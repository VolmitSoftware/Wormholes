package art.arcane.wormholes.clientgametest;

import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class DriverLockstep {
    private static final AtomicInteger SERVER_PERMITS = new AtomicInteger();
    private static final AtomicInteger CLIENT_PERMITS = new AtomicInteger();
    private static final AtomicLong SERVER_TICKS = new AtomicLong();
    private static volatile boolean active;

    private DriverLockstep() {
    }

    public static int clientTicks(Minecraft minecraft, int scheduled) {
        if (!active) {
            return scheduled;
        }
        minecraft.managedBlock(() -> !active || CLIENT_PERMITS.get() > 0);
        if (!active) {
            return scheduled;
        }
        CLIENT_PERMITS.decrementAndGet();
        return 1;
    }

    public static void beforeServerTick(MinecraftServer server) {
        if (!active) {
            return;
        }
        server.managedBlock(() -> !active || SERVER_PERMITS.get() > 0);
        if (active) {
            SERVER_PERMITS.decrementAndGet();
        }
    }

    public static void afterServerTick() {
        SERVER_TICKS.incrementAndGet();
    }

    static void activate() {
        SERVER_PERMITS.set(0);
        CLIENT_PERMITS.set(0);
        active = true;
    }

    static void deactivate() {
        active = false;
    }

    static long serverTicks() {
        return SERVER_TICKS.get();
    }

    static void permitServerTick() {
        SERVER_PERMITS.incrementAndGet();
    }

    static void permitClientTick() {
        CLIENT_PERMITS.incrementAndGet();
    }
}
