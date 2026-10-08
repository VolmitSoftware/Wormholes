package art.arcane.wormholes.clientgametest;

import art.arcane.optics.crossing.ArrivalOrientation;
import art.arcane.optics.crossing.OrientationRule;
import art.arcane.optics.crossing.PlaneCrossing;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.math.Angles;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.client.ClientPreparedTravel;
import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.network.client.ClientViewExtensions;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.transit.OrientationPolicy;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import static art.arcane.wormholes.clientgametest.NativeClientViewAssertions.cells;
import static art.arcane.wormholes.clientgametest.NativeClientViewAssertions.runtime;

final class SeamlessScenario {
    static final int NEGOTIATION_TIMEOUT_TICKS = 400;
    private static final Logger LOGGER = LoggerFactory.getLogger("WormholesClientGameTest");
    private static final int PREPARATION_TIMEOUT_TICKS = 600;
    private static final int CROSSING_TIMEOUT_TICKS = 120;
    private static final int ACCEPT_TIMEOUT_TICKS = 100;
    private static final int SETTLE_TICKS = 60;
    static final int RETURN_VIEW_TICKS = 20;
    static final int LOADED_RETURN_VIEW_TICKS = 300;
    private static final double CROSSING_JUMP = 4.0D;
    private static final double POSE_TOLERANCE = 1.0E-3D;
    private static final int STEP_AWAY_TICKS = 8;
    private static final float LOOK_TOLERANCE = 0.5F;
    private static final int VIEW_SETTLE_TICKS = 100;
    private static final double TURN_PIXELS = 40.0D;
    private static final float TURNING_HEAD_LAG = 1.0F;
    private static final int MAX_LOGGED_HANDLE = 8;

    private SeamlessScenario() {
    }

    static Route build(ServerPlayer actor, MinecraftServer server, RouteSpec spec) {
        WormholesModRuntime runtime = runtime(server);
        ServerLevel sourceLevel = Objects.requireNonNull(server.getLevel(spec.sourceLevel()), "source level " + spec.sourceLevel());
        ServerLevel destinationLevel = Objects.requireNonNull(server.getLevel(spec.destinationLevel()), "destination level " + spec.destinationLevel());
        BlockPos sourceMin = spec.sourceMin();
        BlockPos destinationMin = spec.destinationMin();
        fill(sourceLevel, sourceMin.offset(-4, 0, -6), 11, 6, 16, Blocks.AIR.defaultBlockState());
        fill(sourceLevel, sourceMin.offset(-4, -1, -6), 11, 1, 16, Blocks.STONE.defaultBlockState());
        fill(destinationLevel, destinationMin.offset(-7, -1, -13), 17, 8, 24, Blocks.STONE.defaultBlockState());
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
        if (spec.churn()) {
            churn(sourceLevel, sourceMin.offset(4, 0, 2));
            churn(destinationLevel, destinationMin.offset(4, 0, 0));
        }
        MinecraftPortal source = runtime.portals().create(actor.getUUID(), sourceLevel, cells(sourceMin), PortalType.PORTAL, new Vec3(0, 0, -1));
        MinecraftPortal destination = runtime.portals().create(actor.getUUID(), destinationLevel, cells(destinationMin), PortalType.PORTAL, new Vec3(0, 0, -1));
        assertTrue(source != null && destination != null, "portal creation rejected");
        source.setOrientation(spec.orientation());
        destination.setOrientation(spec.orientation());
        assertTrue(runtime.portals().link(actor, source.getId(), destination.getId()), "source link rejected");
        assertTrue(runtime.portals().link(actor, destination.getId(), source.getId()), "return link rejected");
        Vec3d approach = new Vec3d(sourceMin.getX() + 1.5D, sourceMin.getY() + 1.62D, sourceMin.getZ() + 6.5D);
        Leg outbound = leg(source, destination, approach, spec.orientation());
        Leg inbound = leg(destination, source, outbound.toward().point(new Vec3d(approach.x(), approach.y(), approach.z() - 13.0D)), spec.orientation());
        return new Route(source.getId(), destination.getId(), spec.sourceLevel(), sourceMin, destinationMin, outbound, inbound,
            stand.getUUID(), frame.getUUID());
    }

    static void teleportToApproach(ServerPlayer player, ServerLevel level, BlockPos sourceMin) {
        player.teleportTo(level, sourceMin.getX() + 1.5D, sourceMin.getY(), sourceMin.getZ() + 6.5D, Set.of(), 180.0F, 0.0F, false);
    }

    static void removePortal(ServerPlayer player, MinecraftServer server, UUID portal) {
        runtime(server).portals().remove(player, portal);
    }

    static void join(SeamlessClient client) {
        client.waitForChunksDownload();
        client.waitFor(minecraft -> WormholesClient.instance() != null && WormholesClient.instance().session().active(), NEGOTIATION_TIMEOUT_TICKS);
    }

    static void turnBack(SeamlessClient client, Route route) {
        Exit exit = route.inbound().exit();
        Vec3d portal = exit.sourceOrigin();
        Vec3 arrived = client.computeOnClient(minecraft -> minecraft.player.position());
        Face normal = exit.sourceView().getNormal();
        double side = Math.signum((arrived.x - portal.x()) * normal.x() + (arrived.y - portal.y()) * normal.y() + (arrived.z - portal.z()) * normal.z());
        client.lookAt(Angles.yaw(normal.x() * side, normal.z() * side), 0.0F);
        client.holdForwardFor(STEP_AWAY_TICKS);
        client.waitTicks(SETTLE_TICKS);
        Vec3 feet = client.computeOnClient(minecraft -> minecraft.player.position());
        client.lookAt((float) Math.toDegrees(Math.atan2(-(portal.x() - feet.x), portal.z() - feet.z)), 0.0F);
        client.waitTicks(SETTLE_TICKS);
        awaitPrepared(client);
    }

    static void awaitReady(SeamlessClient client) {
        client.waitFor(SeamlessScenario::ready, PREPARATION_TIMEOUT_TICKS);
    }

    static void turnAround(SeamlessClient client, Vec3d portal) {
        client.holdForwardFor(STEP_AWAY_TICKS);
        Vec3 feet = client.computeOnClient(minecraft -> minecraft.player.position());
        client.lookAt((float) Math.toDegrees(Math.atan2(-(portal.x() - feet.x), portal.z() - feet.z)), 0.0F);
        awaitReady(client);
    }

    static void awaitPrepared(SeamlessClient client) {
        client.waitForChunksRender();
        client.waitTicks(VIEW_SETTLE_TICKS);
        client.waitFor(SeamlessScenario::ready, PREPARATION_TIMEOUT_TICKS);
    }

    static Crossing walkThrough(SeamlessClient client, String label) {
        client.runOnClient(minecraft -> TravelTap.reset());
        int player = client.computeOnClient(minecraft -> System.identityHashCode(minecraft.player));
        ClientLevel source = client.computeOnClient(minecraft -> minecraft.level);
        client.holdForward();
        try {
            client.waitFor(minecraft -> TravelTap.crossingFrame(CROSSING_JUMP) >= 0, CROSSING_TIMEOUT_TICKS);
        } finally {
            client.releaseForward();
        }
        client.waitTicks(1);
        int index = client.computeOnClient(minecraft -> TravelTap.crossingFrame(CROSSING_JUMP));
        return new Crossing(label, index, player, source);
    }

    static Crossing walkThroughTurning(SeamlessClient client, String label) {
        client.runOnClient(minecraft -> TravelTap.reset());
        int player = client.computeOnClient(minecraft -> System.identityHashCode(minecraft.player));
        ClientLevel source = client.computeOnClient(minecraft -> minecraft.level);
        client.holdForward();
        try {
            for (int tick = 0; tick < CROSSING_TIMEOUT_TICKS && client.computeOnClient(minecraft -> TravelTap.crossingFrame(CROSSING_JUMP)) < 0; tick++) {
                client.moveCursor((tick & 1) == 0 ? TURN_PIXELS : -TURN_PIXELS, 0.0D);
                client.waitTicks(1);
            }
        } finally {
            client.releaseForward();
        }
        client.waitTicks(1);
        int index = client.computeOnClient(minecraft -> TravelTap.crossingFrame(CROSSING_JUMP));
        assertTrue(index >= 0, label + ": the player never crossed the portal while turning");
        float headLag = TravelTap.frames().get(index).headLag();
        LOGGER.info("[{}] crossing frame look leads the head yaw by {} degrees", label, String.format("%.3f", headLag));
        assertTrue(Math.abs(headLag) > TURNING_HEAD_LAG, label + ": the crossing frame was not turning (look leads head by " + headLag + ")");
        return new Crossing(label, index, player, source);
    }

    static boolean ready(Minecraft minecraft) {
        ClientPreparedTravel travel = WormholesClient.instance().preparedTravel();
        return (travel.readyRevision() > 0 || travel.seamless().armed()) && !travel.adopted() && !travel.pendingCrossing()
            && !travel.seamless().pending();
    }

    static void assertSeamlessNegotiated(SeamlessClient client) {
        boolean seamless = client.computeOnClient(minecraft -> WormholesClient.instance().session().active()
            && WormholesClient.instance().session().has(ClientViewExtensions.REMOTE_VIEW)
            && WormholesClient.instance().session().has(ClientViewExtensions.SEAMLESS_TRAVEL));
        assertTrue(seamless, "the server did not negotiate REMOTE_VIEW and SEAMLESS_TRAVEL for this session");
    }

    static void assertPreparedTravel(SeamlessClient client, Crossing crossing, boolean dimensionChanged) {
        client.waitFor(minecraft -> !WormholesClient.instance().preparedTravel().pendingCrossing()
            && !WormholesClient.instance().preparedTravel().seamless().pending(), ACCEPT_TIMEOUT_TICKS);
        client.waitTicks(SETTLE_TICKS);
        reportFrameTimes(crossing);
        assertTrue(!dimensionChanged || TravelTap.respawns() > 0, crossing.label() + ": prepared travel received no respawn packet");
        assertTrue(TravelTap.positions() > 0, crossing.label() + ": prepared travel received no position packet");
        assertTrue(!TravelTap.loadingScreenShown(), crossing.label() + ": the level loading screen was shown");
        assertSamePlayer(client, crossing);
    }

    static void assertSeamlessTravel(SeamlessClient client, Route route, Crossing crossing, int returnViewTicks) {
        boolean present = client.computeOnClient(minecraft -> minecraft.level.getEntity(route.stand()) != null
            && minecraft.level.getEntity(route.frame()) != null);
        if (!present) {
            throw new AssertionError(crossing.label() + ": destination entities were not resident on the first tick after the crossing; "
                + client.computeOnClient(minecraft -> arrivalState(minecraft, route, crossing)));
        }
        assertSeamless(client, crossing, route.outbound(), route.destinationMin(), returnViewTicks);
        assertTrue(!TravelTap.addedAny(route.stand(), route.frame()),
            crossing.label() + ": destination entities were added again after the crossing");
    }

    static void assertSeamlessReturn(SeamlessClient client, Route route, Crossing crossing, int returnViewTicks) {
        assertSeamless(client, crossing, route.inbound(), route.sourceMin(), returnViewTicks);
    }

    static void finish(SeamlessClient client, SeamlessServer server, Route route) {
        client.restoreDefaultGameOptions();
        server.remove(route);
    }

    static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertSeamless(SeamlessClient client, Crossing crossing, Leg leg, BlockPos returnPortal, int returnViewTicks) {
        client.waitFor(minecraft -> !WormholesClient.instance().preparedTravel().pendingCrossing()
            && !WormholesClient.instance().preparedTravel().seamless().pending(), ACCEPT_TIMEOUT_TICKS);
        boolean levelChanged = client.computeOnClient(minecraft -> minecraft.level != crossing.source());
        if (levelChanged) {
            client.waitFor(minecraft -> WormholesClient.instance().preparedTravel().residents().handle(crossing.source()) > 0, returnViewTicks);
        }
        int returnView = ticksUntil(client, minecraft -> NativeClientViewAssertions.sections(NativeClientViewAssertions.portalKey(returnPortal)) > 0,
            returnViewTicks, crossing.label() + ": the return view had no sections");
        LOGGER.info("[{}] return view present {} ticks after the crossing was accepted", crossing.label(), returnView);
        client.waitTicks(SETTLE_TICKS);
        assertTrue(client.computeOnClient(minecraft -> MainRendererSections.visible(minecraft) > 0),
            crossing.label() + ": the main renderer draws nothing of the arrival level");
        reportFrameTimes(crossing);
        assertTrue(TravelTap.respawns() == 0, crossing.label() + ": " + TravelTap.respawns() + " respawn packets were handled");
        assertTrue(TravelTap.positions() == 0, crossing.label() + ": " + TravelTap.positions() + " position packets were handled");
        assertTrue(TravelTap.accepts() == 0, crossing.label() + ": " + TravelTap.accepts() + " teleport acknowledgements were sent");
        assertTrue(!TravelTap.loadingScreenShown(), crossing.label() + ": the level loading screen was shown");
        assertTrue(!TravelTap.clientUnloaded(), crossing.label() + ": the client left the loaded state");
        assertSamePlayer(client, crossing);
        assertPoseContinuity(leg, crossing);
    }

    private static int ticksUntil(SeamlessClient client, Predicate<Minecraft> condition, int timeoutTicks, String failure) {
        for (int tick = 0; tick <= timeoutTicks; tick++) {
            if (client.computeOnClient(condition::test)) {
                return tick;
            }
            client.waitTicks(1);
        }
        throw new AssertionError(failure + " within " + timeoutTicks + " ticks");
    }

    private static void assertPoseContinuity(Leg leg, Crossing crossing) {
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

    private static void reportFrameTimes(Crossing crossing) {
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

    private static void churn(ServerLevel level, BlockPos origin) {
        level.setBlockAndUpdate(origin, Blocks.OBSERVER.defaultBlockState().setValue(ObserverBlock.FACING, Direction.EAST));
        level.setBlockAndUpdate(origin.east(), Blocks.OBSERVER.defaultBlockState().setValue(ObserverBlock.FACING, Direction.WEST));
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

    private static String arrivalState(Minecraft minecraft, Route route, Crossing crossing) {
        ClientPreparedTravel travel = WormholesClient.instance().preparedTravel();
        StringBuilder state = new StringBuilder();
        state.append("level ").append(minecraft.level.dimension().identifier()).append(minecraft.level == crossing.source() ? " (crossing source)" : " (swapped)")
            .append(", player ").append(minecraft.player.position()).append(", pending ").append(travel.pendingCrossing())
            .append(", adopted ").append(travel.adopted()).append(", confirmed ").append(travel.positionConfirmed());
        for (int handle = 1; handle <= MAX_LOGGED_HANDLE; handle++) {
            if (travel.residents().has(handle)) {
                ClientLevel level = travel.residents().level(handle);
                state.append(", resident ").append(handle).append(' ').append(level.dimension().identifier())
                    .append(level == minecraft.level ? " (current)" : "").append(" stand ").append(level.getEntity(route.stand()) != null)
                    .append(" frame ").append(level.getEntity(route.frame()) != null);
            }
        }
        return state.append(", ").append(TravelTap.events()).append(", re-added ").append(TravelTap.addedAny(route.stand(), route.frame())).toString();
    }

    private static void assertSamePlayer(SeamlessClient client, Crossing crossing) {
        int player = client.computeOnClient(minecraft -> System.identityHashCode(minecraft.player));
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

    record RouteSpec(ResourceKey<Level> sourceLevel, BlockPos sourceMin, ResourceKey<Level> destinationLevel, BlockPos destinationMin,
                     OrientationPolicy orientation, boolean churn) {
    }

    record Route(UUID source, UUID destination, ResourceKey<Level> sourceLevel, BlockPos sourceMin, BlockPos destinationMin,
                 Leg outbound, Leg inbound, UUID stand, UUID frame) {
    }

    record Leg(OpticTransform toward, Exit exit, OrientationRule orientation) {
    }

    record Exit(Frame sourceView, Vec3d sourceOrigin, Frame destinationFrame, boolean front) {
    }

    record Crossing(String label, int index, int player, ClientLevel source) {
    }
}
