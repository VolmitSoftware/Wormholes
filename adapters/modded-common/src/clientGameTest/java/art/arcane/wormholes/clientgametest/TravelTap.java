package art.arcane.wormholes.clientgametest;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;
import org.slf4j.Logger;

import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public final class TravelTap {
    private static final Logger LOGGER = LogUtils.getLogger();
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
    private static int captureSequence;
    private static int captureFramesRemaining;
    private static ClientFrameEvent profileFrame;
    private static ClientRenderEvent profileRender;

    private TravelTap() {
    }

    public static synchronized void reset() {
        SeamlessTurningTap.close();
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
        captureFramesRemaining = 0;
        profileFrame = null;
        profileRender = null;
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
        SeamlessTurningTap.gameTick();
    }

    static long gameTickIndex() {
        return gameTicks;
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
        finishProfileRender(minecraft);
        Camera camera = minecraft.gameRenderer.mainCamera();
        ClientPacketListener connection = minecraft.getConnection();
        loadingScreen |= minecraft.gui.screen() instanceof LevelLoadingScreen;
        unloaded |= connection != null && !connection.hasClientLoaded();
        if (!camera.isInitialized() || minecraft.level == null || minecraft.player == null) {
            return;
        }
        SeamlessTurningTap.rendered(minecraft);
        VerticalCameraLoopTap.rendered(minecraft);
        CrossingCompositionTap.frame(minecraft);
        NetherCrossingCompositionTap.frame(minecraft);
        captureCrossingFrames(minecraft, camera);
        CameraRenderState rendered = minecraft.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
        EntityDimensions dimensions = minecraft.player.getDimensions(minecraft.player.getPose());
        float partialTicks = camera.getCameraEntityPartialTicks(minecraft.getDeltaTracker());
        PlayerPose pose = new PlayerPose(minecraft.player.getPosition(partialTicks),
            minecraft.player.getEyePosition(partialTicks), minecraft.player.getBbWidth(), minecraft.player.getBbHeight(),
            minecraft.player.getEyeHeight(), dimensions);
        FRAMES.add(new Frame(FRAMES.size(), camera.position(), camera.yRot(), camera.xRot(),
            minecraft.level.dimension().identifier().toString(), System.identityHashCode(minecraft.level),
            System.identityHashCode(minecraft.player), gameTicks + minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(true),
            minecraft.player.position().distanceTo(new Vec3(minecraft.player.xo, minecraft.player.yo, minecraft.player.zo)),
            Mth.wrapDegrees(minecraft.player.getYRot() - minecraft.player.yHeadRot), 0L, vector(camera.forwardVector()), vector(camera.upVector()),
            minecraft.player.getAttributeValue(Attributes.SCALE), System.nanoTime(), pose, rendered.pos, minecraft.options.getCameraType().isFirstPerson()));
        profileFrame(FRAMES.getLast());
    }

    public static void frameStarted(Minecraft minecraft) {
        if (Boolean.getBoolean("wormholes.clientgametest.profileFrames") && minecraft.level != null) {
            profileRender = new ClientRenderEvent();
            profileRender.frame = frameIndex();
            profileRender.begin();
        }
        NetherCrossingCompositionTap.beforeFrame(minecraft);
    }

    private static void finishProfileRender(Minecraft minecraft) {
        if (profileRender == null) {
            return;
        }
        profileRender.end();
        if (minecraft.level != null) {
            profileRender.dimension = minecraft.level.dimension().identifier().toString();
            profileRender.level = System.identityHashCode(minecraft.level);
            profileRender.commit();
        }
        profileRender = null;
    }

    private static void captureCrossingFrames(Minecraft minecraft, Camera camera) {
        if (!Boolean.getBoolean("wormholes.clientgametest.captureCrossingFrames")) {
            return;
        }
        if (!FRAMES.isEmpty()) {
            Frame previous = FRAMES.getLast();
            if (previous.level() != System.identityHashCode(minecraft.level)
                || previous.camera().distanceTo(camera.position()) > 4.0D) {
                captureSequence++;
                captureFramesRemaining = 4;
            }
        }
        if (captureFramesRemaining <= 0) {
            return;
        }
        String name = "crossing-frame-" + captureSequence + "-" + (4 - captureFramesRemaining) + ".png";
        captureFramesRemaining--;
        Screenshot.grab(minecraft.gameDirectory, name, minecraft.gameRenderer.mainRenderTarget(), 1,
            message -> LOGGER.info("Crossing capture: {}", message.getString()));
    }

    public static synchronized int frameIndex() {
        return FRAMES.size();
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

    public static synchronized int bodyCrossingFrame(double jump) {
        for (int index = 1; index < FRAMES.size(); index++) {
            Frame before = FRAMES.get(index - 1);
            Frame after = FRAMES.get(index);
            if (after.pose().feet().distanceTo(before.pose().feet()) > jump || !after.dimension().equals(before.dimension())) {
                return index;
            }
        }
        return -1;
    }

    private static void profileFrame(Frame frame) {
        if (!Boolean.getBoolean("wormholes.clientgametest.profileFrames")) {
            return;
        }
        if (profileFrame != null) {
            profileFrame.end();
            profileFrame.frame = frame.index();
            profileFrame.dimension = frame.dimension();
            profileFrame.level = frame.level();
            profileFrame.cameraX = frame.camera().x;
            profileFrame.cameraY = frame.camera().y;
            profileFrame.cameraZ = frame.camera().z;
            profileFrame.commit();
        }
        profileFrame = new ClientFrameEvent();
        profileFrame.begin();
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
                        float headLag, long tickNanos, Vec3 forward, Vec3 up, double scale, long wallNanos,
                        PlayerPose pose, Vec3 renderedCamera, boolean firstPerson) {
        Frame withTickNanos(long nanos) {
            return new Frame(index, camera, yaw, pitch, dimension, level, player, clock, tickSpeed, headLag, nanos, forward, up, scale, wallNanos,
                pose, renderedCamera, firstPerson);
        }
    }

    public record PlayerPose(Vec3 feet, Vec3 eye, float width, float height, float eyeHeight, EntityDimensions dimensions) {
    }

    @Name("wormholes.ClientFrame")
    @Label("Client frame interval")
    @StackTrace(false)
    private static final class ClientFrameEvent extends Event {
        int frame;
        String dimension;
        int level;
        double cameraX;
        double cameraY;
        double cameraZ;
    }

    @Name("wormholes.ClientRender")
    @Label("GameRenderer render phase")
    @StackTrace(false)
    private static final class ClientRenderEvent extends Event {
        int frame;
        String dimension;
        int level;
    }

    public record ScalePacket(int frame, double before) {
    }
}
