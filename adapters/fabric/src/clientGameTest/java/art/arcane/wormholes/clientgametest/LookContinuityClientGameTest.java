package art.arcane.wormholes.clientgametest;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.crossing.ArrivalOrientation;
import art.arcane.optics.crossing.LookTransfer;
import art.arcane.optics.crossing.OrientationRule;
import art.arcane.optics.crossing.PlaneCrossing;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.math.Angles;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.network.client.TravelMessage;
import art.arcane.wormholes.portal.AmbientParticleStyle;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.transit.OrientationPolicy;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static art.arcane.wormholes.clientgametest.NativeClientViewAssertions.runtime;

public final class LookContinuityClientGameTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("WormholesClientGameTest");
    private static final BlockPos POLE_SOURCE = new BlockPos(200, 100, 200);
    private static final BlockPos UPWARD = new BlockPos(220, 100, 200);
    private static final BlockPos WALL_SOURCE = new BlockPos(240, 100, 200);
    private static final BlockPos WALL = new BlockPos(260, 100, 203);
    private static final BlockPos TWIST_SOURCE = new BlockPos(280, 100, 200);
    private static final BlockPos TWIST_DESTINATION = new BlockPos(320, 100, 200);
    private static final float POLE_YAW = 30.0F;
    private static final float WALL_YAW = 0.0F;
    private static final double HOVER = 6.0D;
    private static final double APPROACH = 6.5D;
    private static final double CROSSING_JUMP = 4.0D;
    private static final float ANGLE_TOLERANCE = 0.5F;
    private static final double VECTOR_TOLERANCE = 1.0E-3D;
    private static final double EXIT_TOLERANCE = 0.02D;
    private static final float ROLL_SETTLED = 0.1F;
    private static final float ROLL_START_SHARE = 0.6F;
    private static final double ROLL_EASE_SECONDS = 0.35D;
    private static final long ROLL_DEADLINE_NANOS = 500_000_000L;
    private static final int NEGOTIATION_TIMEOUT_TICKS = 400;
    private static final int ARRIVE_TIMEOUT_TICKS = 200;
    private static final int PREPARE_TIMEOUT_TICKS = 600;
    private static final int CROSSING_TIMEOUT_TICKS = 160;
    private static final int SETTLE_TICKS = 30;

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientViewTestConfig.enableSeamless(true);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            run(context, singleplayer.getConnection(), singleplayer.getServer(), "singleplayer");
        }
        try (TestDedicatedServerContext server = context.worldBuilder().createServer(ClientViewTestConfig.serverProperties());
             TestDedicatedServerConnection connection = server.connect()) {
            run(context, connection, server, "dedicated");
        }
    }

    private void run(ClientGameTestContext context, TestServerConnection connection, TestServerContext server, String label) {
        connection.waitForChunksDownload();
        context.waitFor(client -> WormholesClient.instance() != null && WormholesClient.instance().session().active(), NEGOTIATION_TIMEOUT_TICKS);
        assertTrue(context.computeOnClient(client -> WormholesClient.instance().session().nativeSelected()),
            label + ": native portal travel was not negotiated");
        ServerPlayer player = server.computeOnServer(minecraftServer -> connection.getServerPlayer());
        Layout layout = server.computeOnServer(minecraftServer -> build(minecraftServer, player));
        List<String> failures = new ArrayList<>();
        try {
            pole(context, connection, server, player, layout, label + " straight down", failures);
            floorToWall(context, connection, server, player, layout, label + " floor to wall", failures);
            twist(context, connection, server, player, layout, label + " twisted pair", failures);
        } finally {
            context.runOnClient(client -> WormholesClient.instance().config().cameraRollEaseSeconds = ROLL_EASE_SECONDS);
            server.runOnServer(minecraftServer -> {
                for (UUID portal : layout.portals()) {
                    runtime(minecraftServer).portals().remove(player, portal);
                }
            });
        }
        assertTrue(failures.isEmpty(), label + ": " + failures);
    }

    private static void pole(ClientGameTestContext context, TestServerConnection connection, TestServerContext server, ServerPlayer player,
                             Layout layout, String label, List<String> failures) {
        Samples samples = fall(context, connection, server, player, layout.poleSource(), layout.poleToward(), POLE_SOURCE, POLE_YAW, label);
        List<TravelTap.Frame> frames = samples.frames();
        seamless(context, label, failures);
        int crossing = TravelTap.crossingFrame(CROSSING_JUMP);
        if (crossing < 1) {
            failures.add(label + ": never crossed");
            return;
        }
        TravelTap.Frame after = frames.get(crossing);
        route(samples, crossing, layout.poleToward(), label, failures);
        LOGGER.info("[{}] arrived at yaw {} pitch {}, look transfer {}", label, after.yaw(), after.pitch(), layout.poleLook());
        if (Math.abs(after.pitch() + 90.0F) > ANGLE_TOLERANCE) {
            failures.add(label + ": arrival pitch " + after.pitch() + " is not straight up");
        }
        if (Math.abs(Angles.unwrap(after.yaw() - layout.poleLook().yaw(), 0.0F)) > ANGLE_TOLERANCE) {
            failures.add(label + ": arrival yaw " + after.yaw() + " differs from the look transfer " + layout.poleLook().yaw());
        }
        continuity(frames, crossing, layout.poleToward(), true, label, failures);
    }

    private static void floorToWall(ClientGameTestContext context, TestServerConnection connection, TestServerContext server, ServerPlayer player,
                                    Layout layout, String label, List<String> failures) {
        Samples samples = fall(context, connection, server, player, layout.wallSource(), layout.wallToward(), WALL_SOURCE, WALL_YAW, label);
        List<TravelTap.Frame> frames = samples.frames();
        seamless(context, label, failures);
        int crossing = TravelTap.crossingFrame(CROSSING_JUMP);
        if (crossing < 1) {
            failures.add(label + ": never crossed");
            return;
        }
        TravelTap.Frame after = frames.get(crossing);
        route(samples, crossing, layout.wallToward(), label, failures);
        Vec3 exit = vector(Angles.direction(layout.wallLook().yaw(), layout.wallLook().pitch()));
        LOGGER.info("[{}] arrived at yaw {} pitch {} looking {}, exit {}, look transfer {}", label, after.yaw(), after.pitch(), after.forward(), exit,
            layout.wallLook());
        if (Math.abs(after.pitch()) > ANGLE_TOLERANCE) {
            failures.add(label + ": a straight-down look arrived at pitch " + after.pitch());
        }
        if (after.forward().subtract(exit).length() > EXIT_TOLERANCE) {
            failures.add(label + ": the arrival looks along " + after.forward() + " instead of the exit " + exit);
        }
        continuity(frames, crossing, layout.wallToward(), false, label, failures);
    }

    private static void twist(ClientGameTestContext context, TestServerConnection connection, TestServerContext server, ServerPlayer player,
                              Layout layout, String label, List<String> failures) {
        Vec3 start = new Vec3(TWIST_SOURCE.getX() + 1.5D, TWIST_SOURCE.getY() - 0.5D, TWIST_SOURCE.getZ() + APPROACH);
        place(context, server, player, start, 180.0F, 0.0F, false);
        context.runOnClient(client -> WormholesClient.instance().config().cameraRollEaseSeconds = ROLL_EASE_SECONDS);
        Samples easedSamples = walk(context, layout.twistSource(), layout.twistToward(), label + " eased");
        List<TravelTap.Frame> eased = easedSamples.frames();
        seamless(context, label + " eased", failures);
        int crossing = TravelTap.crossingFrame(CROSSING_JUMP);
        if (crossing < 1) {
            failures.add(label + " eased: never crossed");
            return;
        }
        route(easedSamples, crossing, layout.twistToward(), label + " eased", failures);
        eased(eased, crossing, Math.abs(layout.twistLook().roll()), label + " eased", failures);
        continuity(eased, crossing, layout.twistToward(), false, label + " eased", failures);
        context.getInput().holdKeyFor(options -> options.keyUp, 6);
        Vec3 feet = context.computeOnClient(client -> client.player.position());
        Vec3 portal = Vec3.atLowerCornerOf(TWIST_DESTINATION).add(1.5D, 1.5D, 0.5D);
        LOGGER.info("[{}] stepped away to {} after arriving at yaw {} pitch {}", label, feet, eased.get(crossing).yaw(), eased.get(crossing).pitch());
        context.getInput().lookAt((float) Math.toDegrees(Math.atan2(-(portal.x - feet.x), portal.z - feet.z)), 0.0F);
        context.waitTicks(SETTLE_TICKS);
        context.runOnClient(client -> WormholesClient.instance().config().cameraRollEaseSeconds = 0.0D);
        Samples instantSamples = walk(context, layout.twistDestination(), layout.twistToward().inverse(), label + " instant");
        List<TravelTap.Frame> instant = instantSamples.frames();
        seamless(context, label + " instant", failures);
        int back = TravelTap.crossingFrame(CROSSING_JUMP);
        if (back < 1) {
            failures.add(label + " instant: never crossed back");
            return;
        }
        route(instantSamples, back, layout.twistToward().inverse(), label + " instant", failures);
        TravelTap.Frame before = instant.get(back - 1);
        LookTransfer returnLook = LookTransfer.of(new Angles.Look(before.yaw(), before.pitch()), layout.twistToward().inverse().permutation());
        if (Math.abs(returnLook.roll()) < 45.0F) {
            failures.add(label + " instant: the return pair carries only " + returnLook.roll() + " degrees of roll");
        }
        continuity(instant, back, layout.twistToward().inverse(), false, label + " instant", failures);
        float worst = 0.0F;
        for (int index = back; index < instant.size(); index++) {
            worst = Math.max(worst, Math.abs(roll(instant.get(index))));
        }
        LOGGER.info("[{}] return crossing with the ease disabled: worst roll {} degrees over {} frames", label, worst, instant.size() - back);
        if (worst > ROLL_SETTLED) {
            failures.add(label + " instant: the camera rolled " + worst + " degrees with the ease disabled");
        }
    }

    private static Samples fall(ClientGameTestContext context, TestServerConnection connection, TestServerContext server,
                                ServerPlayer player, UUID source, OpticTransform toward, BlockPos min, float yaw, String label) {
        Vec3 hover = new Vec3(min.getX() + 1.5D, min.getY() + HOVER, min.getZ() + 1.5D);
        place(context, server, player, hover, yaw, 90.0F, true);
        connection.waitForChunksRender();
        PreparedRoute prepared = prepare(context, source, toward, label);
        context.getInput().lookAt(yaw, 90.0F);
        context.waitTicks(1);
        assertTrue(context.computeOnClient(client -> Math.abs(client.player.getXRot() - 90.0F) <= ANGLE_TOLERANCE
            && client.player.getAbilities().flying && client.player.position().distanceTo(hover) < 0.05D),
            label + ": straight-down departure pose changed before releasing flight");
        context.runOnClient(client -> TravelTap.reset());
        double eyeHeight = context.computeOnClient(client -> (double) client.player.getEyeHeight());
        context.runOnClient(client -> LookContinuityTravelTap.open());
        try {
            server.runOnServer(minecraftServer -> {
                player.getAbilities().flying = false;
                player.onUpdateAbilities();
            });
            waitForCrossing(context);
            context.waitTicks(SETTLE_TICKS);
            LOGGER.info("[{}] fall completed: {}", label, context.computeOnClient(client -> state(client, source)));
            return context.computeOnClient(client -> new Samples(TravelTap.frames(), prepared, eyeHeight, LookContinuityTravelTap.attempts(),
                client.level, client.player));
        } finally {
            context.runOnClient(client -> LookContinuityTravelTap.close());
        }
    }

    private static Samples walk(ClientGameTestContext context, UUID source, OpticTransform toward, String label) {
        PreparedRoute prepared = prepare(context, source, toward, label);
        context.runOnClient(client -> TravelTap.reset());
        double eyeHeight = context.computeOnClient(client -> (double) client.player.getEyeHeight());
        context.runOnClient(client -> LookContinuityTravelTap.open());
        try {
            context.getInput().holdKey(options -> options.keyUp);
            try {
                waitForCrossing(context);
            } finally {
                context.getInput().releaseKey(options -> options.keyUp);
            }
            context.waitTicks(SETTLE_TICKS);
            LOGGER.info("[{}] walk completed: {}", label, context.computeOnClient(client -> state(client, source)));
            return context.computeOnClient(client -> new Samples(TravelTap.frames(), prepared, eyeHeight, LookContinuityTravelTap.attempts(),
                client.level, client.player));
        } finally {
            context.runOnClient(client -> LookContinuityTravelTap.close());
        }
    }

    private static void waitForCrossing(ClientGameTestContext context) {
        for (int tick = 0; tick < CROSSING_TIMEOUT_TICKS && context.computeOnClient(client -> TravelTap.crossingFrame(CROSSING_JUMP)) < 0; tick++) {
            context.waitTicks(1);
        }
    }

    private static void validate(PreparedRoute prepared, OpticTransform toward, String label) {
        TravelMessage.TravelBegin arm = prepared.arm();
        OpticTransform actual = arm.sourceToDestination().rigid();
        assertTrue(actual.permutation().equals(toward.permutation())
            && actual.point(Vec3d.ZERO).subtract(toward.point(Vec3d.ZERO)).length() < 1.0E-6D && arm.scale() == 1.0F,
            label + ": armed route differs from the fixture transform: " + arm);
    }

    private static PreparedRoute prepare(ClientGameTestContext context, UUID source, OpticTransform toward, String label) {
        for (int tick = 0; tick < PREPARE_TIMEOUT_TICKS; tick++) {
            PreparedRoute prepared = context.computeOnClient(client -> prepared(client, source));
            if (prepared != null) {
                TravelMessage.TravelBegin arm = prepared.arm();
                OpticTransform actual = arm.sourceToDestination().rigid();
                validate(prepared, toward, label);
                LOGGER.info("[{}] prepared after {} ticks: token {} generation {}, resident {} handle {}, source level {} destination level {}, geometry {}, transform {}, {}", label, tick,
                    arm.token(), arm.generation(), arm.resident(), arm.levelHandle(), System.identityHashCode(prepared.source()),
                    System.identityHashCode(prepared.destination()), arm.sourceGeometry(), actual, context.computeOnClient(client -> state(client, source)));
                return prepared;
            }
            context.waitTicks(1);
        }
        throw new AssertionError(label + ": route not prepared within " + PREPARE_TIMEOUT_TICKS + " ticks: "
            + context.computeOnClient(client -> state(client, source)));
    }

    private static PreparedRoute prepared(Minecraft client, UUID source) {
        if (client.player == null || client.level == null || WormholesClient.instance().seamlessTravel().pending()) {
            return null;
        }
        for (TravelMessage.TravelBegin arm : WormholesClient.instance().seamlessTravel().arms()) {
            if (!arm.sourcePortal().equals(source) || !arm.sourceWorld().equals(client.level.dimension().identifier().toString())) {
                continue;
            }
            ClientLevel target = arm.resident() ? WormholesClient.instance().seamlessTravel().residents().level(arm.levelHandle()) : client.level;
            Vec3d arrival = arm.sourceToDestination().point(new Vec3d(client.player.getX(), client.player.getY(), client.player.getZ()));
            if (target != null && target.dimension().identifier().toString().equals(arm.world().dimension())
                && target.getChunkSource().getChunk(arrival.blockX() >> 4, arrival.blockZ() >> 4, ChunkStatus.FULL, false) != null) {
                return new PreparedRoute(arm, client.level, target, client.player);
            }
        }
        return null;
    }

    private static String state(Minecraft client, UUID source) {
        return "source " + source + ", feet " + client.player.position() + ", eye " + client.player.getEyePosition()
            + ", yaw " + client.player.getYRot() + ", pitch " + client.player.getXRot() + ", velocity " + client.player.getDeltaMovement()
            + ", flying " + client.player.getAbilities().flying + ", forward " + client.options.keyUp.isDown()
            + ", pending " + WormholesClient.instance().seamlessTravel().pending() + ", arms "
            + WormholesClient.instance().seamlessTravel().arms() + ", respawns " + TravelTap.respawns() + ", positions " + TravelTap.positions()
            + ", vanilla accepts " + TravelTap.accepts() + ", " + TravelTap.events();
    }

    private static void route(Samples samples, int crossing, OpticTransform toward, String label, List<String> failures) {
        TravelTap.Frame before = samples.frames().get(crossing - 1);
        TravelTap.Frame after = samples.frames().get(crossing);
        LookContinuityTravelTap.Attempt nativeCrossing = nativeCrossing(samples, label, failures);
        if (nativeCrossing == null) {
            return;
        }
        TravelMessage.TravelBegin arm = nativeCrossing.arm();
        PreparedRoute prepared = new PreparedRoute(arm, nativeCrossing.source(), nativeCrossing.destination(), nativeCrossing.player());
        validate(prepared, toward, label);
        if (!arm.sourcePortal().equals(samples.prepared().arm().sourcePortal()) || !nativeCrossing.ready()) {
            failures.add(label + ": native crossing used another source arm or an unprepared destination");
        }
        Vec3d start = new Vec3d(before.camera().x, before.camera().y, before.camera().z);
        Vec3d arrival = new Vec3d(after.camera().x, after.camera().y, after.camera().z);
        Vec3d eyeOffset = new Vec3d(0.0D, samples.eyeHeight(), 0.0D);
        Vec3d end = toward.inverse().point(arrival.subtract(eyeOffset)).add(eyeOffset);
        Vec3d origin = arm.sourceGeometry().apertureArea().center();
        Vec3d plane = planePoint(arm.sourceGeometry());
        Vec3d hit = PlaneCrossing.intersection(arm.sourceGeometry().frame(), plane, start, end);
        double side = arm.sourceGeometry().signedDistance(start.x(), start.y(), start.z());
        LOGGER.info("[{}] route token {} generation {}, source {} front {}, side {}, source eye {} -> {}, eye height {}, aperture hit {}, before {}, after {}, packets {}", label,
            arm.token(), arm.generation(), arm.sourcePortal(), arm.sourceGeometry().frontSide(), side, start, end, samples.eyeHeight(), hit, before, after,
            TravelTap.events());
        if ((side > 0.0D) != arm.sourceGeometry().frontSide()) {
            failures.add(label + ": sampled departure is on the wrong side of the armed source");
        }
        if (hit == null || !arm.sourceGeometry().containsPoint(hit.x(), hit.y(), hit.z())) {
            failures.add(label + ": camera jump did not cross the intended aperture; hit " + hit);
        }
        nativeMotion(nativeCrossing, start, end, plane, label, failures);
        if (start.subtract(origin).length() > 8.0D || arrival.subtract(toward.point(origin)).length() > 8.0D) {
            failures.add(label + ": camera jump was outside the intended source or destination");
        }
        if (!before.dimension().equals(arm.sourceWorld()) || !after.dimension().equals(arm.world().dimension()) || before.player() != after.player()) {
            failures.add(label + ": crossing replaced the player or used the wrong source or destination dimension");
        }
        LOGGER.info("[{}] initial token {} generation {}, native token {} generation {}, resident {} handle {}, expected levels {} -> {}, observed frames {} -> {}, native arrival level {}, settled level {}", label,
            samples.prepared().arm().token(), samples.prepared().arm().generation(), arm.token(), arm.generation(), arm.resident(), arm.levelHandle(),
            System.identityHashCode(prepared.source()), System.identityHashCode(prepared.destination()), before.level(), after.level(),
            System.identityHashCode(nativeCrossing.arrivedLevel()), System.identityHashCode(samples.settledLevel()));
        if (!levelsMatch(samples, before, after, nativeCrossing)) {
            failures.add(label + ": crossing did not use the exact source and destination level objects bound to the prepared arm");
        }
        for (int index = 1; index < samples.frames().size(); index++) {
            if (index != crossing && samples.frames().get(index).camera().distanceTo(samples.frames().get(index - 1).camera()) > CROSSING_JUMP) {
                failures.add(label + ": an extra camera jump occurred at frame " + index);
            }
        }
    }

    private static Vec3d planePoint(ApertureDescriptor geometry) {
        Vec3d center = geometry.apertureArea().center();
        double plane = geometry.planeCoordinate();
        return switch (geometry.facingDirection().getAxis()) {
            case X -> new Vec3d(plane, center.y(), center.z());
            case Y -> new Vec3d(center.x(), plane, center.z());
            case Z -> new Vec3d(center.x(), center.y(), plane);
        };
    }

    private static void nativeMotion(LookContinuityTravelTap.Attempt crossing, Vec3d cameraStart, Vec3d cameraEnd, Vec3d origin,
                                     String label, List<String> failures) {
        LookContinuityTravelTap.Motion motion = crossing.motion();
        ApertureDescriptor geometry = crossing.arm().sourceGeometry();
        Vec3d start = new Vec3d(motion.previous().x, motion.previous().y, motion.previous().z);
        Vec3d end = new Vec3d(motion.eye().x, motion.eye().y, motion.eye().z);
        Vec3d hit = PlaneCrossing.intersection(geometry.frame(), origin, start, end);
        double direction = geometry.frontSide() ? 1.0D : -1.0D;
        double before = geometry.signedDistance(start.x(), start.y(), start.z()) * direction;
        double after = geometry.signedDistance(end.x(), end.y(), end.z()) * direction;
        LOGGER.info("[{}] native aperture sample: partial {}, previous {}, eye {}, feet {}, sampled eye {}, plane {}, hit {}, camera deltas {} -> {}",
            label, motion.partial(), start, end, motion.feet(), motion.sampledEye(), origin, hit,
            cameraStart.subtract(start), cameraEnd.subtract(end));
        if (before <= 0.0D || after > 0.0D || hit == null || !geometry.containsPoint(hit.x(), hit.y(), hit.z())) {
            failures.add(label + ": native motion did not cross the intended aperture; hit " + hit);
        }
        if (!motion.eye().equals(motion.sampledEye())) {
            failures.add(label + ": native eye differs from the original player sample at partial " + motion.partial());
        }
    }

    private static LookContinuityTravelTap.Attempt nativeCrossing(Samples samples, String label, List<String> failures) {
        LookContinuityTravelTap.Attempt crossing = null;
        int successful = 0;
        for (LookContinuityTravelTap.Attempt attempt : samples.attempts()) {
            if (attempt.successful()) {
                crossing = attempt;
                successful++;
            }
        }
        LOGGER.info("[{}] native crossing attempts {}, successful {}", label, samples.attempts().size(), successful);
        if (successful != 1) {
            failures.add(label + ": expected one successful native crossing, observed " + successful);
            return null;
        }
        return crossing;
    }

    private static boolean levelsMatch(Samples samples, TravelTap.Frame before, TravelTap.Frame after, LookContinuityTravelTap.Attempt crossing) {
        PreparedRoute initial = samples.prepared();
        return crossing.source() == initial.source() && crossing.player() == initial.player()
            && before.level() == System.identityHashCode(initial.source()) && before.player() == System.identityHashCode(initial.player())
            && after.level() == System.identityHashCode(crossing.destination()) && crossing.arrivedLevel() == crossing.destination()
            && crossing.arrivedPlayer() == initial.player() && samples.settledLevel() == crossing.destination()
            && samples.settledPlayer() == initial.player();
    }

    private static void place(ClientGameTestContext context, TestServerContext server, ServerPlayer player, Vec3 position, float yaw, float pitch,
                              boolean flying) {
        server.runOnServer(minecraftServer -> {
            player.setGameMode(GameType.CREATIVE);
            player.getAbilities().mayfly = true;
            player.getAbilities().flying = flying;
            player.onUpdateAbilities();
            player.teleportTo(player.level(), position.x, position.y, position.z, Set.of(), yaw, pitch, false);
            player.setDeltaMovement(Vec3.ZERO);
        });
        context.waitFor(client -> client.player.position().distanceTo(position) < 0.05D, ARRIVE_TIMEOUT_TICKS);
        context.getInput().lookAt(yaw, pitch);
        context.waitTicks(SETTLE_TICKS);
    }

    private static void seamless(ClientGameTestContext context, String label, List<String> failures) {
        int corrections = context.computeOnClient(client -> TravelTap.positions() + TravelTap.respawns());
        if (corrections > 0) {
            failures.add(label + ": the crossing was corrected by " + corrections + " position or respawn packets; " + TravelTap.events());
        }
        if (context.computeOnClient(client -> TravelTap.accepts() > 0 || TravelTap.loadingScreenShown() || TravelTap.clientUnloaded()
            || WormholesClient.instance().seamlessTravel().pending())) {
            failures.add(label + ": native crossing did not settle without a vanilla teleport, loading screen, or unloaded client");
        }
    }

    private static void continuity(List<TravelTap.Frame> frames, int crossing, OpticTransform toward, boolean checkUp, String label,
                                   List<String> failures) {
        double worstForward = 0.0D;
        double worstUp = 0.0D;
        int worstFrame = -1;
        for (int index = 1; index < frames.size(); index++) {
            TravelTap.Frame before = frames.get(index - 1);
            TravelTap.Frame after = frames.get(index);
            Vec3 forward = index == crossing ? map(toward, before.forward()) : before.forward();
            Vec3 up = index == crossing ? map(toward, before.up()) : before.up();
            double forwardGap = angle(forward, after.forward());
            double upGap = checkUp ? angle(up, after.up()) : 0.0D;
            if (forwardGap > worstForward || upGap > worstUp) {
                worstFrame = index;
            }
            worstForward = Math.max(worstForward, forwardGap);
            worstUp = Math.max(worstUp, upGap);
        }
        LOGGER.info("[{}] camera continuity over {} frames: worst forward turn {} rad, worst up turn {} rad at frame {} (crossing {})", label,
            frames.size(), String.format("%.6f", worstForward), String.format("%.6f", worstUp), worstFrame, crossing);
        if (worstForward > VECTOR_TOLERANCE) {
            failures.add(label + ": the camera forward jumped " + worstForward + " rad at frame " + worstFrame + " (crossing " + crossing + ")");
        }
        if (worstUp > VECTOR_TOLERANCE) {
            failures.add(label + ": the camera up jumped " + worstUp + " rad at frame " + worstFrame + " (crossing " + crossing + ")");
        }
    }

    private static void eased(List<TravelTap.Frame> frames, int crossing, float expected, String label, List<String> failures) {
        float first = Math.abs(roll(frames.get(crossing)));
        float previous = first;
        long settled = -1L;
        int rises = 0;
        List<String> samples = new ArrayList<>();
        for (int index = crossing; index < frames.size(); index++) {
            float roll = Math.abs(roll(frames.get(index)));
            samples.add(String.format("%.2f", roll));
            if (roll > previous + 1.0E-3F) {
                rises++;
            }
            if (settled < 0L && roll < ROLL_SETTLED) {
                settled = frames.get(index).wallNanos() - frames.get(crossing).wallNanos();
            }
            previous = roll;
        }
        LOGGER.info("[{}] crossing roll {} degrees (look transfer roll {}), settled after {} ms, {} rises; {}", label, first, expected,
            settled < 0L ? "never" : String.format("%.1f", settled / 1.0E6D), rises, samples);
        if (expected < 45.0F) {
            failures.add(label + ": the twisted pair carries only " + expected + " degrees of roll");
        }
        if (first < expected * ROLL_START_SHARE) {
            failures.add(label + ": the crossing frame rolled only " + first + " of " + expected + " degrees");
        }
        if (rises > 0) {
            failures.add(label + ": the roll grew " + rises + " times while easing out");
        }
        if (settled < 0L || settled > ROLL_DEADLINE_NANOS) {
            failures.add(label + ": the roll did not settle below " + ROLL_SETTLED + " degrees within 0.5 s");
        }
    }

    private static Layout build(MinecraftServer server, ServerPlayer actor) {
        WormholesModRuntime runtime = runtime(server);
        ServerLevel level = server.overworld();
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set noon");
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState glass = Blocks.GLASS.defaultBlockState();
        BlockState slab = Blocks.SMOOTH_STONE_SLAB.defaultBlockState();
        fill(level, POLE_SOURCE.offset(-3, -5, -3), 9, 18, 9, air);
        fill(level, POLE_SOURCE.offset(-3, -5, -3), 9, 1, 9, glass);
        fill(level, UPWARD.offset(-3, -3, -3), 9, 16, 9, air);
        fill(level, UPWARD.offset(-3, -3, -3), 9, 1, 9, glass);
        fill(level, WALL_SOURCE.offset(-3, -5, -3), 9, 18, 9, air);
        fill(level, WALL_SOURCE.offset(-3, -5, -3), 9, 1, 9, glass);
        fill(level, WALL.offset(-6, -3, -13), 15, 14, 27, air);
        fill(level, WALL.offset(-6, -3, -13), 15, 1, 27, Blocks.STONE.defaultBlockState());
        for (BlockPos min : List.of(TWIST_SOURCE, TWIST_DESTINATION)) {
            fill(level, min.offset(-4, -2, -10), 11, 10, 21, air);
            fill(level, min.offset(-4, -2, -10), 11, 1, 21, Blocks.STONE.defaultBlockState());
            fill(level, min.offset(-4, -1, -10), 11, 1, 21, slab);
        }
        MinecraftPortal poleSource = portal(runtime, actor, level, floor(POLE_SOURCE), new Vec3(0, 1, 0));
        MinecraftPortal upward = portal(runtime, actor, level, floor(UPWARD), new Vec3(0, -1, 0));
        assertTrue(runtime.portals().update(actor, upward.getId(), portal -> portal.setFrame(Frame.canonical(Face.U))),
            "upward exit frame rejected");
        MinecraftPortal wallSource = portal(runtime, actor, level, floor(WALL_SOURCE), new Vec3(0, 1, 0));
        MinecraftPortal wall = portal(runtime, actor, level, NativeClientViewAssertions.cells(WALL), new Vec3(0, 0, 1));
        MinecraftPortal twistSource = portal(runtime, actor, level, NativeClientViewAssertions.cells(TWIST_SOURCE), new Vec3(0, 0, -1));
        MinecraftPortal twistDestination = portal(runtime, actor, level, NativeClientViewAssertions.cells(TWIST_DESTINATION), new Vec3(0, 0, -1));
        assertTrue(runtime.portals().update(actor, twistDestination.getId(), portal -> portal.setFrame(portal.getFrame().rotateClockwise())),
            "twisted frame rejected");
        assertTrue(runtime.portals().link(actor, poleSource.getId(), upward.getId()), "pole link rejected");
        assertTrue(runtime.portals().link(actor, wallSource.getId(), wall.getId()), "floor to wall link rejected");
        assertTrue(runtime.portals().link(actor, twistSource.getId(), twistDestination.getId()), "twisted link rejected");
        assertTrue(runtime.portals().link(actor, twistDestination.getId(), twistSource.getId()), "twisted return link rejected");
        Vec3d above = new Vec3d(0.0D, 1.0D, 0.0D);
        Vec3d approach = new Vec3d(0.0D, 0.0D, 1.0D);
        assertTrue(poleSource.getFrame().equals(Frame.canonical(Face.D)) && upward.getFrame().equals(Frame.canonical(Face.U)),
            "straight-down source and upward exit must have opposite vertical frames");
        assertTrue(wallSource.getFrame().equals(Frame.canonical(Face.D)) && wall.getFrame().equals(Frame.canonical(Face.S)),
            "the floor-to-wall fixture must pair a downward source with a south-facing wall");
        assertTrue(twistSource.getFrame().equals(Frame.canonical(Face.N))
            && twistDestination.getFrame().equals(Frame.canonical(Face.N).rotateClockwise()),
            "the twisted pair must retain its quarter-turned destination frame");
        assertTrue(!front(poleSource, above) && !front(wallSource, above) && !front(twistSource, approach),
            "fixture approaches must exercise the actual back-side views");
        assertTrue(toward(poleSource, upward, above).vector(above.multiply(-1.0D)).subtract(above).length() < 1.0E-6D,
            "the inverted pair must map falling motion and straight-down look upward");
        assertTrue(toward(wallSource, wall, above).vector(above.multiply(-1.0D)).subtract(new Vec3d(0, 0, 1)).length() < 1.0E-6D,
            "the floor-to-wall pair must map falling motion and straight-down look south");
        LookTransfer poleLook = transfer(poleSource, upward, above, POLE_YAW, 90.0F);
        LookTransfer twistLook = transfer(twistSource, twistDestination, approach, 180.0F, 0.0F);
        assertTrue(Math.abs(poleLook.pitch() + 90.0F) <= ANGLE_TOLERANCE && Math.abs(poleLook.yaw() + POLE_YAW) <= ANGLE_TOLERANCE
            && Math.abs(poleLook.roll()) <= ANGLE_TOLERANCE, "the inverted pair must produce yaw -30, pitch -90, roll zero: " + poleLook);
        assertTrue(Math.abs(Math.abs(twistLook.roll()) - 90.0F) <= ANGLE_TOLERANCE,
            "the quarter-turned pair must carry 90 degrees of roll: " + twistLook);
        LOGGER.info("Look fixture source {} frame {} origin {}, upward {} frame {} origin {}, wall {} frame {} origin {}, twist {} frame {} -> {} frame {}",
            poleSource.getId(), frame(poleSource.getFrame()), poleSource.getOrigin(), upward.getId(), frame(upward.getFrame()), upward.getOrigin(),
            wall.getId(), frame(wall.getFrame()), wall.getOrigin(), twistSource.getId(), frame(twistSource.getFrame()), twistDestination.getId(),
            frame(twistDestination.getFrame()));
        return new Layout(List.of(poleSource.getId(), upward.getId(), wallSource.getId(), wall.getId(), twistSource.getId(), twistDestination.getId()),
            poleSource.getId(), wallSource.getId(), twistSource.getId(), twistDestination.getId(),
            toward(poleSource, upward, above), toward(wallSource, wall, above), toward(twistSource, twistDestination, approach),
            poleLook, transfer(wallSource, wall, above, WALL_YAW, 90.0F), twistLook);
    }

    private static MinecraftPortal portal(WormholesModRuntime runtime, ServerPlayer actor, ServerLevel level, List<BlockPos> cells, Vec3 normal) {
        MinecraftPortal portal = runtime.portals().create(actor.getUUID(), level, cells, PortalType.PORTAL, normal);
        assertTrue(portal != null, "portal creation rejected at " + cells.getFirst());
        portal.setAmbientStyle(AmbientParticleStyle.OFF);
        portal.setOrientation(OrientationPolicy.FRAME);
        return portal;
    }

    private static OpticTransform toward(MinecraftPortal source, MinecraftPortal destination, Vec3d approach) {
        boolean front = front(source, approach);
        return OpticTransform.between(source.getFrame().view(front), source.getOrigin(), destination.getFrame().view(front), destination.getOrigin());
    }

    private static LookTransfer transfer(MinecraftPortal source, MinecraftPortal destination, Vec3d approach, float yaw, float pitch) {
        PlaneCrossing crossing = PlaneCrossing.create(source.getFrame(), source.getOrigin(), new PlaneCrossing.Motion(
            source.getOrigin().add(approach.multiply(0.2D)), source.getOrigin().add(approach.multiply(-0.2D)),
            approach.multiply(-0.4D), Angles.direction(yaw, pitch)));
        return ArrivalOrientation.transfer(crossing, LookTransfer.cameraUp(yaw, pitch), destination.getFrame(), OrientationRule.FRAME, false);
    }

    private static boolean front(MinecraftPortal portal, Vec3d approach) {
        return approach.x() * portal.getFrame().getNormal().x() + approach.y() * portal.getFrame().getNormal().y()
            + approach.z() * portal.getFrame().getNormal().z() > 0.0D;
    }

    private static float roll(TravelTap.Frame frame) {
        Vec3 canonical = vector(LookTransfer.cameraUp(frame.yaw(), frame.pitch()));
        Vec3 up = frame.up();
        double sine = canonical.cross(up).dot(frame.forward());
        double cosine = canonical.dot(up);
        return (float) Math.toDegrees(Math.atan2(sine, cosine));
    }

    private static String frame(Frame frame) {
        return frame.getNormal() + "/" + frame.getRight() + "/" + frame.getUp();
    }

    private static Vec3 map(OpticTransform transform, Vec3 vector) {
        return vector(transform.vector(new Vec3d(vector.x, vector.y, vector.z)));
    }

    private static Vec3 vector(Vec3d vector) {
        return new Vec3(vector.x(), vector.y(), vector.z());
    }

    private static double angle(Vec3 expected, Vec3 actual) {
        double cosine = expected.normalize().dot(actual.normalize());
        return Math.acos(Math.max(-1.0D, Math.min(1.0D, cosine)));
    }

    private static List<BlockPos> floor(BlockPos min) {
        List<BlockPos> cells = new ArrayList<>(9);
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) {
                cells.add(min.offset(x, 0, z));
            }
        }
        return cells;
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

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private record Layout(List<UUID> portals, UUID poleSource, UUID wallSource, UUID twistSource, UUID twistDestination, OpticTransform poleToward,
                          OpticTransform wallToward, OpticTransform twistToward, LookTransfer poleLook, LookTransfer wallLook,
                          LookTransfer twistLook) {
    }

    private record PreparedRoute(TravelMessage.TravelBegin arm, ClientLevel source, ClientLevel destination, LocalPlayer player) {
    }

    private record Samples(List<TravelTap.Frame> frames, PreparedRoute prepared, double eyeHeight, List<LookContinuityTravelTap.Attempt> attempts,
                           ClientLevel settledLevel, LocalPlayer settledPlayer) {
    }
}
