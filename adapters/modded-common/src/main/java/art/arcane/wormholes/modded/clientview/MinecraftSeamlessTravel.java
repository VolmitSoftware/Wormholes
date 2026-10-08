package art.arcane.wormholes.modded.clientview;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.crossing.PlaneCrossing;
import art.arcane.optics.crossing.Pose;
import art.arcane.optics.crossing.PoseTransform;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.frame.Similarity;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.MinecraftPortalRegistry;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.mixin.SeamlessListenerAccess;
import art.arcane.wormholes.modded.seamless.MinecraftSeamlessMove;
import art.arcane.wormholes.modded.seamless.RemoteRoute;
import art.arcane.wormholes.modded.seamless.RemoteRoutes;
import art.arcane.wormholes.modded.seamless.RouteWindow;
import art.arcane.wormholes.network.MinecraftGatewayPolicies;
import art.arcane.wormholes.network.client.TravelMessage;
import art.arcane.wormholes.nexus.NetworkMember;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.render.client.session.ClientViewTravel;
import art.arcane.wormholes.render.client.session.SeamlessCrossCheck;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class MinecraftSeamlessTravel {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");
    private static final int CLAIM_GRACE_TICKS = 3;
    private static final int MAX_CROSSINGS_PER_TICK = 4;
    private static final int LATE_CLAIM_TICKS = 40;
    private static final int RETIRED_ARM_TICKS = 40;
    private static final double SIDE_HYSTERESIS_BLOCKS = 2.0D;
    private static final double SIDE_HYSTERESIS_TICKS = 2.0D;
    private static final long ACCEPT_REVISION = 1L;

    private final WormholesModRuntime runtime;
    private final MinecraftClientViewPortalAccess portals;
    private final MinecraftPreparedTravel prepared;
    private final Map<UUID, Traveler> travelers = new HashMap<>();
    private final List<UUID> interested = new ArrayList<>();
    private long generation;

    MinecraftSeamlessTravel(WormholesModRuntime runtime, MinecraftClientViewPortalAccess portals, MinecraftPreparedTravel prepared) {
        this.runtime = runtime;
        this.portals = portals;
        this.prepared = prepared;
    }

    void tick(ClientViewTravel<MinecraftClientViewPeer> travel, ServerPlayer player) {
        UUID playerId = player.getUUID();
        RemoteRoutes routes = runtime.remoteRoutes();
        travel.drainAcks(ack -> routes.ack(playerId, ack));
        travel.drainReopens(reopen -> routes.reopen(playerId, reopen.levelHandle()));
        Traveler traveler = travelers.get(playerId);
        if (traveler != null && traveler.flight != null && runtime.server().getTickCount() > traveler.flight.started + 1
            && !runtime.portals().travelling(playerId) && !runtime.doors().travelling(playerId)) {
            finish(travel, player, null);
        }
        arm(travel, player);
    }

    void settle(ClientViewTravel<MinecraftClientViewPeer> travel, ServerPlayer player) {
        TravelMessage.TravelCross request = travel.takeSeamlessCross();
        while (request != null) {
            cross(travel, player, request);
            request = travel.takeSeamlessCross();
        }
    }

    boolean defer(ServerPlayer player, UUID source) {
        Traveler traveler = travelers.get(player.getUUID());
        if (traveler == null) {
            return false;
        }
        if (traveler.flight != null) {
            return true;
        }
        Arm arm = traveler.arms.get(source);
        if (arm == null) {
            return false;
        }
        long tick = runtime.server().getTickCount();
        if (traveler.grace.waiting(source, tick)) {
            return true;
        }
        traveler.flight = new Flight(arm, null, null, tick);
        return false;
    }

    boolean armed(UUID player, UUID source) {
        Traveler traveler = travelers.get(player);
        return traveler != null && (traveler.arms.containsKey(source) || traveler.flight != null && traveler.flight.arm.source().getId().equals(source));
    }

    boolean crossing(UUID player) {
        Traveler traveler = travelers.get(player);
        return traveler != null && traveler.flight != null;
    }

    TravelMessage.TravelBegin attempted(UUID player) {
        Traveler traveler = travelers.get(player);
        return traveler == null || traveler.flight == null ? null : traveler.flight.arm.begin();
    }

    boolean owns(UUID player, TravelMessage.TravelBegin begin) {
        Traveler traveler = travelers.get(player);
        return begin != null && traveler != null && traveler.flight != null && traveler.flight.arm.begin().token().equals(begin.token());
    }

    MinecraftSeamlessMove.Context arrival(ClientViewTravel<MinecraftClientViewPeer> travel, ServerPlayer player, UUID source, ServerLevel world,
                                          TravelMessage.TravelPose arrival, Vec3d velocity) {
        Traveler traveler = travelers.get(player.getUUID());
        Flight flight = traveler == null ? null : traveler.flight;
        if (flight == null || !flight.arm.source().getId().equals(source) || flight.arm.world() != world) {
            return null;
        }
        RemoteRoute route = runtime.remoteRoutes().route(player.getUUID(), source);
        boolean changed = world != player.level();
        if (route == null || route.level() != world || route.handle() != flight.arm.handle() || changed && !route.resident()) {
            return null;
        }
        long tick = runtime.server().getTickCount();
        TravelMessage.TravelPose pose = flight.request == null ? arrival : pose(arrival, flight.request, flight.crossing,
            flight.arm.begin().rules(), flight.arm.destination().getFrame(), flight.arm.destination().getOrigin());
        TravelMessage.TravelAccept accept = new TravelMessage.TravelAccept(flight.arm.begin().token(), flight.arm.begin().generation(),
            ACCEPT_REVISION, pose, velocity, route.handle(), changed, tick);
        return new MinecraftSeamlessMove.Context(runtime, player, world, new PositionMoveRotation(new Vec3(pose.x(), pose.y(), pose.z()),
            new Vec3(velocity.x(), velocity.y(), velocity.z()), pose.yaw(), pose.pitch()), route,
            route.resident() ? returnRoute(travel, player, flight.arm, route.handle()) : null, accept, travel::sendTravel, tick);
    }

    void moved(ServerPlayer player, boolean moved) {
        Traveler traveler = travelers.get(player.getUUID());
        if (traveler == null || traveler.flight == null) {
            return;
        }
        traveler.flight.accepted = moved;
        if (moved && traveler.flight.request == null) {
            traveler.lateToken = traveler.flight.arm.begin().token();
            traveler.lateUntil = runtime.server().getTickCount() + LATE_CLAIM_TICKS;
        }
    }

    void finish(ClientViewTravel<MinecraftClientViewPeer> travel, ServerPlayer player, TravelMessage.TravelBegin attempted) {
        Traveler traveler = travelers.get(player.getUUID());
        Flight flight = traveler == null ? null : traveler.flight;
        if (flight == null || attempted != null && !flight.arm.begin().token().equals(attempted.token())) {
            return;
        }
        traveler.flight = null;
        traveler.grace.settled();
        if (flight.accepted) {
            return;
        }
        if (flight.request == null) {
            LOGGER.info("Crossing abandoned {}: the server crossing could not land", player.getScoreboardName());
            return;
        }
        reject(travel, player, flight.request, "the crossing could not land");
    }

    void rearm(ClientViewTravel<MinecraftClientViewPeer> travel, ServerPlayer player) {
        arm(travel, player);
    }

    void relocated(UUID player) {
        Traveler traveler = travelers.get(player);
        if (traveler != null) {
            traveler.sides.relocated();
        }
    }

    void forget(UUID player) {
        travelers.remove(player);
    }

    void clear() {
        travelers.clear();
    }

    static TravelMessage.TravelPose pose(TravelMessage.TravelPose arrival, TravelMessage.TravelCross cross, PlaneCrossing crossing,
                                         TravelMessage.ArrivalRules rules, Frame destination, Vec3d destinationOrigin) {
        TravelMessage.TravelPose source = cross.sourcePose();
        Vec3d position = new Vec3d(source.x(), source.y(), source.z());
        Pose departed = new Pose(position, position, position, crossing.velocity(), source.yaw(), source.pitch(),
            source.yaw(), source.pitch(), source.yaw(), source.yaw(), source.yaw(), source.yaw());
        Pose arrived = PoseTransform.arrive(departed, crossing, Similarity.of(crossing.toward(destination, destinationOrigin), 1.0D), destination,
            rules.orientation(), rules.gravityFlip(), rules.momentum(), rules.momentum().maxSpeed());
        return new TravelMessage.TravelPose(arrival.x(), arrival.y(), arrival.z(), arrived.yaw(), arrived.pitch());
    }

    static SeamlessCrossCheck.Server authority(ServerPlayer player, ApertureDescriptor geometry, Vec3d velocity) {
        return new SeamlessCrossCheck.Server(player.level().dimension().identifier().toString(), geometry,
            new TravelMessage.TravelPose(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot()), velocity,
            player.getEyeHeight(), ((SeamlessListenerAccess) player.connection).wormholesAwaitingPosition() != null, player.isChangingDimension());
    }

    static boolean residentArrival(RemoteRoute route, ServerPlayer player, Vec3d arrival) {
        int chunkX = arrival.blockX() >> 4;
        int chunkZ = arrival.blockZ() >> 4;
        return route.resident() ? route.stream().delivered(ChunkPos.pack(chunkX, chunkZ))
            : player.level().getChunkSource().chunkMap.isChunkTracked(player, chunkX, chunkZ);
    }

    private void arm(ClientViewTravel<MinecraftClientViewPeer> travel, ServerPlayer player) {
        UUID playerId = player.getUUID();
        RemoteRoutes routes = runtime.remoteRoutes();
        routes.update(player, travel, candidates(travel, player), runtime.server().getTickCount());
        Traveler traveler = travelers.computeIfAbsent(playerId, ignored -> new Traveler());
        long tick = runtime.server().getTickCount();
        List<RemoteRoute> active = routes.routes(playerId);
        Set<UUID> live = new HashSet<>(active.size() * 2);
        int coreRadius = RemoteRoutes.coreRadius(RemoteRoutes.fullRadius(player.requestedViewDistance(),
            runtime.server().getPlayerList().getViewDistance()));
        MinecraftClientViewPeer peer = travel.player();
        double speed = runtime.portals().observedVelocity(player).distance(new Vec3d(0.0D, 0.0D, 0.0D));
        for (int index = 0; index < active.size(); index++) {
            RemoteRoute route = active.get(index);
            MinecraftPortal source = portals.portal(peer, route.sourceId());
            MinecraftPortal destination = source == null ? null : peer.portals().projectionDestination(source);
            if (destination == null || !destination.getId().equals(route.destinationId()) || route.resident() && !route.opened()
                || runtime.portals().resolveLevel(source) != player.level() && peer.door(source.getId()) != source) {
                continue;
            }
            UUID sourceId = source.getId();
            Arm current = traveler.arms.get(sourceId);
            boolean front = traveler.sides.front(current != null, current != null && current.front(), planeDistance(player, source), speed);
            long identity = peer.portals().routeIdentity(source);
            if (current != null && current.matches(source, destination, route, front, identity)) {
                live.add(sourceId);
                continue;
            }
            Arm next = arm(travel, player, source, destination, route, front, identity, coreRadius);
            if (next != null && travel.sendTravel(next.begin())) {
                if (current != null) {
                    traveler.retire(current, tick);
                }
                traveler.arms.put(sourceId, next);
                live.add(sourceId);
            }
        }
        Iterator<Map.Entry<UUID, Arm>> iterator = traveler.arms.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Arm> entry = iterator.next();
            if (live.contains(entry.getKey()) || traveler.flight != null && traveler.flight.arm == entry.getValue()) {
                continue;
            }
            iterator.remove();
            traveler.retire(entry.getValue(), tick);
            travel.sendTravel(new TravelMessage.TravelCancel(entry.getValue().begin().token(), entry.getValue().begin().generation()));
        }
        traveler.retired.removeIf(retired -> retired.until() < tick);
        traveler.sides.evaluated();
    }

    private static double planeDistance(ServerPlayer player, MinecraftPortal portal) {
        Vec3d origin = portal.getOrigin();
        Vec3 eye = player.getEyePosition();
        return (eye.x - origin.x()) * portal.getFrame().getNormal().x() + (eye.y - origin.y()) * portal.getFrame().getNormal().y()
            + (eye.z - origin.z()) * portal.getFrame().getNormal().z();
    }

    static boolean keepsSide(double distance, double speed) {
        return Math.abs(distance) < SIDE_HYSTERESIS_BLOCKS + speed * SIDE_HYSTERESIS_TICKS;
    }

    private Arm arm(ClientViewTravel<MinecraftClientViewPeer> travel, ServerPlayer player, MinecraftPortal source, MinecraftPortal destination,
                    RemoteRoute route, boolean front, long identity, int coreRadius) {
        MinecraftClientViewPeer peer = travel.player();
        ServerLevel world = route.level();
        ApertureDescriptor geometry = portals.travelGeometry(peer, source, front);
        MinecraftClientViewScene.Destination mapped = geometry == null ? null : portals.scene().destination(peer, source.getId(), front);
        TravelMessage.TravelWorld metadata = RemoteRoutes.travelWorld(world).orElse(null);
        if (mapped == null || metadata == null || geometry.mirror()) {
            return null;
        }
        Vec3d feet = PlaneCrossing.planePoint(source.getFrame(), source.getOrigin(), new Vec3d(player.getX(), player.getY(), player.getZ()),
            destination.getFrame(), destination.getOrigin());
        Vec3d eye = feet.add(new Vec3d(0, player.getEyeHeight(), 0));
        Vec3d anchor = destination.getOrigin();
        RouteWindow core = new RouteWindow((int) Math.floor(anchor.x()) >> 4, (int) Math.floor(anchor.z()) >> 4, coreRadius);
        Similarity toward = MinecraftPortalRegistry.towardDestination(source, destination, front);
        OpticTransform destinationToSource = toward.isRigid() ? mapped.frame().transform() : TravelMessage.TravelBegin.destinationToSource(toward);
        TravelMessage.TravelBegin begin = new TravelMessage.TravelBegin(UUID.randomUUID(), ++generation, source.getId(),
            player.level().dimension().identifier().toString(), geometry, destinationToSource, (float) toward.scale(), metadata,
            new TravelMessage.TravelPose(feet.x(), feet.y(), feet.z(), player.getYRot(), player.getXRot()), core.coordinates(),
            MinecraftPortalEnvironment.capture(world, eye, OpticTransform.IDENTITY, world.isFlat()), TravelMessage.MAX_TRAVEL_EXPIRY_MILLIS,
            prepared.rules(peer, source), route.resident(), route.handle(), true);
        return new Arm(source, destination, world, front, identity, route.handle(), begin);
    }

    private List<RemoteRoutes.Candidate> candidates(ClientViewTravel<MinecraftClientViewPeer> travel, ServerPlayer player) {
        interested.clear();
        portals.interested(travel.player(), interested);
        List<RemoteRoutes.Candidate> candidates = new ArrayList<>(interested.size());
        Vec3d feet = new Vec3d(player.getX(), player.getY(), player.getZ());
        for (UUID id : interested) {
            MinecraftPortal source = portals.portal(travel.player(), id);
            if (source == null || source.isMirrorMode() || !source.isOpen() || source.getType() == PortalType.RTP
                || MinecraftGatewayPolicies.active(source)) {
                continue;
            }
            MinecraftPortal destination = travel.player().portals().projectionDestination(source);
            ServerLevel world = destination == null ? null : runtime.portals().resolveLevel(destination);
            if (world == null || RemoteRoutes.travelWorld(world).isEmpty() || !prepared.eligible(travel.player(), player, source, destination)) {
                continue;
            }
            candidates.add(new RemoteRoutes.Candidate(source, destination, world, source.getOrigin().distance(feet)));
        }
        return candidates;
    }

    private void cross(ClientViewTravel<MinecraftClientViewPeer> travel, ServerPlayer player, TravelMessage.TravelCross request) {
        Traveler traveler = travelers.computeIfAbsent(player.getUUID(), ignored -> new Traveler());
        long tick = runtime.server().getTickCount();
        if (request.token().equals(traveler.lateToken) && tick <= traveler.lateUntil) {
            return;
        }
        Arm arm = traveler.find(request.token(), request.generation());
        if (arm == null) {
            reject(travel, player, request, "route not armed");
            return;
        }
        if (traveler.flight != null) {
            reject(travel, player, request, "another crossing is in flight");
            return;
        }
        if (traveler.crossingTick != tick) {
            traveler.crossingTick = tick;
            traveler.crossings = 0;
        }
        if (++traveler.crossings > MAX_CROSSINGS_PER_TICK) {
            reject(travel, player, request, "more than " + MAX_CROSSINGS_PER_TICK + " crossings in one tick");
            return;
        }
        Refused refused = refusal(travel, player, arm, request);
        if (refused.reason() != null) {
            reject(travel, player, request, refused.reason());
            return;
        }
        traveler.flight = new Flight(arm, request, refused.crossing(), tick);
        traveler.grace.settled();
        boolean dispatched = prepared.dispatchCross(travel.player(), player, arm.source(), arm.destination(), arm.begin().sourceGeometry().kind(),
            refused.crossing());
        Flight flight = traveler.flight;
        if (flight == null) {
            rearm(travel, player);
            return;
        }
        if (!dispatched || !runtime.portals().travelling(player.getUUID()) && !runtime.doors().travelling(player.getUUID())) {
            traveler.flight = null;
            if (flight.accepted) {
                rearm(travel, player);
                return;
            }
            reject(travel, player, request, dispatched ? "the departure was refused" : "admission refused the crossing");
        }
    }

    private Refused refusal(ClientViewTravel<MinecraftClientViewPeer> travel, ServerPlayer player, Arm arm, TravelMessage.TravelCross request) {
        MinecraftClientViewPeer peer = travel.player();
        RemoteRoute route = runtime.remoteRoutes().route(player.getUUID(), arm.source().getId());
        if (route == null || route.level() != arm.world() || route.handle() != arm.handle()) {
            return new Refused(null, "route changed");
        }
        if (player.level() != runtime.portals().resolveLevel(arm.source()) || peer.portals().projectionDestination(arm.source()) != arm.destination()
            || peer.portals().routeIdentity(arm.source()) != arm.identity()) {
            return new Refused(null, "destination changed");
        }
        if (player.getVehicle() != null || !player.getPassengers().isEmpty()) {
            return new Refused(null, "riding");
        }
        Vec3d feet = new Vec3d(player.getX(), player.getY(), player.getZ());
        Vec3d velocity = runtime.portals().observedVelocity(player);
        SeamlessCrossCheck.Refusal check = SeamlessCrossCheck.check(request, arm.begin(),
            authority(player, portals.travelGeometry(peer, arm.source(), arm.front()), velocity));
        if (check != SeamlessCrossCheck.Refusal.NONE) {
            return new Refused(null, check.name().toLowerCase(Locale.ROOT).replace('_', ' ') + String.format(Locale.ROOT,
                " (claimed %.2f %.2f %.2f, server %.2f %.2f %.2f, speed %.2f)", request.sourcePose().x(), request.sourcePose().y(),
                request.sourcePose().z(), feet.x(), feet.y(), feet.z(), velocity.distance(new Vec3d(0, 0, 0))));
        }
        boolean front = arm.begin().sourceGeometry().frontSide();
        Vec3 look = Vec3.directionFromRotation(request.sourcePose().pitch(), request.sourcePose().yaw());
        PlaneCrossing crossing = new PlaneCrossing(arm.source().getFrame().view(front), arm.source().getOrigin(),
            new Vec3d(request.sourcePose().x(), request.sourcePose().y(), request.sourcePose().z()), velocity, new Vec3d(look.x, look.y, look.z), front);
        if (!residentArrival(route, player, MinecraftPortalRegistry.passage(arm.source(), arm.destination(), crossing).toward().point(crossing.point()))) {
            return new Refused(null, "arrival column not delivered");
        }
        return destinationMatches(peer, player, arm.source(), arm.destination(), crossing)
            ? new Refused(crossing, null) : new Refused(null, "destination resolves elsewhere");
    }

    boolean destinationMatches(MinecraftClientViewPeer peer, ServerPlayer player, MinecraftPortal source, MinecraftPortal destination,
                                       PlaneCrossing crossing) {
        if (peer.door(source.getId()) == source) {
            return true;
        }
        NetworkMember selected = runtime.portals().resolveDestination(source, player, crossing);
        return selected != null && selected.isLocal() && selected.portalId().equals(destination.getId());
    }

    private void reject(ClientViewTravel<MinecraftClientViewPeer> travel, ServerPlayer player, TravelMessage.TravelCross request, String reason) {
        LOGGER.info("Crossing rejected {}: {}", player.getScoreboardName(), reason);
        travel.sendTravel(new TravelMessage.TravelCancel(request.token(), request.generation()));
        player.connection.teleport(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
    }

    private RemoteRoutes.Return returnRoute(ClientViewTravel<MinecraftClientViewPeer> travel, ServerPlayer player, Arm arm, int handle) {
        MinecraftPortal arrival = arm.destination();
        if (runtime.portals().get(arrival.getId()) != arrival || !arrival.isOpen() || arrival.isMirrorMode()) {
            return null;
        }
        MinecraftPortal back = travel.player().portals().projectionDestination(arrival);
        if (back == null || runtime.portals().get(back.getId()) != back || runtime.portals().resolveLevel(back) != player.level()
            || !back.isOpen() || back.isMirrorMode() || !runtime.portals().canDepart(player, arrival) || !runtime.portals().canArrive(player, back)) {
            return null;
        }
        TravelMessage.RemoteLevelOpen open = RemoteRoutes.openReturn(player.level(), back, handle,
            RemoteRoutes.fullRadius(player.requestedViewDistance(), runtime.server().getPlayerList().getViewDistance()));
        return open == null ? null : new RemoteRoutes.Return(arrival, back, open);
    }

    private record Arm(MinecraftPortal source, MinecraftPortal destination, ServerLevel world, boolean front, long identity, int handle,
                       TravelMessage.TravelBegin begin) {
        private boolean matches(MinecraftPortal nextSource, MinecraftPortal nextDestination, RemoteRoute route, boolean nextFront,
                                long nextIdentity) {
            return source == nextSource && destination == nextDestination && world == route.level() && front == nextFront
                && identity == nextIdentity && handle == route.handle();
        }
    }

    private record Refused(PlaneCrossing crossing, String reason) {
    }

    private record Retired(Arm arm, long until) {
    }

    static final class ClaimGrace {
        private UUID source;
        private long until;

        boolean waiting(UUID next, long tick) {
            if (!next.equals(source)) {
                source = next;
                until = tick + CLAIM_GRACE_TICKS;
            }
            if (tick < until) {
                return true;
            }
            source = null;
            return false;
        }

        void settled() {
            source = null;
        }
    }

    static final class SideMemory {
        private boolean relocated;

        boolean front(boolean armed, boolean remembered, double distance, double speed) {
            return armed && !relocated && keepsSide(distance, speed) ? remembered : distance >= 0.0D;
        }

        void relocated() {
            relocated = true;
        }

        void evaluated() {
            relocated = false;
        }
    }

    private static final class Flight {
        private final Arm arm;
        private final TravelMessage.TravelCross request;
        private final PlaneCrossing crossing;
        private final long started;
        private boolean accepted;

        private Flight(Arm arm, TravelMessage.TravelCross request, PlaneCrossing crossing, long started) {
            this.arm = arm;
            this.request = request;
            this.crossing = crossing;
            this.started = started;
        }
    }

    private static final class Traveler {
        private final Map<UUID, Arm> arms = new HashMap<>();
        private final List<Retired> retired = new ArrayList<>();
        private final ClaimGrace grace = new ClaimGrace();
        private final SideMemory sides = new SideMemory();
        private Flight flight;
        private long crossingTick = Long.MIN_VALUE;
        private int crossings;
        private UUID lateToken;
        private long lateUntil;

        private Arm find(UUID token, long generation) {
            for (Arm arm : arms.values()) {
                if (arm.begin().token().equals(token) && arm.begin().generation() == generation) {
                    return arm;
                }
            }
            for (int index = 0; index < retired.size(); index++) {
                Arm arm = retired.get(index).arm();
                if (arm.begin().token().equals(token) && arm.begin().generation() == generation) {
                    return arm;
                }
            }
            return null;
        }

        private void retire(Arm arm, long tick) {
            retired.add(new Retired(arm, tick + RETIRED_ARM_TICKS));
        }
    }
}
