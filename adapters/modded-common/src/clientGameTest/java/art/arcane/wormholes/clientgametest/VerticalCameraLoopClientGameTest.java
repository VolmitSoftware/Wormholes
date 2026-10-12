package art.arcane.wormholes.clientgametest;

import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.network.client.TravelMessage;
import com.google.gson.GsonBuilder;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

import static art.arcane.wormholes.clientgametest.SeamlessScenario.assertTrue;

public final class VerticalCameraLoopClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("WormholesClientGameTest");
    private static final int PREPARE_TICKS = 600;
    private static final int FALL_TICKS = 240;
    private static final int CROSSINGS = 8;
    private static final float ANGLE_TOLERANCE = 0.5F;
    private static final double SPEED_INCREASE = 0.05D;
    private static final double POSITION_TOLERANCE = 0.05D;
    private static final Vec3 HOVER = new Vec3(SeamlessFallLoop.PLAYER_FLOOR.getX() + 1.5D,
        SeamlessFallLoop.PLAYER_FLOOR.getY() + SeamlessFallLoop.SHAFT - 6.0D, SeamlessFallLoop.PLAYER_FLOOR.getZ() + 1.5D);
    private static final List<View> VIEWS = List.of(new View("down", 37.0F, 90.0F), new View("near-down", -53.0F, 89.0F),
        new View("up", 137.0F, -90.0F), new View("near-up", -143.0F, -89.0F));

    private VerticalCameraLoopClientGameTest() {
    }

    static void run(SeamlessClient client, SeamlessServer server, String label, Consumer<Boolean> gravityFlip) {
        SeamlessScenario.join(client);
        SeamlessScenario.assertSeamlessNegotiated(client);
        try {
            for (boolean enabled : List.of(false, true)) {
                gravityFlip.accept(enabled);
                for (CameraType camera : List.of(CameraType.FIRST_PERSON, CameraType.THIRD_PERSON_BACK)) {
                    for (View view : VIEWS) {
                        runCase(client, server, new Case(label + "-gravity-" + enabled + '-' + camera.name().toLowerCase(Locale.ROOT) + '-' + view.name(),
                            camera, view, enabled));
                    }
                }
            }
        } finally {
            client.runOnClient(minecraft -> VerticalCameraLoopTap.close());
            client.restoreDefaultGameOptions();
            gravityFlip.accept(false);
        }
        LOGGER.info("WORMHOLES_VERTICAL_CAMERA_LOOP_PASS {}: sixteen gravity/view cases, {} physical crossings, {} degree tolerance", label,
            VIEWS.size() * 4 * CROSSINGS, ANGLE_TOLERANCE);
    }

    private static void runCase(SeamlessClient client, SeamlessServer server, Case scenario) {
        SeamlessFallLoop.Loop loop = server.buildFallLoop();
        try {
            client.waitForChunksDownload();
            client.waitFor(minecraft -> minecraft.player != null && minecraft.player.getAbilities().flying
                && minecraft.player.position().distanceTo(HOVER) < POSITION_TOLERANCE, PREPARE_TICKS);
            client.runOnClient(minecraft -> {
                hidden(minecraft);
                minecraft.options.setCameraType(scenario.camera());
                minecraft.options.bobView().set(false);
                minecraft.options.fovEffectScale().set(0.0D);
                minecraft.options.framerateLimit().set(120);
            });
            client.lookAt(scenario.view().yaw(), scenario.view().pitch());
            client.waitTicks(3);
            client.waitForChunksRender();
            client.waitFor(minecraft -> SeamlessScenario.ready(minecraft) && ready(minecraft, loop.portals().getFirst(), scenario.gravityFlip()), PREPARE_TICKS);
            client.runOnClient(minecraft -> {
                assertPose(scenario, VerticalCameraLoopTap.pose(minecraft, minecraft.player), "frozen departure");
                assertTrue(minecraft.player.getAbilities().flying && minecraft.player.position().distanceTo(HOVER) < POSITION_TOLERANCE,
                    scenario.label() + ": player moved before the physical fall");
                TravelTap.reset();
                VerticalCameraLoopTap.open(scenario.label());
            });
            List<VerticalCameraLoopTap.Pose> ticks = new ArrayList<>(FALL_TICKS);
            try {
                server.startFallLoop();
                try (TickStepper stepper = client.lockstep()) {
                    for (int tick = 0; tick < FALL_TICKS; tick++) {
                        VerticalCameraLoopTap.Pose pose = stepper.step(minecraft -> {
                            hidden(minecraft);
                            return VerticalCameraLoopTap.pose(minecraft, minecraft.player);
                        });
                        ticks.add(pose);
                        assertPose(scenario, pose, "fall tick " + tick);
                        assertTrue(pose.position().y >= SeamlessFallLoop.PLAYER_FLOOR.getY() - 6.0D,
                            scenario.label() + ": player fell outside the physical shaft: " + pose);
                        if (client.computeOnClient(minecraft -> VerticalCameraLoopTap.crossings().size()) >= CROSSINGS) {
                            stepper.step(minecraft -> VerticalCameraLoopTap.pose(minecraft, minecraft.player));
                            stepper.step(minecraft -> VerticalCameraLoopTap.pose(minecraft, minecraft.player));
                            break;
                        }
                    }
                }
                client.runOnClient(minecraft -> verify(minecraft, scenario, loop, ticks));
            } finally {
                client.runOnClient(minecraft -> {
                    VerticalCameraLoopTap.close();
                    persist(minecraft, scenario, ticks);
                });
            }
        } finally {
            server.removeFallLoop(loop);
        }
    }

    private static boolean ready(Minecraft minecraft, UUID portal, boolean gravityFlip) {
        for (TravelMessage.TravelBegin arm : WormholesClient.instance().seamlessTravel().arms()) {
            if (!arm.sourcePortal().equals(portal)) {
                continue;
            }
            assertTrue(arm.rules().gravityFlip() == gravityFlip, "Vertical loop arm did not use the configured gravity-flip setting");
            Vec3d moved = arm.sourceToDestination().point(new Vec3d(HOVER.x, HOVER.y, HOVER.z));
            assertTrue(moved.subtract(new Vec3d(HOVER.x, HOVER.y + SeamlessFallLoop.SHAFT, HOVER.z)).length() < 1.0E-6D
                && arm.scale() == 1.0F, "Vertical loop arm must translate the physical floor to its ceiling without scale");
            for (Vec3d axis : List.of(new Vec3d(1, 0, 0), new Vec3d(0, 1, 0), new Vec3d(0, 0, 1))) {
                assertTrue(arm.sourceToDestination().vector(axis).subtract(axis).length() < 1.0E-6D,
                    "Vertical loop must preserve all three orientation axes");
            }
            return !arm.resident() || WormholesClient.instance().seamlessTravel().residents().level(arm.levelHandle()) != null;
        }
        return false;
    }

    private static void verify(Minecraft minecraft, Case scenario, SeamlessFallLoop.Loop loop, List<VerticalCameraLoopTap.Pose> ticks) {
        List<VerticalCameraLoopTap.Crossing> crossings = VerticalCameraLoopTap.crossings();
        List<VerticalCameraLoopTap.Rendered> rendered = VerticalCameraLoopTap.frames();
        List<TravelTap.Frame> frames = TravelTap.frames();
        assertTrue(crossings.size() == CROSSINGS, scenario.label() + ": expected exactly " + CROSSINGS + " real native crossings, got " + crossings.size());
        assertTrue(!rendered.isEmpty() && rendered.size() == frames.size(), scenario.label() + ": missing rendered native pose/frame samples");
        assertTrue(TravelTap.respawns() == 0 && TravelTap.positions() == 0 && TravelTap.accepts() == 0
            && !TravelTap.loadingScreenShown() && !TravelTap.clientUnloaded(), scenario.label() + ": loop used vanilla teleport or unload: " + TravelTap.events());
        int player = ticks.getFirst().player();
        double previousSpeed = 0.0D;
        for (int index = 0; index < crossings.size(); index++) {
            VerticalCameraLoopTap.Crossing crossing = crossings.get(index);
            VerticalCameraLoopTap.Attempt attempt = crossing.attempt();
            assertTrue(attempt.arm().sourcePortal().equals(loop.portals().getFirst()), scenario.label() + ": native crossing used another source portal");
            assertTrue(attempt.arm().rules().gravityFlip() == scenario.gravityFlip(), scenario.label() + ": native crossing used another gravity-flip setting");
            assertTrue(attempt.entry().player() == player && crossing.returned().player() == player
                && crossing.returned().level() == attempt.target(), scenario.label() + ": native crossing replaced the player or missed its armed destination");
            assertPose(scenario, attempt.entry(), "native entry " + index);
            assertPose(scenario, crossing.returned(), "native return " + index);
            Vec3 translated = attempt.entry().position().add(0.0D, SeamlessFallLoop.SHAFT, 0.0D);
            assertTrue(crossing.returned().position().distanceTo(translated) < POSITION_TOLERANCE,
                scenario.label() + ": crossing did not physically translate along the linked shaft: " + crossing.evidence());
            double speed = -attempt.entry().velocity().y;
            assertTrue(speed > previousSpeed + SPEED_INCREASE, scenario.label() + ": fall did not accelerate between crossings " + index
                + ": " + previousSpeed + " -> " + speed);
            previousSpeed = speed;
            assertTrue(crossing.returned().velocity().distanceTo(attempt.entry().velocity()) < 1.0E-6D,
                scenario.label() + ": native crossing reset falling momentum");
            assertTrue(attempt.frame() > 0 && attempt.frame() < frames.size(), scenario.label() + ": missing before/after rendered crossing frames");
            LOGGER.info("[{}] crossing {} at frame {}, speed {}, entry {}, return {}", scenario.label(), index + 1, attempt.frame(), speed,
                attempt.entry(), crossing.returned());
        }
        Vec3 expected = Vec3.directionFromRotation(scenario.view().pitch(), scenario.view().yaw());
        Vec3 expectedUp = Vec3.directionFromRotation(scenario.view().pitch() - 90.0F, scenario.view().yaw());
        Vec3 expectedRight = expected.cross(expectedUp);
        for (int index = 0; index < frames.size(); index++) {
            TravelTap.Frame frame = frames.get(index);
            VerticalCameraLoopTap.Rendered render = rendered.get(index);
            assertTrue(render.frame() == frame.index() && frame.player() == player && render.pose().player() == player,
                scenario.label() + ": rendered pose/frame/player identity mismatch at " + index);
            assertPose(scenario, render.pose(), "rendered pose " + index);
            assertAngle(frame.yaw(), scenario.view().yaw(), scenario.label() + ": rendered yaw frame " + index);
            assertAngle(frame.pitch(), scenario.view().pitch(), scenario.label() + ": rendered pitch frame " + index);
            assertDirection(expected, frame.forward(), scenario.label() + ": rendered forward frame " + index);
            assertDirection(expectedUp, frame.up(), scenario.label() + ": rendered camera up/roll frame " + index);
            assertDirection(expectedRight, frame.forward().cross(frame.up()), scenario.label() + ": rendered camera right frame " + index);
            assertDirection(expected, render.interpolatedForward(), scenario.label() + ": interpolated forward frame " + index);
            if (index > 0) {
                assertAngle(frame.yaw(), frames.get(index - 1).yaw(), scenario.label() + ": inter-frame cardinal yaw jump " + index);
                assertAngle(frame.pitch(), frames.get(index - 1).pitch(), scenario.label() + ": inter-frame pitch reset " + index);
            }
        }
        hidden(minecraft);
        LOGGER.info("[{}] {} accelerating physical crossings, {} rendered frames, last speed {}, tolerance {} degrees", scenario.label(),
            crossings.size(), frames.size(), previousSpeed, ANGLE_TOLERANCE);
    }

    private static void assertPose(Case scenario, VerticalCameraLoopTap.Pose pose, String stage) {
        assertAngle(pose.yaw(), scenario.view().yaw(), scenario.label() + ": " + stage + " current yaw");
        assertAngle(pose.oldYaw(), scenario.view().yaw(), scenario.label() + ": " + stage + " previous yaw");
        assertAngle(pose.pitch(), scenario.view().pitch(), scenario.label() + ": " + stage + " current pitch");
        assertAngle(pose.oldPitch(), scenario.view().pitch(), scenario.label() + ": " + stage + " previous pitch");
    }

    private static void assertAngle(float actual, float expected, String label) {
        assertTrue(Float.isFinite(actual) && Math.abs(Mth.wrapDegrees(actual - expected)) <= ANGLE_TOLERANCE,
            label + ": expected " + expected + " +/- " + ANGLE_TOLERANCE + " degrees, got " + actual);
    }

    private static void assertDirection(Vec3 expected, Vec3 actual, String label) {
        double cosine = expected.normalize().dot(actual.normalize());
        double angle = Math.toDegrees(Math.acos(Math.clamp(cosine, -1.0D, 1.0D)));
        assertTrue(Double.isFinite(angle) && actual.lengthSqr() > 0.99D && angle <= ANGLE_TOLERANCE,
            label + ": forward direction changed by " + angle + " degrees (tolerance " + ANGLE_TOLERANCE + ")");
    }

    private static void hidden(Minecraft minecraft) {
        assertTrue(Boolean.parseBoolean(System.getProperty("automator.hidden", "true"))
            && NativeTestWindow.hiddenAndUnfocused(minecraft) && NativeTestWindow.mouseReleased(minecraft),
            "Vertical camera loop requires a hidden, unfocused native renderer with the mouse released");
    }

    private static void persist(Minecraft minecraft, Case scenario, List<VerticalCameraLoopTap.Pose> ticks) {
        List<VerticalCameraLoopTap.Evidence> crossings = new ArrayList<>();
        for (VerticalCameraLoopTap.Crossing crossing : VerticalCameraLoopTap.crossings()) {
            crossings.add(crossing.evidence());
        }
        Evidence evidence = new Evidence(scenario.label(), ANGLE_TOLERANCE, CROSSINGS, scenario.camera().name(), scenario.view(), scenario.gravityFlip(), ticks,
            crossings, VerticalCameraLoopTap.frames(), TravelTap.frames());
        Path path = minecraft.gameDirectory.toPath().resolve("screenshots/vertical-camera-loop/" + scenario.label() + ".json");
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(evidence));
        } catch (IOException failure) {
            throw new AssertionError("Could not save vertical camera acceptance evidence", failure);
        }
    }

    private record View(String name, float yaw, float pitch) {
    }

    private record Case(String label, CameraType camera, View view, boolean gravityFlip) {
    }

    private record Evidence(String label, float angleTolerance, int requiredCrossings, String camera, View view, boolean gravityFlip,
                            List<VerticalCameraLoopTap.Pose> ticks, List<VerticalCameraLoopTap.Evidence> crossings,
                            List<VerticalCameraLoopTap.Rendered> renderedPoses, List<TravelTap.Frame> renderedFrames) {
    }
}
