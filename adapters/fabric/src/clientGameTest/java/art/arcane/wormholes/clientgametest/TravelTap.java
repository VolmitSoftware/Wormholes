package art.arcane.wormholes.clientgametest;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class TravelTap {
    private static final List<Frame> FRAMES = new ArrayList<>();
    private static final IntList ADDED = new IntArrayList();
    private static final AtomicInteger ACCEPTS = new AtomicInteger();
    private static final IntList RESPAWN_FRAMES = new IntArrayList();
    private static final IntList POSITION_FRAMES = new IntArrayList();
    private static int respawns;
    private static int positions;
    private static boolean loadingScreen;
    private static boolean unloaded;
    private static long tickStarted;
    private static long gameTicks;

    private TravelTap() {
    }

    public static synchronized void reset() {
        FRAMES.clear();
        ADDED.clear();
        RESPAWN_FRAMES.clear();
        POSITION_FRAMES.clear();
        ACCEPTS.set(0);
        respawns = 0;
        positions = 0;
        loadingScreen = false;
        unloaded = false;
    }

    public static synchronized void respawn() {
        respawns++;
        RESPAWN_FRAMES.add(FRAMES.size());
    }

    public static synchronized void position() {
        positions++;
        POSITION_FRAMES.add(FRAMES.size());
    }

    public static synchronized String events() {
        return "respawn frames " + RESPAWN_FRAMES + ", position frames " + POSITION_FRAMES;
    }

    public static synchronized void added(int id) {
        ADDED.add(id);
    }

    public static void accepted() {
        ACCEPTS.incrementAndGet();
    }

    public static void gameTick() {
        gameTicks++;
    }

    public static void tickStarted() {
        tickStarted = System.nanoTime();
    }

    public static synchronized void tickEnded() {
        if (!FRAMES.isEmpty() && FRAMES.getLast().tickNanos() == 0L) {
            Frame last = FRAMES.removeLast();
            FRAMES.add(last.withTickNanos(System.nanoTime() - tickStarted));
        }
    }

    public static synchronized void frame(Minecraft minecraft) {
        Camera camera = minecraft.gameRenderer.mainCamera();
        ClientPacketListener connection = minecraft.getConnection();
        loadingScreen |= minecraft.gui.screen() instanceof LevelLoadingScreen;
        unloaded |= connection != null && !connection.hasClientLoaded();
        if (!camera.isInitialized() || minecraft.level == null || minecraft.player == null) {
            return;
        }
        FRAMES.add(new Frame(FRAMES.size(), camera.position(), camera.yRot(), camera.xRot(),
            minecraft.level.dimension().identifier().toString(), System.identityHashCode(minecraft.level),
            System.identityHashCode(minecraft.player), gameTicks + minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(true),
            minecraft.player.position().distanceTo(new Vec3(minecraft.player.xo, minecraft.player.yo, minecraft.player.zo)),
            Mth.wrapDegrees(minecraft.player.getYRot() - minecraft.player.yHeadRot), 0L));
    }

    public static synchronized List<Frame> frames() {
        return List.copyOf(FRAMES);
    }

    public static synchronized int respawns() {
        return respawns;
    }

    public static synchronized int positions() {
        return positions;
    }

    public static synchronized boolean addedAny(int first, int second) {
        return ADDED.contains(first) || ADDED.contains(second);
    }

    public static int accepts() {
        return ACCEPTS.get();
    }

    public static synchronized boolean loadingScreenShown() {
        return loadingScreen;
    }

    public static synchronized boolean clientUnloaded() {
        return unloaded;
    }

    public static synchronized int crossingFrame(double jump) {
        for (int index = 1; index < FRAMES.size(); index++) {
            if (FRAMES.get(index).camera().distanceTo(FRAMES.get(index - 1).camera()) > jump
                || !FRAMES.get(index).dimension().equals(FRAMES.get(index - 1).dimension())) {
                return index;
            }
        }
        return -1;
    }

    public record Frame(int index, Vec3 camera, float yaw, float pitch, String dimension, int level, int player, double clock, double tickSpeed,
                        float headLag, long tickNanos) {
        Frame withTickNanos(long nanos) {
            return new Frame(index, camera, yaw, pitch, dimension, level, player, clock, tickSpeed, headLag, nanos);
        }
    }
}
