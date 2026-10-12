package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.network.client.TravelMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class VerticalCameraLoopTap {
    private static final Logger LOGGER = LoggerFactory.getLogger("WormholesClientGameTest");
    private static final List<Crossing> CROSSINGS = new ArrayList<>();
    private static final List<Rendered> RENDERED = new ArrayList<>();
    private static String label;
    private static boolean active;
    private static int capturedCrossings;

    private VerticalCameraLoopTap() {
    }

    static void open(String name) {
        CROSSINGS.clear();
        RENDERED.clear();
        label = name;
        capturedCrossings = 0;
        active = true;
    }

    static void close() {
        active = false;
    }

    public static Attempt entering(Minecraft minecraft, LocalPlayer player, TravelMessage.TravelBegin arm,
                                   float partial, Vec3 previous, Vec3 eye) {
        if (!active) {
            return null;
        }
        ClientLevel target = arm.resident() ? WormholesClient.instance().seamlessTravel().residents().level(arm.levelHandle()) : minecraft.level;
        return new Attempt(TravelTap.frameIndex(), arm, System.identityHashCode(target), partial, previous, eye, pose(minecraft, player));
    }

    public static void returned(Attempt attempt, Minecraft minecraft, Vec3 result) {
        if (active && attempt != null && result != null) {
            CROSSINGS.add(new Crossing(attempt, result, pose(minecraft, minecraft.player)));
        }
    }

    public static void rendered(Minecraft minecraft) {
        if (!active) {
            return;
        }
        float partial = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(true);
        RENDERED.add(new Rendered(TravelTap.frameIndex(), partial, pose(minecraft, minecraft.player), minecraft.player.getViewVector(partial)));
        if (RENDERED.size() == 1 || capturedCrossings < CROSSINGS.size()) {
            String name = "vertical-camera-" + label + "-crossing-" + CROSSINGS.size() + ".png";
            Screenshot.grab(minecraft.gameDirectory, name, minecraft.gameRenderer.mainRenderTarget(), 1,
                message -> LOGGER.info("Vertical camera frame sample: {}", message.getString()));
            capturedCrossings = CROSSINGS.size();
        }
    }

    static List<Crossing> crossings() {
        return List.copyOf(CROSSINGS);
    }

    static List<Rendered> frames() {
        return List.copyOf(RENDERED);
    }

    static Pose pose(Minecraft minecraft, LocalPlayer player) {
        if (player == null || minecraft.level == null) {
            throw new AssertionError("Vertical loop lost its native player or level");
        }
        return new Pose(System.identityHashCode(player), System.identityHashCode(minecraft.level), player.position(), player.getDeltaMovement(),
            player.getYRot(), player.yRotO, player.getXRot(), player.xRotO);
    }

    record Pose(int player, int level, Vec3 position, Vec3 velocity, float yaw, float oldYaw, float pitch, float oldPitch) {
    }

    public record Attempt(int frame, TravelMessage.TravelBegin arm, int target, float partial, Vec3 previousEye, Vec3 eye, Pose entry) {
    }

    record Crossing(Attempt attempt, Vec3 checkpoint, Pose returned) {
        Evidence evidence() {
            return new Evidence(attempt.frame(), attempt.arm().sourcePortal(), attempt.arm().token(), attempt.arm().generation(), attempt.target(),
                attempt.arm().rules().gravityFlip(), attempt.partial(), attempt.previousEye(), attempt.eye(), attempt.entry(), returned);
        }
    }

    record Evidence(int frame, UUID portal, UUID token, long generation, int target, boolean gravityFlip, float partial, Vec3 previousEye, Vec3 eye,
                    Pose entry, Pose returned) {
    }

    record Rendered(int frame, float partial, Pose pose, Vec3 interpolatedForward) {
    }
}
