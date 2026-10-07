package art.arcane.wormholes.clientgametest;

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
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

final class DriverClient implements SeamlessClient {
    private static final long STALL_MILLIS = 120_000L;
    private static final int CHUNK_TIMEOUT_TICKS = 1200;
    private static final String REPLY_PREFIX = "[whqa] ";

    private final Minecraft minecraft;
    private final Object tickLock = new Object();
    private final BlockingQueue<String> replies = new LinkedBlockingQueue<>();
    private final Object cursorLock = new Object();
    private long ticks;
    private long cursorTick;
    private double cursorX;
    private double cursorY;

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

    @Override
    public void waitFor(Predicate<Minecraft> condition, int timeoutTicks) {
        for (int tick = 0; tick <= timeoutTicks; tick++) {
            if (computeOnClient(condition::test)) {
                return;
            }
            waitTicks(1);
        }
        throw new AssertionError("condition not met within " + timeoutTicks + " ticks");
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
        synchronized (cursorLock) {
            cursorX += deltaX;
            cursorY += deltaY;
        }
    }

    @Override
    public void restoreDefaultGameOptions() {
        releaseForward();
    }

    void ticked() {
        synchronized (tickLock) {
            ticks++;
            tickLock.notifyAll();
        }
    }

    void cursorFrame() {
        double deltaX;
        double deltaY;
        synchronized (tickLock) {
            if (ticks == cursorTick) {
                return;
            }
            cursorTick = ticks;
        }
        synchronized (cursorLock) {
            deltaX = cursorX;
            deltaY = cursorY;
            cursorX = 0.0D;
            cursorY = 0.0D;
        }
        if ((deltaX != 0.0D || deltaY != 0.0D) && minecraft.player != null) {
            minecraft.player.turn(deltaX, deltaY);
        }
    }

    void systemMessage(String text) {
        if (text.startsWith(REPLY_PREFIX)) {
            replies.add(text.substring(REPLY_PREFIX.length()));
        }
    }

    String command(String command, String reply, int timeoutTicks) {
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

    void clickButton(String translationKey) {
        String text = Component.translatable(translationKey).getString();
        runOnClient(client -> {
            Screen screen = client.gui.screen();
            if (screen == null || !press(screen, text)) {
                throw new AssertionError("no '" + text + "' button on " + (screen == null ? "no screen" : screen.getClass().getName()));
            }
        });
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

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(STALL_MILLIS, TimeUnit.MILLISECONDS);
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
        }
    }
}
