package art.arcane.wormholes.clientgametest;

import art.arcane.optics.crossing.GravityFrame;
import art.arcane.optics.crossing.ScaleRule;
import art.arcane.optics.frame.AxisPermutation;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.math.Face;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.MinecraftScaleAccess;
import art.arcane.wormholes.modded.MinecraftTravelCosts;
import art.arcane.wormholes.modded.MinecraftTraversalCompletion;
import art.arcane.wormholes.modded.MinecraftTraversalContext;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.client.GravityRenderState;
import art.arcane.wormholes.modded.client.GravityRenderTransform;
import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.modded.gravity.MinecraftGravity;
import art.arcane.wormholes.modded.mixin.SeamlessListenerAccess;
import art.arcane.wormholes.modded.seamless.StraddleTracker;
import art.arcane.wormholes.network.client.TravelMessage;
import art.arcane.wormholes.portal.AmbientParticleStyle;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.transit.OrientationPolicy;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

import static art.arcane.wormholes.clientgametest.NativeClientViewAssertions.runtime;
import static art.arcane.wormholes.clientgametest.SeamlessScenario.assertTrue;

public final class GravityPortalClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("WormholesClientGameTest");
    private static final BlockPos SOURCE = new BlockPos(400, 100, 400);
    private static final BlockPos DESTINATION = new BlockPos(440, 100, 400);
    private static final Vec3 APPROACH = new Vec3(402.0D, 100.0D, 404.5D);
    private static final int PREPARE_TICKS = 600;
    private static final int CROSSING_TICKS = 160;
    private static final float YAW = 180.0F;

    private GravityPortalClientGameTest() {
    }

    static void run(SeamlessClient client, Server server, Consumer<Boolean> jumping, Consumer<String> screenshot) {
        SeamlessScenario.join(client);
        SeamlessScenario.assertSeamlessNegotiated(client);
        Scene scene = server.compute(nativeServer -> build(nativeServer, server.player(), new SceneSettings(2, ScaleRule.ratio(0.25D, 4.0D))));
        try {
            client.runOnClient(minecraft -> {
                hidden(minecraft);
                minecraft.options.setCameraType(CameraType.FIRST_PERSON);
                minecraft.options.bobView().set(false);
                minecraft.options.fovEffectScale().set(0.0D);
                minecraft.options.framerateLimit().set(120);
            });
            prepare(client, server);
            client.lookAt(YAW, 0.0F);
            awaitArm(client, server, scene.source());
            GravityFrame expected = client.computeOnClient(minecraft -> {
                TravelMessage.TravelBegin arm = arm(scene.source());
                assertTrue(arm.rules().rotateGravity(), "Gravity portal did not arm quarter-turn gravity");
                assertTrue(Math.abs(arm.scale() - 0.5F) < 1.0E-6F, "Portal ratio did not arm 50% traveller scale");
                return GravityFrame.WORLD.transformed(arm.sourceToDestination().rigid().permutation());
            });
            assertTrue(expected.down() == Face.W, "Quarter-turn fixture did not make the west wall the floor");
            client.waitForChunksRender();
            client.runOnClient(minecraft -> TravelTap.reset());
            cross(client, expected, 0.5D);
            awaitArrival(client, server, expected, 0.5D);
            Snapshot authoritative = server.compute(nativeServer -> snapshot(server.player()));
            assertTrue(expected.equals(authoritative.gravity()) && authoritative.grounded(), "Server did not accept grounded wall locomotion: " + authoritative);
            assertTrue(Math.abs(authoritative.scale() - 0.5D) < 1.0E-6D, "Server traveller scale was not 50%: " + authoritative);
            assertTrue(Math.abs(Mth.wrapDegrees(authoritative.yaw() - YAW)) < 0.5F, "Server did not preserve local yaw");
            server.run(nativeServer -> scene.traversal().verify());
            client.waitFor(minecraft -> minecraft.level.getEntity(scene.observer()) instanceof ArmorStand
                && expected.equals(MinecraftGravity.frame(minecraft.level.getEntity(scene.observer()))), PREPARE_TICKS);
            client.runOnClient(minecraft -> validatePose(minecraft, expected, scene.observer(), 0.5D));
            moveOnWall(client, expected);
            jumpFromWall(client, expected, jumping);
            overlayRegression(client, server);
            renderViews(client, expected, screenshot);
            client.runOnClient(minecraft -> {
                assertTrue(TravelTap.respawns() == 0 && TravelTap.positions() == 0 && TravelTap.accepts() == 0
                    && !TravelTap.loadingScreenShown() && !TravelTap.clientUnloaded(), "Gravity crossing required a vanilla teleport correction: " + TravelTap.events());
                assertTrue(!TravelTap.frames().isEmpty(), "Gravity acceptance did not advance native render frames");
            });
            LOGGER.info("WORMHOLES_GRAVITY_PORTAL_PASS: actual quarter-turn portal crossing, 50% scale, server/client gravity, wall movement, jump, eyes, player/observer models and first/third-person rendering");
        } finally {
            jumping.accept(false);
            client.releaseForward();
            client.restoreDefaultGameOptions();
            server.run(nativeServer -> cleanup(nativeServer, server.player(), scene));
        }
    }

    static void demo(SeamlessClient client, Server server, Consumer<FlightControls> input, CameraType camera, String id) {
        SeamlessScenario.join(client);
        SeamlessScenario.assertSeamlessNegotiated(client);
        Scene scene = server.compute(nativeServer -> build(nativeServer, server.player(), new SceneSettings(4, ScaleRule.OFF)));
        NativeDemoCapture capture = new NativeDemoCapture(client);
        boolean hiddenHud = client.computeOnClient(minecraft -> minecraft.gui.hud.isHidden());
        boolean recording = false;
        try {
            client.runOnClient(minecraft -> {
                hidden(minecraft);
                minecraft.options.setCameraType(camera);
                minecraft.options.bobView().set(false);
                minecraft.options.fovEffectScale().set(0.0D);
                minecraft.options.fov().set(70);
                if (!minecraft.gui.hud.isHidden()) {
                    minecraft.gui.hud.toggle();
                }
            });
            prepare(client, server);
            client.lookAt(YAW, 0.0F);
            awaitArm(client, server, scene.source());
            GravityFrame expected = client.computeOnClient(minecraft -> {
                TravelMessage.TravelBegin armed = arm(scene.source());
                assertTrue(armed.rules().rotateGravity() && Math.abs(armed.scale() - 1.0F) < 1.0E-6F,
                    "Equal-size gravity demo did not arm unscaled directional travel");
                return GravityFrame.WORLD.transformed(armed.sourceToDestination().rigid().permutation());
            });
            assertTrue(expected.down() == Face.W, "Gravity demo did not make the west wall the floor");
            client.waitForChunksRender();
            client.runOnClient(minecraft -> TravelTap.reset());
            capture.begin(id);
            recording = true;
            client.waitTicks(40);
            cross(client, expected, 1.0D);
            awaitArrival(client, server, expected, 1.0D);
            server.run(nativeServer -> scene.traversal().verify());
            client.waitFor(minecraft -> minecraft.level.getEntity(scene.observer()) instanceof ArmorStand
                && expected.equals(MinecraftGravity.frame(minecraft.level.getEntity(scene.observer()))), PREPARE_TICKS);
            client.runOnClient(minecraft -> validatePose(minecraft, expected, scene.observer(), 1.0D));
            client.waitTicks(40);
            moveOnWall(client, expected);
            clearGroundedOverlay(client, "demo wall walk");
            jumpFromWall(client, expected, pressed -> input.accept(new FlightControls(pressed, false)));
            assertTrue(client.computeOnClient(minecraft -> !minecraft.player.getAbilities().flying),
                "A single gravity-relative jump enabled creative flight");
            capture.turn(150.0F, 0.0F, 40);
            flyOnWall(client, server, expected, input);
            client.waitTicks(40);
            clearGroundedOverlay(client, "demo final ground");
            Snapshot authoritative = server.compute(nativeServer -> snapshot(server.player()));
            assertTrue(expected.equals(authoritative.gravity()) && authoritative.grounded()
                && Math.abs(authoritative.scale() - 1.0D) < 1.0E-6D, "Gravity demo server state diverged: " + authoritative);
            client.runOnClient(minecraft -> {
                hidden(minecraft);
                assertTrue(minecraft.options.getCameraType() == camera && minecraft.gui.hud.isHidden(),
                    "Gravity demo changed its camera or showed the HUD");
                assertTrue(TravelTap.respawns() == 0 && TravelTap.positions() == 0 && TravelTap.accepts() == 0
                    && !TravelTap.loadingScreenShown() && !TravelTap.clientUnloaded(),
                    "Gravity demo received a vanilla teleport correction: " + TravelTap.events());
                assertTrue(!TravelTap.frames().isEmpty(), "Gravity demo did not advance native render frames");
                validateCamera(minecraft, expected, camera);
            });
            LOGGER.info("WORMHOLES_NATIVE_DEMO_PASS: {} unscaled quarter-turn crossing, wall walk, normal jump, creative ascent/descent and hover", id);
        } finally {
            try {
                if (recording) {
                    capture.end(id);
                }
            } finally {
                input.accept(new FlightControls(false, false));
                client.releaseForward();
                client.runOnClient(minecraft -> {
                    if (minecraft.gui.hud.isHidden() != hiddenHud) {
                        minecraft.gui.hud.toggle();
                    }
                });
                client.restoreDefaultGameOptions();
                server.run(nativeServer -> cleanup(nativeServer, server.player(), scene));
            }
        }
    }

    private static void prepare(SeamlessClient client, Server server) {
        awaitPlacement(client, server, true, "hover placement");
        client.waitForChunksDownload();
        client.waitForChunksRender();
        BlockPos support = BlockPos.containing(APPROACH).below();
        for (int tick = 0; tick < PREPARE_TICKS; tick++) {
            if (client.computeOnClient(minecraft -> minecraft.level.hasChunkAt(support)
                && minecraft.level.getBlockState(support).is(Blocks.STONE))) {
                server.run(nativeServer -> {
                    ServerPlayer player = server.player();
                    player.getAbilities().flying = false;
                    player.setDeltaMovement(Vec3.ZERO);
                    player.onUpdateAbilities();
                });
                awaitPlacement(client, server, false, "grounded placement");
                return;
            }
            if (tick % 50 == 0) {
                preparationEvidence(client, server, "terrain", tick);
            }
            client.waitTicks(1);
        }
        throw preparationFailure(client, server, "source support terrain did not arrive");
    }

    private static void awaitArm(SeamlessClient client, Server server, UUID source) {
        for (int tick = 0; tick < PREPARE_TICKS; tick++) {
            if (client.computeOnClient(minecraft -> armed(source))) {
                return;
            }
            if (tick % 50 == 0) {
                String state = client.computeOnClient(minecraft -> "arms=" + WormholesClient.instance().seamlessTravel().arms().size()
                    + ", unprepared=" + WormholesClient.instance().seamlessTravel().unprepared());
                boolean supported = server.compute(nativeServer -> runtime(nativeServer).clientViews().supportsGravity(server.player()));
                LOGGER.info("Gravity portal arming tick {}: {}; native gravity supported {}", tick, state, supported);
            }
            client.waitTicks(1);
        }
        throw preparationFailure(client, server, "gravity portal did not arm");
    }

    private static void awaitArrival(SeamlessClient client, Server server, GravityFrame gravity, double scale) {
        for (int tick = 0; tick < PREPARE_TICKS; tick++) {
            boolean confirmed = client.computeOnClient(minecraft -> !WormholesClient.instance().seamlessTravel().pending());
            Snapshot local = client.computeOnClient(minecraft -> snapshot(minecraft.player));
            Snapshot authoritative = server.compute(nativeServer -> snapshot(server.player()));
            if (confirmed && gravity.equals(local.gravity()) && gravity.equals(authoritative.gravity())
                && local.grounded() && authoritative.grounded() && Math.abs(authoritative.scale() - scale) < 1.0E-6D && Math.abs(local.scale() - scale) < 1.0E-6D) {
                LOGGER.info("Gravity portal arrival grounded: client {}; server {}", local, authoritative);
                return;
            }
            if (tick % 50 == 0) {
                LOGGER.info("Gravity portal arrival tick {}, confirmed {}: client {}; server {}", tick, confirmed, local, authoritative);
            }
            client.waitTicks(1);
        }
        throw preparationFailure(client, server, "gravity portal did not reach confirmed client/server ground");
    }

    private static void awaitPlacement(SeamlessClient client, Server server, boolean flying, String stage) {
        for (int tick = 0; tick < PREPARE_TICKS; tick++) {
            boolean placed = client.computeOnClient(minecraft -> minecraft.player != null
                && minecraft.player.position().distanceTo(APPROACH) < 0.05D
                && minecraft.player.getAbilities().flying == flying
                && (flying || minecraft.player.onGround()));
            if (placed) {
                preparationEvidence(client, server, stage + " ready", tick);
                return;
            }
            if (tick % 50 == 0) {
                preparationEvidence(client, server, stage, tick);
            }
            client.waitTicks(1);
        }
        throw preparationFailure(client, server, stage + " did not settle at " + APPROACH);
    }

    private static void preparationEvidence(SeamlessClient client, Server server, String stage, int ticks) {
        Preparation local = client.computeOnClient(GravityPortalClientGameTest::clientPreparation);
        Preparation authoritative = server.compute(nativeServer -> serverPreparation(server.player()));
        LOGGER.info("Gravity portal preparation {} tick {}: client {}; server {}", stage, ticks, local, authoritative);
    }

    private static AssertionError preparationFailure(SeamlessClient client, Server server, String stage) {
        Preparation local = client.computeOnClient(GravityPortalClientGameTest::clientPreparation);
        Preparation authoritative = server.compute(nativeServer -> serverPreparation(server.player()));
        return new AssertionError("Gravity portal preparation: " + stage + "; client=" + local + "; server=" + authoritative);
    }

    private static Preparation clientPreparation(Minecraft minecraft) {
        hidden(minecraft);
        BlockPos support = BlockPos.containing(APPROACH).below();
        return new Preparation(snapshot(minecraft.player), minecraft.player.getAbilities().flying, minecraft.player.getAbilities().mayfly,
            minecraft.level.hasChunkAt(support), minecraft.level.getBlockState(support).toString(), null,
            minecraft.gui.screen() == null ? "none" : minecraft.gui.screen().getClass().getSimpleName(), minecraft.player.tickCount);
    }

    private static Preparation serverPreparation(ServerPlayer player) {
        BlockPos support = BlockPos.containing(APPROACH).below();
        return new Preparation(snapshot(player), player.getAbilities().flying, player.getAbilities().mayfly,
            player.level().hasChunkAt(support), player.level().getBlockState(support).toString(),
            ((SeamlessListenerAccess) player.connection).wormholesAwaitingPosition(), "server", player.tickCount);
    }

    private static void cross(SeamlessClient client, GravityFrame expected, double scale) {
        client.holdForward();
        try (TickStepper stepper = client.lockstep()) {
            boolean crossed = false;
            for (int tick = 0; tick < CROSSING_TICKS; tick++) {
                Snapshot state = stepper.step(minecraft -> {
                    hidden(minecraft);
                    return snapshot(minecraft.player);
                });
                if (expected.equals(state.gravity())) {
                    crossed = true;
                    assertTrue(state.position().x > DESTINATION.getX() - 1.0D, "Native quarter-turn crossing missed its destination");
                    assertTrue(Math.abs(state.scale() - scale) < 1.0E-6D, "Scale was not predicted on the native crossing tick");
                    assertTrue(Math.abs(Mth.wrapDegrees(state.yaw() - YAW)) < 0.5F, "Native crossing changed gravity-local yaw");
                    break;
                }
            }
            assertTrue(crossed, "Timed out walking through the quarter-turn gravity portal");
        } finally {
            client.releaseForward();
        }
    }

    private static void moveOnWall(SeamlessClient client, GravityFrame gravity) {
        Vec3 before = client.computeOnClient(minecraft -> minecraft.player.position());
        client.holdForwardFor(35);
        client.waitTicks(4);
        Vec3 after = client.computeOnClient(minecraft -> minecraft.player.position());
        Vec3 movement = after.subtract(before);
        Vec3 local = MinecraftGravity.local(gravity, movement);
        assertTrue(Math.abs(local.y) < 0.05D && local.horizontalDistanceSqr() > 0.01D,
            "Forward input did not move along the new locomotion plane: " + movement + ", local " + local);
    }

    private static void jumpFromWall(SeamlessClient client, GravityFrame gravity, Consumer<Boolean> jumping) {
        client.waitFor(minecraft -> minecraft.player.onGround(), PREPARE_TICKS);
        Vec3 before = client.computeOnClient(minecraft -> minecraft.player.position());
        jumping.accept(true);
        try (TickStepper stepper = client.lockstep()) {
            boolean jumped = false;
            for (int tick = 0; tick < 12; tick++) {
                Snapshot state = stepper.step(minecraft -> snapshot(minecraft.player));
                Vec3 velocity = MinecraftGravity.local(gravity, state.velocity());
                Vec3 displacement = MinecraftGravity.local(gravity, state.position().subtract(before));
                if (velocity.y > 0.05D && displacement.y > 0.01D && !state.grounded()) {
                    jumped = true;
                    break;
                }
            }
            assertTrue(jumped, "Jump input did not lift the player away from the new wall floor");
        } finally {
            jumping.accept(false);
        }
        client.waitFor(minecraft -> minecraft.player.onGround(), PREPARE_TICKS);
    }

    private static void flyOnWall(SeamlessClient client, Server server, GravityFrame gravity, Consumer<FlightControls> input) {
        FlightControls released = new FlightControls(false, false);
        FlightControls ascend = new FlightControls(true, false);
        FlightControls descend = new FlightControls(false, true);
        try (TickStepper stepper = client.lockstep()) {
            flightPhase(stepper, gravity, input, released, 8);
            flightPhase(stepper, gravity, input, ascend, 1);
            flightPhase(stepper, gravity, input, released, 2);
            flightPhase(stepper, gravity, input, ascend, 1);
            FlightState takeoff = flightPhase(stepper, gravity, input, released, 1);
            assertTrue(takeoff.flying(), "Double jump did not enable rotated creative flight");
            FlightState rising = flightPhase(stepper, gravity, input, ascend, 16);
            Vec3 ascent = MinecraftGravity.local(gravity, rising.position().subtract(takeoff.position()));
            assertTrue(rising.flying() && ascent.y > 1.0D && ascent.horizontalDistanceSqr() < 1.0E-4D
                && MinecraftGravity.local(gravity, rising.velocity()).y > 0.1D,
                "Creative ascent did not follow gravity-local up: " + ascent + ", " + rising);
            verifyFlight(server, gravity, rising);
            flightPhase(stepper, gravity, input, released, 16);
            FlightState hovering = flightPhase(stepper, gravity, input, released, 1);
            FlightState hoverEnd = flightPhase(stepper, gravity, input, released, 8);
            assertTrue(hoverEnd.flying() && !hoverEnd.grounded() && hoverEnd.position().distanceTo(hovering.position()) < 0.01D,
                "Released creative ascent did not hover: " + hovering + " -> " + hoverEnd);
            verifyFlight(server, gravity, hoverEnd);
            FlightState falling = flightPhase(stepper, gravity, input, descend, 8);
            Vec3 descent = MinecraftGravity.local(gravity, falling.position().subtract(hoverEnd.position()));
            assertTrue(falling.flying() && descent.y < -0.5D && descent.horizontalDistanceSqr() < 1.0E-4D
                && MinecraftGravity.local(gravity, falling.velocity()).y < -0.1D,
                "Creative descent did not follow gravity-local down: " + descent + ", " + falling);
            verifyFlight(server, gravity, falling);
            flightPhase(stepper, gravity, input, released, 16);
            hovering = flightPhase(stepper, gravity, input, released, 1);
            hoverEnd = flightPhase(stepper, gravity, input, released, 8);
            assertTrue(hoverEnd.flying() && !hoverEnd.grounded() && hoverEnd.position().distanceTo(hovering.position()) < 0.01D,
                "Released creative descent did not hover: " + hovering + " -> " + hoverEnd);
            verifyFlight(server, gravity, hoverEnd);
            flightPhase(stepper, gravity, input, ascend, 1);
            flightPhase(stepper, gravity, input, released, 2);
            flightPhase(stepper, gravity, input, ascend, 1);
            FlightState landed = flightPhase(stepper, gravity, input, released, 40);
            assertTrue(!landed.flying() && landed.grounded(), "Creative flight did not switch off and land on the rotated floor: " + landed);
            verifyFlight(server, gravity, landed);
        } finally {
            input.accept(released);
        }
    }

    private static FlightState flightPhase(TickStepper stepper, GravityFrame gravity, Consumer<FlightControls> input,
                                           FlightControls controls, int ticks) {
        input.accept(controls);
        FlightState result = null;
        try {
            for (int tick = 0; tick < ticks; tick++) {
                result = stepper.step(minecraft -> {
                    hidden(minecraft);
                    assertTrue(gravity.equals(MinecraftGravity.frame(minecraft.player)), "Gravity changed during creative flight");
                    assertTrue(Math.abs(minecraft.player.getScale() - 1.0F) < 1.0E-6F, "Creative gravity demo changed traveller scale");
                    return flightState(minecraft.player);
                });
            }
            return result;
        } finally {
            input.accept(new FlightControls(false, false));
        }
    }

    private static void verifyFlight(Server server, GravityFrame gravity, FlightState local) {
        FlightState authoritative = server.compute(nativeServer -> {
            nativeServer.packetProcessor().processQueuedPackets();
            assertTrue(gravity.equals(MinecraftGravity.frame(server.player())), "Server gravity changed during creative flight");
            return flightState(server.player());
        });
        assertTrue(local.flying() == authoritative.flying() && local.grounded() == authoritative.grounded()
            && local.position().distanceTo(authoritative.position()) < 0.15D,
            "Creative flight diverged between client and server: local=" + local + ", server=" + authoritative);
    }

    private static FlightState flightState(Player player) {
        return new FlightState(player.position(), player.getDeltaMovement(), player.getAbilities().flying, player.onGround());
    }

    private static void renderViews(SeamlessClient client, GravityFrame gravity, Consumer<String> screenshot) {
        for (CameraType type : List.of(CameraType.FIRST_PERSON, CameraType.THIRD_PERSON_BACK, CameraType.THIRD_PERSON_FRONT)) {
            client.runOnClient(minecraft -> minecraft.options.setCameraType(type));
            int frames = client.computeOnClient(minecraft -> TravelTap.frames().size());
            client.waitTicks(4);
            client.runOnClient(minecraft -> {
                hidden(minecraft);
                validateCamera(minecraft, gravity, type);
                assertTrue(TravelTap.frames().size() > frames, "Native GPU frames did not advance for " + type);
            });
            screenshot.accept("gravity-portal-" + type.name().toLowerCase(Locale.ROOT));
        }
    }

    private static void overlayRegression(SeamlessClient client, Server server) {
        CameraType original = client.computeOnClient(minecraft -> minecraft.options.getCameraType());
        try {
            for (CameraType type : List.of(CameraType.FIRST_PERSON, CameraType.THIRD_PERSON_BACK)) {
                client.runOnClient(minecraft -> minecraft.options.setCameraType(type));
                clearGroundedOverlay(client, "wall floor " + type);
            }
            client.runOnClient(minecraft -> minecraft.options.setCameraType(CameraType.FIRST_PERSON));
            clearGroundedOverlay(client, "before embedded-eye control");
            BlockPos center = client.computeOnClient(minecraft -> BlockPos.containing(minecraft.player.getEyePosition()));
            List<OverlayBlock> saved = server.compute(nativeServer -> saveOverlayBlocks(nativeServer.overworld(), center));
            try {
                server.run(nativeServer -> {
                    for (OverlayBlock block : saved) {
                        nativeServer.overworld().setBlockAndUpdate(block.position(), Blocks.STONE.defaultBlockState());
                    }
                });
                client.waitFor(minecraft -> overlayBlocks(minecraft, saved, true), 100);
                freshOverlayFrames(client);
                client.runOnClient(minecraft -> {
                    assertTrue(!minecraft.player.noPhysics && StraddleTracker.straddle(minecraft.player) == null,
                        "Embedded-eye control bypassed native physics or used straddle suppression");
                    assertTrue(NativeTestWindow.hasBlockOverlay(minecraft),
                        "A real opaque block enclosing the gravity-relative eye did not produce the native block overlay");
                });
            } finally {
                server.run(nativeServer -> {
                    for (OverlayBlock block : saved) {
                        nativeServer.overworld().setBlockAndUpdate(block.position(), block.state());
                    }
                });
                client.waitFor(minecraft -> overlayBlocks(minecraft, saved, false), 100);
                clearGroundedOverlay(client, "after embedded-eye control");
            }
            LOGGER.info("Gravity overlay control passed: first/rear third-person floor has no overlay, enclosed real eye has an overlay, restored floor clears it");
        } finally {
            client.runOnClient(minecraft -> minecraft.options.setCameraType(original));
        }
    }

    private static void clearGroundedOverlay(SeamlessClient client, String stage) {
        client.waitFor(minecraft -> minecraft.player.onGround() && StraddleTracker.straddle(minecraft.player) == null, 100);
        freshOverlayFrames(client);
        client.runOnClient(minecraft -> {
            hidden(minecraft);
            assertTrue(minecraft.player.onGround() && !minecraft.player.noPhysics && StraddleTracker.straddle(minecraft.player) == null,
                "Gravity floor overlay check did not test ordinary grounded physics outside the portal: " + stage);
            assertTrue(!NativeTestWindow.hasBlockOverlay(minecraft),
                "A clear gravity-relative eye produced a native block overlay at " + stage + ": feet=" + minecraft.player.position()
                    + ", eye=" + minecraft.player.getEyePosition() + ", camera=" + minecraft.gameRenderer.mainCamera().position());
        });
    }

    private static void freshOverlayFrames(SeamlessClient client) {
        int frames = client.computeOnClient(minecraft -> TravelTap.frameIndex());
        client.waitFor(minecraft -> TravelTap.frameIndex() >= frames + 2, 100);
    }

    private static List<OverlayBlock> saveOverlayBlocks(ServerLevel level, BlockPos center) {
        List<OverlayBlock> saved = new ArrayList<>(27);
        for (BlockPos position : BlockPos.betweenClosed(center.offset(-1, -1, -1), center.offset(1, 1, 1))) {
            BlockPos stored = position.immutable();
            saved.add(new OverlayBlock(stored, level.getBlockState(stored)));
        }
        return saved;
    }

    private static boolean overlayBlocks(Minecraft minecraft, List<OverlayBlock> blocks, boolean stone) {
        for (OverlayBlock block : blocks) {
            BlockState current = minecraft.level.getBlockState(block.position());
            if (stone ? !current.is(Blocks.STONE) : !current.equals(block.state())) {
                return false;
            }
        }
        return true;
    }

    private static void validateCamera(Minecraft minecraft, GravityFrame gravity, CameraType type) {
        Camera camera = minecraft.gameRenderer.mainCamera();
        float partialTicks = camera.getCameraEntityPartialTicks(minecraft.getDeltaTracker());
        float yaw = minecraft.player.getViewYRot(partialTicks);
        float pitch = minecraft.player.getViewXRot(partialTicks);
        Quaternionf expected = GravityRenderTransform.camera(gravity, type.isMirrored() ? yaw + 180.0F : yaw,
            type.isMirrored() ? -pitch : pitch);
        vector(new Vector3f(0, 0, -1).rotate(expected), camera.forwardVector(), "Gravity camera forward in " + type);
        vector(new Vector3f(0, 1, 0).rotate(expected), camera.upVector(), "Gravity camera up in " + type);
        if (type.isFirstPerson()) {
            assertTrue(camera.position().distanceTo(minecraft.player.getEyePosition(partialTicks)) < 0.05D,
                "First-person camera did not follow gravity-relative eye position");
        } else {
            Vec3 eye = minecraft.player.getEyePosition(partialTicks);
            Vec3 offset = camera.position().subtract(eye);
            assertTrue(offset.length() > 0.1D, "Third-person gravity camera collapsed into the player");
            assertTrue(offset.normalize().dot(new Vec3(camera.forwardVector())) < -0.99D,
                "Third-person gravity camera did not retreat along its rotated viewing axis");
        }
    }

    private static void validatePose(Minecraft minecraft, GravityFrame gravity, int observerId, double scale) {
        assertTrue(Math.abs(minecraft.player.getAttributeValue(Attributes.SCALE) - scale) < 1.0E-6D, "Client scale did not remain " + scale);
        Vec3 eyeOffset = MinecraftGravity.local(gravity, minecraft.player.getEyePosition().subtract(minecraft.player.position()));
        assertTrue(Math.abs(eyeOffset.x) < 1.0E-6D && Math.abs(eyeOffset.z) < 1.0E-6D
            && Math.abs(eyeOffset.y - minecraft.player.getEyeHeight()) < 1.0E-5D, "Player eye offset did not follow the new up direction");
        Entity observer = minecraft.level.getEntity(observerId);
        assertTrue(observer instanceof ArmorStand && gravity.equals(MinecraftGravity.frame(observer)), "Observer entity gravity did not synchronize");
        for (Entity entity : List.of(minecraft.player, observer)) {
            EntityRenderState state = minecraft.getEntityRenderDispatcher().extractEntity(entity, 1.0F);
            assertTrue(state instanceof LivingEntityRenderState && gravity.equals(((GravityRenderState) state).wormholes$gravity()),
                "Living model extraction lost the gravity basis for " + entity.getType());
        }
    }

    private static boolean armed(UUID source) {
        return WormholesClient.instance().seamlessTravel().armed(source) && WormholesClient.instance().seamlessTravel().unprepared() == null;
    }

    private static TravelMessage.TravelBegin arm(UUID source) {
        for (TravelMessage.TravelBegin arm : WormholesClient.instance().seamlessTravel().arms()) {
            if (arm.sourcePortal().equals(source)) {
                return arm;
            }
        }
        throw new AssertionError("Missing prepared gravity portal arm");
    }

    private static Scene build(MinecraftServer server, ServerPlayer player, SceneSettings settings) {
        WormholesModRuntime runtime = runtime(server);
        ServerLevel level = server.overworld();
        fill(level, SOURCE.offset(-6, -1, -12), 16, 1, 26, Blocks.STONE.defaultBlockState());
        fill(level, DESTINATION.offset(-1, -6, -12), 1, 20, 26, Blocks.STONE.defaultBlockState());
        MinecraftPortal source = portal(runtime, player, level, SOURCE, 4);
        MinecraftPortal destination = portal(runtime, player, level, DESTINATION, settings.destinationEdge());
        destination.setFrame(Frame.fromNormalUp(Face.N, Face.E));
        source.setGravityRotationEnabled(true);
        source.setScaleRule(settings.scale());
        assertTrue(runtime.portals().link(player, source.getId(), destination.getId()), "Gravity fixture portal link rejected");
        GravityFrame gravity = GravityFrame.WORLD.transformed(AxisPermutation.between(source.getFrame(), destination.getFrame()));
        ArmorStand observer = new ArmorStand(level, DESTINATION.getX(), DESTINATION.getY() + 3.0D, DESTINATION.getZ() - 3.0D);
        observer.setNoGravity(true);
        MinecraftGravity.set(observer, gravity);
        assertTrue(level.addFreshEntity(observer), "Gravity observer spawn rejected");
        player.setGameMode(GameType.CREATIVE);
        player.getAbilities().flying = true;
        player.onUpdateAbilities();
        player.teleportTo(level, APPROACH.x, APPROACH.y, APPROACH.z, Set.of(), YAW, 0.0F, false);
        player.setDeltaMovement(Vec3.ZERO);
        TraversalProbe traversal = new TraversalProbe(source.getId());
        return new Scene(source.getId(), destination.getId(), observer.getId(), traversal, runtime.api().listenTraversal(traversal));
    }

    private static MinecraftPortal portal(WormholesModRuntime runtime, ServerPlayer actor, ServerLevel level, BlockPos min, int edge) {
        List<BlockPos> cells = new ArrayList<BlockPos>(edge * edge);
        for (int x = 0; x < edge; x++) {
            for (int y = 0; y < edge; y++) {
                cells.add(min.offset(x, y, 0));
            }
        }
        MinecraftPortal portal = runtime.portals().create(actor.getUUID(), level, cells, PortalType.PORTAL, new Vec3(0.0D, 0.0D, -1.0D));
        assertTrue(portal != null, "Gravity fixture portal creation rejected");
        portal.setAmbientStyle(AmbientParticleStyle.OFF);
        portal.setOrientation(OrientationPolicy.FRAME);
        return portal;
    }

    private static void cleanup(MinecraftServer server, ServerPlayer player, Scene scene) {
        try {
            scene.registration().close();
        } catch (Exception error) {
            throw new IllegalStateException("Could not close gravity traversal subscription", error);
        }
        MinecraftGravity.set(player, GravityFrame.WORLD);
        MinecraftScaleAccess.scaleAttribute().reset(player);
        player.teleportTo(player.level(), APPROACH.x, APPROACH.y, APPROACH.z, Set.of(), YAW, 0.0F, false);
        runtime(server).portals().remove(player, scene.source());
        runtime(server).portals().remove(player, scene.destination());
        Entity observer = server.overworld().getEntity(scene.observer());
        if (observer != null) {
            observer.discard();
        }
        fill(server.overworld(), SOURCE.offset(-6, -1, -12), 16, 1, 26, Blocks.AIR.defaultBlockState());
        fill(server.overworld(), DESTINATION.offset(-1, -6, -12), 1, 20, 26, Blocks.AIR.defaultBlockState());
    }

    private static Snapshot snapshot(LivingEntity entity) {
        return new Snapshot(MinecraftGravity.frame(entity), entity.position(), entity.getDeltaMovement(), entity.getYRot(), entity.getScale(), entity.onGround());
    }

    private static void fill(ServerLevel level, BlockPos min, int sizeX, int sizeY, int sizeZ, BlockState state) {
        for (int x = 0; x < sizeX; x++) {
            for (int y = 0; y < sizeY; y++) {
                for (int z = 0; z < sizeZ; z++) {
                    level.setBlockAndUpdate(min.offset(x, y, z), state);
                }
            }
        }
    }

    private static void hidden(Minecraft minecraft) {
        assertTrue(Boolean.parseBoolean(System.getProperty("automator.hidden", "true")) && NativeTestWindow.hiddenAndUnfocused(minecraft)
            && NativeTestWindow.mouseReleased(minecraft), "Gravity acceptance requires a hidden unfocused window with the mouse released");
    }

    private static void vector(Vector3fc expected, Vector3fc actual, String label) {
        assertTrue(expected.distance(actual) < 1.0E-4F, label + ": " + expected + " != " + actual);
    }

    interface Server {
        void run(Consumer<MinecraftServer> action);

        <T> T compute(Function<MinecraftServer, T> action);

        ServerPlayer player();
    }

    record FlightControls(boolean jump, boolean descend) {
    }

    private record FlightState(Vec3 position, Vec3 velocity, boolean flying, boolean grounded) {
    }

    private record OverlayBlock(BlockPos position, BlockState state) {
    }

    private record SceneSettings(int destinationEdge, ScaleRule scale) {
    }

    private record Scene(UUID source, UUID destination, int observer, TraversalProbe traversal, AutoCloseable registration) {
    }

    private static final class TraversalProbe implements MinecraftTravelCosts.Listener {
        private final UUID portal;
        private final List<MinecraftTraversalCompletion> completed = new ArrayList<>();
        private int admitted;

        private TraversalProbe(UUID portal) {
            this.portal = portal;
        }

        @Override
        public String before(MinecraftTraversalContext context) {
            if (portal.equals(context.portalId())) {
                admitted++;
            }
            return null;
        }

        @Override
        public void completed(MinecraftTraversalCompletion completion) {
            if (portal.equals(completion.context().portalId())) {
                completed.add(completion);
            }
        }

        private void verify() {
            assertTrue(admitted == 1 && completed.size() == 1
                && completed.getFirst().status() == MinecraftTraversalCompletion.Status.COMMITTED,
                "Native API did not report one admission and committed crossing: admitted=" + admitted + ", completions=" + completed);
        }
    }

    private record Snapshot(GravityFrame gravity, Vec3 position, Vec3 velocity, float yaw, double scale, boolean grounded) {
    }

    private record Preparation(Snapshot pose, boolean flying, boolean mayFly, boolean chunkLoaded, String support,
                               Vec3 awaitingTeleport, String screen, int entityTick) {
    }
}
