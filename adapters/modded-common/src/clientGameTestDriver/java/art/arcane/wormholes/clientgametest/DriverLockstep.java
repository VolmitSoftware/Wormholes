package art.arcane.wormholes.clientgametest;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.server.MinecraftServer;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class DriverLockstep {
    private static final Object LEASE_LOCK = new Object();
    private static final ThreadLocal<AtomicLong> SERVER_TICK_COUNTER = new ThreadLocal<>();
    private static final ThreadLocal<AtomicLong> CLIENT_TICK_COUNTER = new ThreadLocal<>();
    private static volatile Lease active;

    private DriverLockstep() {
    }

    public static int clientTicks(Minecraft minecraft, int scheduled) {
        CLIENT_TICK_COUNTER.remove();
        while (true) {
            Lease lease = active;
            if (lease == null) {
                return scheduled;
            }
            minecraft.managedBlock(() -> released(lease) || lease.clientPermits.get() > 0);
            synchronized (LEASE_LOCK) {
                if (active == lease && lease.clientPermits.compareAndSet(1, 0)) {
                    CLIENT_TICK_COUNTER.set(lease.clientTicks);
                    return 1;
                }
            }
        }
    }

    public static void beforeServerTick(MinecraftServer server) {
        SERVER_TICK_COUNTER.remove();
        while (true) {
            Lease lease = active;
            if (lease == null || lease.server != server) {
                return;
            }
            server.managedBlock(() -> released(lease) || lease.serverPermits.get() > 0);
            synchronized (LEASE_LOCK) {
                if (active == lease && lease.serverPermits.compareAndSet(1, 0)) {
                    SERVER_TICK_COUNTER.set(lease.serverTicks);
                    return;
                }
            }
        }
    }

    public static void afterServerTick() {
        AtomicLong counter = SERVER_TICK_COUNTER.get();
        SERVER_TICK_COUNTER.remove();
        if (counter != null) {
            counter.incrementAndGet();
        }
    }

    static void afterClientTick() {
        AtomicLong counter = CLIENT_TICK_COUNTER.get();
        CLIENT_TICK_COUNTER.remove();
        if (counter != null) {
            counter.incrementAndGet();
        }
    }

    static Lease createLease(Minecraft minecraft, Thread owner) {
        MinecraftServer server = minecraft.getSingleplayerServer();
        ClientPacketListener connection = minecraft.getConnection();
        if (server == null || connection == null || minecraft.player == null) {
            throw new AssertionError("lockstep needs a singleplayer connection");
        }
        return new Lease(minecraft, new Session(server, connection, owner));
    }

    static void activate(Lease lease) {
        synchronized (LEASE_LOCK) {
            if (active != null) {
                throw new AssertionError("a native lockstep lease is already active");
            }
            if (lease.owner != Thread.currentThread()) {
                throw new AssertionError("only the lockstep owner can activate its lease");
            }
            if (lease.closed) {
                throw new AssertionError("the native lockstep lease is closed");
            }
            active = lease;
        }
    }

    static void deactivate(Lease lease) {
        synchronized (LEASE_LOCK) {
            lease.closed = true;
            lease.serverPermits.set(0);
            lease.clientPermits.set(0);
            if (active == lease) {
                active = null;
            }
        }
    }

    static void requireOwner(Lease lease) {
        if (lease.owner != Thread.currentThread()) {
            throw new AssertionError("only the lockstep owner can advance its lease");
        }
        if (active != lease) {
            throw new AssertionError("the native lockstep lease is closed");
        }
    }

    static void requireWorld(Lease lease) {
        if (released(lease)) {
            throw new AssertionError("the native lockstep connection changed or its lease closed");
        }
    }

    static boolean isActive(Lease lease) {
        return !released(lease);
    }

    static long serverTicks(Lease lease) {
        return lease.serverTicks.get();
    }

    static long clientTicks(Lease lease) {
        return lease.clientTicks.get();
    }

    static void permitServerTick(Lease lease) {
        synchronized (LEASE_LOCK) {
            requireOwner(lease);
            if (!lease.serverPermits.compareAndSet(0, 1)) {
                throw new AssertionError("a server tick permit is already pending");
            }
        }
    }

    static void permitClientTick(Lease lease) {
        synchronized (LEASE_LOCK) {
            requireOwner(lease);
            if (!lease.clientPermits.compareAndSet(0, 1)) {
                throw new AssertionError("a client tick permit is already pending");
            }
        }
    }

    private static boolean released(Lease lease) {
        if (active != lease) {
            return true;
        }
        if (!lease.owner.isAlive() || lease.minecraft.getSingleplayerServer() != lease.server
            || lease.minecraft.getConnection() != lease.connection || lease.minecraft.player == null) {
            deactivate(lease);
            return true;
        }
        return false;
    }

    static final class Lease {
        private final Minecraft minecraft;
        private final MinecraftServer server;
        private final ClientPacketListener connection;
        private final Thread owner;
        private final AtomicInteger serverPermits = new AtomicInteger();
        private final AtomicInteger clientPermits = new AtomicInteger();
        private final AtomicLong serverTicks = new AtomicLong();
        private final AtomicLong clientTicks = new AtomicLong();
        private boolean closed;

        private Lease(Minecraft minecraft, Session session) {
            this.minecraft = minecraft;
            this.server = session.server();
            this.connection = session.connection();
            this.owner = session.owner();
        }
    }

    private record Session(MinecraftServer server, ClientPacketListener connection, Thread owner) {
    }
}
