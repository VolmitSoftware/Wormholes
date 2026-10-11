package art.arcane.wormholes.clientgametest;

import io.netty.channel.Channel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

final class DriverClient implements SeamlessClient {
    private static final long STALL_MILLIS = 120_000L;
    private static final int CHUNK_TIMEOUT_TICKS = 1200;
    private static final String REPLY_PREFIX = "[whqa] ";
    private static final long LOCKSTEP_POLL_NANOS = 200_000L;

    private final Minecraft minecraft;
    private final Object tickLock = new Object();
    private final BlockingQueue<String> replies = new LinkedBlockingQueue<>();
    private volatile Lockstep activeLockstep;
    private long ticks;

    DriverClient(Minecraft minecraft) {
        this.minecraft = minecraft;
    }

    @Override
    public void runOnClient(Consumer<Minecraft> action) {
        Runnable task = () -> action.accept(minecraft);
        await(minecraft.submit(task));
    }

    @Override
    public <T> T computeOnClient(Function<Minecraft, T> function) {
        Supplier<T> task = () -> function.apply(minecraft);
        return await(minecraft.submit(task));
    }

    @Override
    public void waitTicks(int count) {
        Lockstep lockstep = activeLockstep;
        if (lockstep != null) {
            for (int tick = 0; tick < count; tick++) {
                lockstep.step(client -> null);
            }
            return;
        }
        awaitClientTicks(count);
    }

    @Override
    public void waitFor(Predicate<Minecraft> condition, int timeoutTicks) {
        try {
            awaitCondition(condition, timeoutTicks);
        } catch (RuntimeException | Error failure) {
            closeLockstep();
            throw failure;
        }
    }

    @Override
    public void waitForChunksDownload() {
        waitFor(DriverClient::chunksLoaded, CHUNK_TIMEOUT_TICKS);
    }

    @Override
    public void waitForChunksRender() {
        waitFor(client -> chunksLoaded(client) && client.levelRenderer.hasRenderedAllSections(), CHUNK_TIMEOUT_TICKS);
    }

    @Override
    public void holdForward() {
        runOnClient(client -> client.options.keyUp.setDown(true));
    }

    @Override
    public void releaseForward() {
        runOnClient(client -> client.options.keyUp.setDown(false));
    }

    @Override
    public void holdForwardFor(int count) {
        holdForward();
        try {
            waitTicks(count);
        } finally {
            releaseForward();
        }
    }

    @Override
    public void lookAt(float yaw, float pitch) {
        runOnClient(client -> {
            client.player.setYRot(yaw);
            client.player.setXRot(pitch);
        });
    }

    @Override
    public void moveCursor(double deltaX, double deltaY) {
        NativeSeamlessInput.moveCursor(this, deltaX, deltaY);
    }

    @Override
    public void restoreDefaultGameOptions() {
        releaseForward();
    }

    @Override
    public TickStepper lockstep() {
        Lockstep previous = activeLockstep;
        if (previous != null) {
            if (computeOnClient(client -> DriverLockstep.isActive(previous.lease))) {
                throw new AssertionError("the client already has a lockstep lease");
            }
            previous.close();
        }
        Thread owner = Thread.currentThread();
        Lockstep lockstep = computeOnClient(client -> new Lockstep(ServerPacketDelivery.channels(client), DriverLockstep.createLease(client, owner)));
        DriverLockstep.activate(lockstep.lease);
        activeLockstep = lockstep;
        return lockstep;
    }

    void ticked() {
        synchronized (tickLock) {
            ticks++;
            DriverLockstep.afterClientTick();
            tickLock.notifyAll();
        }
    }

    void cursorFrame() {
        NativeSeamlessInput.frame(minecraft);
    }

    void systemMessage(String text) {
        if (text.startsWith(REPLY_PREFIX)) {
            replies.add(text.substring(REPLY_PREFIX.length()));
        }
    }

    String command(String command, String reply, int timeoutTicks) {
        try {
            return awaitCommand(command, reply, timeoutTicks);
        } catch (RuntimeException | Error failure) {
            closeLockstep();
            throw failure;
        }
    }

    void clickButton(String translationKey) {
        String text = Component.translatable(translationKey).getString();
        runOnClient(client -> {
            Screen screen = client.gui.screen();
            if (screen == null || !press(screen, text)) {
                throw new AssertionError("no '" + text + "' button on " + (screen == null ? "no screen" : screen.getClass().getName()));
            }
        });
    }

    private void awaitClientTicks(int count) {
        synchronized (tickLock) {
            long target = ticks + count;
            long deadline = System.currentTimeMillis() + STALL_MILLIS;
            while (ticks < target) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0L) {
                    throw new AssertionError("the client stopped ticking for " + STALL_MILLIS + " ms");
                }
                try {
                    tickLock.wait(remaining);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("interrupted while waiting for client ticks", interrupted);
                }
            }
        }
    }

    private void awaitCondition(Predicate<Minecraft> condition, int timeoutTicks) {
        for (int tick = 0; tick <= timeoutTicks; tick++) {
            if (computeOnClient(condition::test)) {
                return;
            }
            waitTicks(1);
        }
        throw new AssertionError("condition not met within " + timeoutTicks + " ticks");
    }

    private String awaitCommand(String command, String reply, int timeoutTicks) {
        Lockstep lockstep = activeLockstep;
        if (lockstep != null) {
            DriverLockstep.requireOwner(lockstep.lease);
        }
        replies.clear();
        runOnClient(client -> client.getConnection().sendCommand(command));
        for (int tick = 0; tick <= timeoutTicks; tick++) {
            String received = replies.poll();
            while (received != null) {
                if (received.startsWith("error ")) {
                    throw new AssertionError("/" + command + " failed on the server: " + received.substring(6));
                }
                if (received.startsWith(reply + " ") || received.equals(reply)) {
                    return received.substring(Math.min(received.length(), reply.length() + 1));
                }
                received = replies.poll();
            }
            waitTicks(1);
        }
        throw new AssertionError("/" + command + " got no '" + reply + "' reply within " + timeoutTicks + " ticks");
    }

    private void awaitClientTick(DriverLockstep.Lease lease, long target) {
        long deadline = System.currentTimeMillis() + STALL_MILLIS;
        synchronized (tickLock) {
            while (DriverLockstep.clientTicks(lease) < target) {
                DriverLockstep.requireOwner(lease);
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0L) {
                    throw new AssertionError("the client stopped ticking for " + STALL_MILLIS + " ms");
                }
                try {
                    tickLock.wait(Math.min(remaining, 50L));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("interrupted while waiting for client ticks", interrupted);
                }
            }
        }
        DriverLockstep.requireOwner(lease);
    }

    private static boolean chunksLoaded(Minecraft client) {
        ClientLevel level = client.level;
        if (level == null || client.player == null) {
            return false;
        }
        int radius = client.options.getEffectiveRenderDistance();
        ChunkPos center = client.player.chunkPosition();
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                if (level.getChunk(center.x() + dx, center.z() + dz, ChunkStatus.FULL, false) == null) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean press(ContainerEventHandler container, String text) {
        for (GuiEventListener child : container.children()) {
            if (child instanceof AbstractButton button && text.equals(button.getMessage().getString())) {
                button.onPress(new MouseButtonInfo(1, 0));
                return true;
            }
            if (child instanceof ContainerEventHandler nested && press(nested, text)) {
                return true;
            }
        }
        return false;
    }

    private static void awaitServerTick(DriverLockstep.Lease lease, long before) {
        long deadline = System.currentTimeMillis() + STALL_MILLIS;
        while (DriverLockstep.serverTicks(lease) <= before) {
            DriverLockstep.requireOwner(lease);
            if (Thread.currentThread().isInterrupted()) {
                throw new AssertionError("interrupted while waiting for the integrated server tick");
            }
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("the integrated server did not tick within " + STALL_MILLIS + " ms");
            }
            LockSupport.parkNanos(LOCKSTEP_POLL_NANOS);
        }
    }

    private <T> T await(CompletableFuture<T> future) {
        boolean completed = false;
        try {
            T result = future.get(STALL_MILLIS, TimeUnit.MILLISECONDS);
            completed = true;
            return result;
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof AssertionError assertion) {
                throw assertion;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new AssertionError("client task failed", cause);
        } catch (TimeoutException stalled) {
            throw new AssertionError("client task did not run within " + STALL_MILLIS + " ms", stalled);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for the client", interrupted);
        } finally {
            if (!completed) {
                if (activeLockstep != null) {
                    future.cancel(false);
                }
                closeLockstep();
            }
        }
    }

    private void closeLockstep() {
        Lockstep lockstep = activeLockstep;
        if (lockstep != null) {
            lockstep.close();
        }
    }

    private static void drain(Channel first, Channel second) {
        awaitPacketDelivery(first.eventLoop().submit(() -> { }));
        awaitPacketDelivery(second.eventLoop().submit(() -> { }));
    }

    private static void awaitPacketDelivery(Future<?> future) {
        try {
            future.get(STALL_MILLIS, TimeUnit.MILLISECONDS);
        } catch (ExecutionException failure) {
            throw new AssertionError("lockstep packet delivery failed", failure.getCause());
        } catch (TimeoutException stalled) {
            throw new AssertionError("lockstep packet delivery did not complete within " + STALL_MILLIS + " ms", stalled);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for lockstep packet delivery", interrupted);
        }
    }

    private final class Lockstep implements TickStepper {
        private final ServerPacketDelivery.Channels channels;
        private final DriverLockstep.Lease lease;
        private boolean stepping;

        private Lockstep(ServerPacketDelivery.Channels channels, DriverLockstep.Lease lease) {
            this.channels = channels;
            this.lease = lease;
        }

        @Override
        public <T> T step(Function<Minecraft, T> sample) {
            try {
                DriverLockstep.requireOwner(lease);
                if (stepping) {
                    throw new AssertionError("a lockstep tick is already in progress");
                }
                stepping = true;
                computeOnClient(client -> {
                    DriverLockstep.requireWorld(lease);
                    return null;
                });
                drain(channels.client(), channels.server());
                long served = DriverLockstep.serverTicks(lease);
                DriverLockstep.permitServerTick(lease);
                awaitServerTick(lease, served);
                drain(channels.server(), channels.client());
                long target = DriverLockstep.clientTicks(lease) + 1;
                DriverLockstep.permitClientTick(lease);
                awaitClientTick(lease, target);
                return computeOnClient(client -> {
                    DriverLockstep.requireWorld(lease);
                    return sample.apply(client);
                });
            } catch (RuntimeException | Error failure) {
                close();
                throw failure;
            } finally {
                stepping = false;
            }
        }

        @Override
        public void close() {
            DriverLockstep.deactivate(lease);
            if (activeLockstep == this) {
                activeLockstep = null;
            }
        }
    }
}
