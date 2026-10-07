package art.arcane.wormholes.clientgametest;

import art.arcane.optics.crossing.ArrivalOrientation;
import art.arcane.optics.crossing.OrientationRule;
import art.arcane.optics.crossing.PlaneCrossing;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.math.Angles;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.stream.ViewStreamCapability;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.transit.OrientationPolicy;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static art.arcane.wormholes.clientgametest.NativeClientViewAssertions.cells;
import static art.arcane.wormholes.clientgametest.NativeClientViewAssertions.runtime;

final class SeamlessScenario {
    static final int NEGOTIATION_TIMEOUT_TICKS = 400;
    private static final Logger LOGGER = LoggerFactory.getLogger("WormholesClientGameTest");
    private static final int PREPARATION_TIMEOUT_TICKS = 600;
    private static final int CROSSING_TIMEOUT_TICKS = 120;
    private static final int ACCEPT_TIMEOUT_TICKS = 100;
    private static final int SETTLE_TICKS = 60;
    private static final int RETURN_VIEW_TICKS = 20;
    private static final double CROSSING_JUMP = 4.0D;
    private static final double POSE_TOLERANCE = 1.0E-3D;
    private static final int STEP_AWAY_TICKS = 8;
    private static final float LOOK_TOLERANCE = 0.5F;
    private static final int VIEW_SETTLE_TICKS = 100;
    private static final double TURN_PIXELS = 40.0D;
    private static final float TURNING_HEAD_LAG = 1.0F;

    private SeamlessScenario() {
    }

    static Route build(ServerPlayer actor, ServerLevel sourceLevel, BlockPos sourceMin, ServerLevel destinationLevel,
                       BlockPos destinationMin, OrientationPolicy orientation) {
        WormholesModRuntime runtime = runtime();
        fill(sourceLevel, sourceMin.offset(-4, 0, -6), 11, 6, 16, Blocks.AIR.defaultBlockState());
        fill(sourceLevel, sourceMin.offset(-4, -1, -6), 11, 1, 16, Blocks.STONE.defaultBlockState());
        fill(destinationLevel, destinationMin.offset(-6, 0, -12), 15, 6, 22, Blocks.AIR.defaultBlockState());
        fill(destinationLevel, destinationMin.offset(-6, -1, -12), 15, 1, 22, Blocks.STONE.defaultBlockState());
        BlockPos standPosition = destinationMin.offset(4, 0, -3);
        ArmorStand stand = EntityTypes.ARMOR_STAND.create(destinationLevel, EntitySpawnReason.COMMAND);
        assertTrue(stand != null, "armour stand could not be created");
        stand.snapTo(standPosition.getX() + 0.5D, standPosition.getY(), standPosition.getZ() + 0.5D, 0.0F, 0.0F);
        stand.setNoGravity(true);
        destinationLevel.addFreshEntity(stand);
        BlockPos wall = destinationMin.offset(-4, 1, -3);
        destinationLevel.setBlockAndUpdate(wall, Blocks.STONE.defaultBlockState());
        ItemFrame frame = new ItemFrame(destinationLevel, wall.relative(Direction.EAST), Direction.EAST);
        assertTrue(destinationLevel.addFreshEntity(frame), "item frame could not be placed");
        MinecraftPortal source = runtime.portals().create(actor.getUUID(), sourceLevel, cells(sourceMin), PortalType.PORTAL, new Vec3(0, 0, -1));
        MinecraftPortal destination = runtime.portals().create(actor.getUUID(), destinationLevel, cells(destinationMin), PortalType.PORTAL, new Vec3(0, 0, -1));
        assertTrue(source != null && destination != null, "portal creation rejected");
        source.setOrientation(orientation);
        destination.setOrientation(orientation);
        assertTrue(runtime.portals().link(actor, source.getId(), destination.getId()), "source link rejected");
        assertTrue(runtime.portals().link(actor, destination.getId(), source.getId()), "return link rejected");
        Vec3d approach = new Vec3d(sourceMin.getX() + 1.5D, sourceMin.getY() + 1.62D, sourceMin.getZ() + 6.5D);
        Leg outbound = leg(source, destination, approach, orientation);
        Leg inbound = leg(destination, source, outbound.toward().point(new Vec3d(approach.x(), approach.y(), approach.z() - 13.0D)), orientation);
        return new Route(source.getId(), destination.getId(), sourceLevel.dimension(), sourceMin, destinationMin, outbound, inbound,
            stand.getId(), frame.getId());
    }

    static void approach(TestServerContext server, ServerPlayer player, Route route) {
        server.runOnServer(minecraftServer -> player.teleportTo(minecraftServer.getLevel(route.sourceLevel()), route.sourceMin().getX() + 1.5D,
            route.sourceMin().getY(), route.sourceMin().getZ() + 6.5D, Set.of(), 180.0F, 0.0F, false));
    }

    static void turnBack(ClientGameTestContext context, TestServerConnection connection, Route route) {
        context.getInput().holdKeyFor(options -> options.keyUp, STEP_AWAY_TICKS);
        context.waitTicks(SETTLE_TICKS);
        Vec3d portal = route.inbound().exit().sourceOrigin();
        Vec3 feet = context.computeOnClient(client -> client.player.position());
        context.getInput().lookAt((float) Math.toDegrees(Math.atan2(-(portal.x() - feet.x), portal.z() - feet.z)), 0.0F);
        context.waitTicks(SETTLE_TICKS);
        awaitPrepared(context, connection);
    }

    static void awaitPrepared(ClientGameTestContext context, TestServerConnection connection) {
        connection.waitForChunksRender();
        context.waitTicks(VIEW_SETTLE_TICKS);
        context.waitFor(client -> WormholesClient.instance().preparedTravel().readyRevision() > 0
            && !WormholesClient.instance().preparedTravel().adopted() && !WormholesClient.instance().preparedTravel().pendingCrossing(),
            PREPARATION_TIMEOUT_TICKS);
    }

    static Crossing walkThrough(ClientGameTestContext context, String label) {
        context.runOnClient(client -> TravelTap.reset());
        int player = context.computeOnClient(client -> System.identityHashCode(client.player));
        ClientLevel source = context.computeOnClient(client -> client.level);
        context.getInput().holdKey(options -> options.keyUp);
        try {
            context.waitFor(client -> TravelTap.crossingFrame(CROSSING_JUMP) >= 0, CROSSING_TIMEOUT_TICKS);
        } finally {
            context.getInput().releaseKey(options -> options.keyUp);
        }
        context.waitTicks(1);
        int index = context.computeOnClient(client -> TravelTap.crossingFrame(CROSSING_JUMP));
        return new Crossing(label, index, player, source);
    }

    static Crossing walkThroughTurning(ClientGameTestContext context, String label) {
        context.runOnClient(client -> TravelTap.reset());
        int player = context.computeOnClient(client -> System.identityHashCode(client.player));
        ClientLevel source = context.computeOnClient(client -> client.level);
        context.getInput().holdKey(options -> options.keyUp);
        try {
            for (int tick = 0; tick < CROSSING_TIMEOUT_TICKS && context.computeOnClient(client -> TravelTap.crossingFrame(CROSSING_JUMP)) < 0; tick++) {
                context.getInput().moveCursor((tick & 1) == 0 ? TURN_PIXELS : -TURN_PIXELS, 0.0D);
                context.waitTick();
            }
        } finally {
            context.getInput().releaseKey(options -> options.keyUp);
        }
        context.waitTicks(1);
        int index = context.computeOnClient(client -> TravelTap.crossingFrame(CROSSING_JUMP));
        assertTrue(index >= 0, label + ": the player never crossed the portal while turning");
        float headLag = TravelTap.frames().get(index).headLag();
        LOGGER.info("[{}] crossing frame look leads the head yaw by {} degrees", label, String.format("%.3f", headLag));
        assertTrue(Math.abs(headLag) > TURNING_HEAD_LAG, label + ": the crossing frame was not turning (look leads head by " + headLag + ")");
        return new Crossing(label, index, player, source);
    }

    static void assertSeamlessNegotiated(ClientGameTestContext context) {
        boolean seamless = context.computeOnClient(client -> WormholesClient.instance().session().active()
            && WormholesClient.instance().session().has(ViewStreamCapability.SEAMLESS_TRAVEL));
        assertTrue(seamless, "the server did not negotiate SEAMLESS_TRAVEL (capability 20) for this session");
    }

    static void assertPreparedTravel(ClientGameTestContext context, Crossing crossing, boolean dimensionChanged) {
        context.waitFor(client -> !WormholesClient.instance().preparedTravel().pendingCrossing(), ACCEPT_TIMEOUT_TICKS);
        context.waitTicks(SETTLE_TICKS);
        reportFrameTimes(crossing);
        assertTrue(!dimensionChanged || TravelTap.respawns() > 0, crossing.label() + ": prepared travel received no respawn packet");
        assertTrue(TravelTap.positions() > 0, crossing.label() + ": prepared travel received no position packet");
        assertTrue(!TravelTap.loadingScreenShown(), crossing.label() + ": the level loading screen was shown");
        assertSamePlayer(context, crossing);
    }

    static void assertSeamlessTravel(ClientGameTestContext context, Route route, Crossing crossing) {
        boolean present = context.computeOnClient(client -> client.level.getEntity(route.standId()) != null
            && client.level.getEntity(route.frameId()) != null);
        assertTrue(present, crossing.label() + ": destination entities were not resident on the first tick after the crossing");
        assertSeamless(context, crossing, route.outbound(), route.destinationMin());
        assertTrue(!TravelTap.addedAny(route.standId(), route.frameId()),
            crossing.label() + ": destination entities were added again after the crossing");
    }

    static void assertSeamlessReturn(ClientGameTestContext context, Route route, Crossing crossing) {
        assertSeamless(context, crossing, route.inbound(), route.sourceMin());
    }

    private static void assertSeamless(ClientGameTestContext context, Crossing crossing, Leg leg, BlockPos returnPortal) {
        context.waitFor(client -> !WormholesClient.instance().preparedTravel().pendingCrossing(), ACCEPT_TIMEOUT_TICKS);
        boolean levelChanged = context.computeOnClient(client -> client.level != crossing.source());
        if (levelChanged) {
            context.waitFor(client -> WormholesClient.instance().preparedTravel().residents().handle(crossing.source()) > 0, RETURN_VIEW_TICKS);
        }
        context.waitFor(client -> NativeClientViewAssertions.sections(NativeClientViewAssertions.portalKey(returnPortal)) > 0, RETURN_VIEW_TICKS);
        context.waitTicks(SETTLE_TICKS);
        assertTrue(context.computeOnClient(client -> !client.levelRenderer.visibleSections().isEmpty()),
            crossing.label() + ": the main renderer draws nothing of the arrival level");
        reportFrameTimes(crossing);
        assertTrue(TravelTap.respawns() == 0, crossing.label() + ": " + TravelTap.respawns() + " respawn packets were handled");
        assertTrue(TravelTap.positions() == 0, crossing.label() + ": " + TravelTap.positions() + " position packets were handled");
        assertTrue(TravelTap.accepts() == 0, crossing.label() + ": " + TravelTap.accepts() + " teleport acknowledgements were sent");
        assertTrue(!TravelTap.loadingScreenShown(), crossing.label() + ": the level loading screen was shown");
        assertTrue(!TravelTap.clientUnloaded(), crossing.label() + ": the client left the loaded state");
        assertSamePlayer(context, crossing);
        assertPoseContinuity(leg, crossing);
    }

    static void assertPoseContinuity(Leg leg, Crossing crossing) {
        List<TravelTap.Frame> frames = TravelTap.frames();
        assertTrue(crossing.index() >= 2 && crossing.index() < frames.size(), crossing.label() + ": no crossing frame was recorded");
        TravelTap.Frame earlier = frames.get(crossing.index() - 2);
        TravelTap.Frame before = frames.get(crossing.index() - 1);
        TravelTap.Frame after = frames.get(crossing.index());
        double step = Math.max(before.tickSpeed(), after.tickSpeed()) * (after.clock() - before.clock());
        Vec3d mapped = leg.toward().point(new Vec3d(before.camera().x, before.camera().y, before.camera().z));
        double gap = new Vec3(mapped.x(), mapped.y(), mapped.z()).distanceTo(after.camera());
        LOGGER.info("[{}] pose continuity: camera moved {} blocks across the crossing frame, per-frame speed {} blocks over {} ticks", crossing.label(),
            String.format("%.5f", gap), String.format("%.5f", step), String.format("%.4f", after.clock() - before.clock()));
        assertTrue(gap <= step + POSE_TOLERANCE, crossing.label() + ": camera moved " + gap + " blocks across the crossing frame (step " + step + ")");
        Angles.Look expected = expectedLook(leg, before);
        float yawStep = Math.max(Math.abs(wrap(before.yaw() - earlier.yaw())), Math.abs(after.headLag()));
        float pitchStep = Math.abs(before.pitch() - earlier.pitch());
        assertTrue(Math.abs(wrap(after.yaw() - expected.yaw())) <= yawStep + LOOK_TOLERANCE,
            crossing.label() + ": yaw " + after.yaw() + " after the crossing, expected " + expected.yaw());
        assertTrue(Math.abs(after.pitch() - expected.pitch()) <= pitchStep + LOOK_TOLERANCE,
            crossing.label() + ": pitch " + after.pitch() + " after the crossing, expected " + expected.pitch());
    }

    static void reportFrameTimes(Crossing crossing) {
        List<TravelTap.Frame> frames = TravelTap.frames();
        if (crossing.index() < 2) {
            LOGGER.info("[{}] frame timing unavailable: crossing frame {}", crossing.label(), crossing.index());
            return;
        }
        long[] before = new long[crossing.index()];
        for (int index = 0; index < before.length; index++) {
            before[index] = frames.get(index).tickNanos();
        }
        Arrays.sort(before);
        long median = before[before.length / 2];
        long worst = 0L;
        int worstFrame = -1;
        int slow = 0;
        List<String> after = new ArrayList<>();
        for (int index = crossing.index(); index < Math.min(frames.size(), crossing.index() + 40); index++) {
            long nanos = frames.get(index).tickNanos();
            if (nanos > worst) {
                worst = nanos;
                worstFrame = index - crossing.index();
            }
            if (nanos > median * 2L) {
                slow++;
            }
            after.add(String.format("%.1f", nanos / 1_000_000.0D));
        }
        LOGGER.info("[{}] frame time median {} ms over {} frames before the crossing; worst {} ms at crossing+{}; {} of 40 frames above 2x median; crossing frame {}; {}; frames {}",
            crossing.label(), String.format("%.2f", median / 1_000_000.0D), before.length, String.format("%.2f", worst / 1_000_000.0D),
            worstFrame, slow, crossing.index(), TravelTap.events(), after);
    }

    static void fill(ServerLevel level, BlockPos min, int sizeX, int sizeY, int sizeZ, BlockState state) {
        for (int x = 0; x < sizeX; x++) {
            for (int y = 0; y < sizeY; y++) {
                for (int z = 0; z < sizeZ; z++) {
                    level.setBlockAndUpdate(min.offset(x, y, z), state);
                }
            }
        }
    }

    static void finish(ClientGameTestContext context, TestServerContext server, ServerPlayer player, Route route) {
        context.restoreDefaultGameOptions();
        server.runOnServer(minecraftServer -> {
            runtime().portals().remove(player, route.source());
            runtime().portals().remove(player, route.destination());
        });
    }

    static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertSamePlayer(ClientGameTestContext context, Crossing crossing) {
        int player = context.computeOnClient(client -> System.identityHashCode(client.player));
        assertTrue(player == crossing.player(), crossing.label() + ": the local player object was replaced");
    }

    private static Angles.Look expectedLook(Leg leg, TravelTap.Frame before) {
        Vec3d look = Angles.direction(before.yaw(), before.pitch());
        Exit exit = leg.exit();
        PlaneCrossing crossing = new PlaneCrossing(exit.sourceView(), exit.sourceOrigin(),
            new Vec3d(before.camera().x, before.camera().y, before.camera().z), look, look, exit.front());
        return ArrivalOrientation.apply(crossing, exit.destinationFrame(), leg.orientation(), false);
    }

    private static Leg leg(MinecraftPortal from, MinecraftPortal to, Vec3d approach, OrientationPolicy orientation) {
        boolean front = side(from, approach) > 0.0D;
        OpticTransform toward = OpticTransform.between(from.getFrame().view(front), from.getOrigin(), to.getFrame().view(front), to.getOrigin());
        return new Leg(toward, new Exit(from.getFrame().view(front), from.getOrigin(), to.getFrame(), front), orientation.rule());
    }

    private static double side(MinecraftPortal portal, Vec3d point) {
        Vec3d origin = portal.getOrigin();
        return (point.x() - origin.x()) * portal.getFrame().getNormal().x() + (point.y() - origin.y()) * portal.getFrame().getNormal().y()
            + (point.z() - origin.z()) * portal.getFrame().getNormal().z();
    }

    private static float wrap(float degrees) {
        float wrapped = degrees % 360.0F;
        if (wrapped >= 180.0F) {
            wrapped -= 360.0F;
        }
        if (wrapped < -180.0F) {
            wrapped += 360.0F;
        }
        return wrapped;
    }

    record Route(UUID source, UUID destination, ResourceKey<Level> sourceLevel, BlockPos sourceMin, BlockPos destinationMin,
                 Leg outbound, Leg inbound, int standId, int frameId) {
    }

    record Leg(OpticTransform toward, Exit exit, OrientationRule orientation) {
    }

    record Exit(Frame sourceView, Vec3d sourceOrigin, Frame destinationFrame, boolean front) {
    }

    record Crossing(String label, int index, int player, ClientLevel source) {
    }
}
