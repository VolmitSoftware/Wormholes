package art.arcane.wormholes.clientgametest;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public final class TravelTap {
    private static final List<Frame> FRAMES = new ArrayList<>();
    private static final Set<UUID> ADDED = new HashSet<>();
    private static final AtomicInteger ACCEPTS = new AtomicInteger();
    private static final IntList RESPAWN_FRAMES = new IntArrayList();
    private static final IntList POSITION_FRAMES = new IntArrayList();
    private static final List<ScalePacket> SCALE_PACKETS = new ArrayList<>();
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
        SCALE_PACKETS.clear();
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

    public static synchronized void scalePacket(double before) {
        SCALE_PACKETS.add(new ScalePacket(FRAMES.size(), before));
    }

    public static synchronized List<ScalePacket> scalePackets() {
        return List.copyOf(SCALE_PACKETS);
    }

    public static synchronized String events() {
        return "respawn frames " + RESPAWN_FRAMES + ", position frames " + POSITION_FRAMES;
    }

    public static synchronized void added(UUID entity) {
        ADDED.add(entity);
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
            Mth.wrapDegrees(minecraft.player.getYRot() - minecraft.player.yHeadRot), 0L, vector(camera.forwardVector()), vector(camera.upVector()),
            minecraft.player.getAttributeValue(Attributes.SCALE), System.nanoTime()));
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

    public static synchronized int respawnsSince(int frame) {
        return countSince(RESPAWN_FRAMES, frame);
    }

    public static synchronized int positionsSince(int frame) {
        return countSince(POSITION_FRAMES, frame);
    }

    public static synchronized boolean addedAny(UUID first, UUID second) {
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
        return crossingFrame(jump, 1);
    }

    public static synchronized int crossingFrame(double jump, int from) {
        for (int index = Math.max(1, from); index < FRAMES.size(); index++) {
            if (FRAMES.get(index).camera().distanceTo(FRAMES.get(index - 1).camera()) > jump
                || !FRAMES.get(index).dimension().equals(FRAMES.get(index - 1).dimension())) {
                return index;
            }
        }
        return -1;
    }

    private static Vec3 vector(Vector3fc vector) {
        return new Vec3(vector.x(), vector.y(), vector.z());
    }

    private static int countSince(IntList frames, int frame) {
        int count = 0;
        for (int index = 0; index < frames.size(); index++) {
            if (frames.getInt(index) >= frame) {
                count++;
            }
        }
        return count;
    }

    public record Frame(int index, Vec3 camera, float yaw, float pitch, String dimension, int level, int player, double clock, double tickSpeed,
                        float headLag, long tickNanos, Vec3 forward, Vec3 up, double scale, long wallNanos) {
        Frame withTickNanos(long nanos) {
            return new Frame(index, camera, yaw, pitch, dimension, level, player, clock, tickSpeed, headLag, nanos, forward, up, scale, wallNanos);
        }
    }

    public record ScalePacket(int frame, double before) {
    }
}
