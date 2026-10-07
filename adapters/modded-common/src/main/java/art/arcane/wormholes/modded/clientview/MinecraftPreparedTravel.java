package art.arcane.wormholes.modded.clientview;

import art.arcane.optics.math.Vec3d;
import art.arcane.optics.plate.ChunkLease;
import art.arcane.optics.crossing.PlaneCrossing;
import art.arcane.optics.crossing.Pose;
import art.arcane.optics.crossing.PoseTransform;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.MinecraftChunkPacketEncoding;
import art.arcane.wormholes.modded.MinecraftProjectionWorldView;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.mixin.SeamlessListenerAccess;
import art.arcane.wormholes.modded.seamless.MinecraftSeamlessMove;
import art.arcane.wormholes.modded.seamless.RemoteRoute;
import art.arcane.wormholes.modded.seamless.RemoteRoutes;
import art.arcane.wormholes.modded.seamless.RouteWindow;
import art.arcane.wormholes.network.MinecraftGatewayPolicies;
import art.arcane.wormholes.nexus.NetworkMember;
import art.arcane.wormholes.portal.PortalType;
import it.unimi.dsi.fastutil.longs.LongList;
import art.arcane.wormholes.network.client.ClientTravelWindow;
import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.wormholes.render.client.session.ClientPreparedTravelServer;
import art.arcane.wormholes.render.client.session.ClientViewTravel;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import art.arcane.wormholes.network.client.TravelMessage;
import art.arcane.wormholes.portal.ApertureKind;

final class MinecraftPreparedTravel {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");
    private static final long CAPTURE_NANOS = 2_000_000L;
    private static final int MAX_CAPTURE_COLUMNS = 16;
    private static final int MAX_LEASE_REQUESTS = 16;
    private static final int SEND_BYTES_PER_TICK = 128 * 1024;
    private final WormholesModRuntime runtime;
    private final MinecraftClientViewPortalAccess portals;
    private final Map<UUID, Preparation> preparations = new HashMap<>();
    private final Map<UUID, LandingWarmup> landings = new HashMap<>();
    private final Map<UUID, Long> retryAfter = new HashMap<>();
    private final List<UUID> interested = new ArrayList<>();
    private long generation;

    MinecraftPreparedTravel(WormholesModRuntime runtime, MinecraftClientViewPortalAccess portals) {
        this.runtime = runtime;
        this.portals = portals;
    }

    void tick(ClientViewTravel<MinecraftClientViewPeer> travel, ServerPlayer player) {
        if (!travel.preparedTravelSelected()) {
            discard(travel);
            return;
        }
        if (travel.seamlessSelected()) {
            seamlessTick(travel, player);
            return;
        }
        Optional<TravelMessage.TravelCross> crossing = travel.server().takeCross();
        if (crossing.isPresent()) {
            cross(travel, player, crossing.get());
            return;
        }
        Long retry = retryAfter.get(player.getUUID());
        if (retry != null && System.currentTimeMillis() < retry) {
            return;
        }
        MinecraftPortal source = nearest(travel, player);
        if (source == null) {
            discard(travel);
            return;
        }
        MinecraftPortal destination = travel.player().portals().projectionDestination(source);
        ServerLevel world = destination == null ? null : runtime.portals().resolveLevel(destination);
        if (world == null || !eligible(travel.player(), player, source, destination)) {
            discard(travel);
            return;
        }
        Preparation preparation = preparations.get(player.getUUID());
        Vec3d feet = mappedCrossing(player, source, destination);
        long route = travel.player().portals().routeIdentity(source);
        LandingRoute landing = new LandingRoute(source, destination, world, route, feet.getBlockX() >> 4, feet.getBlockZ() >> 4);
        if (!landingReady(travel, player, landing)) {
            return;
        }
        int radius = ClientTravelWindow.radius(Math.min(player.requestedViewDistance(), runtime.server().getPlayerList().getViewDistance()));
        if (preparation == null || preparation.source != source || preparation.destination != destination
            || preparation.world != world || preparation.route != route || !preparation.contains(feet)
            || preparation.coordinates.size() != ClientTravelWindow.count(radius)
            || travel.server().preparing().isEmpty()) {
            LandingWarmup warmed = landings.remove(player.getUUID());
            discard(travel);
            boolean transferred = false;
            try {
                preparation = create(travel, player, source, destination, world, feet, route, radius);
                if (preparation != null && warmed != null) {
                    preparation.leases.put(new TravelMessage.TravelCoordinate(landing.chunkX(), landing.chunkZ()), warmed.lease());
                    transferred = true;
                }
            } finally {
                if (warmed != null && !transferred) {
                    warmed.lease().close();
                }
            }
            if (preparation == null) {
                return;
            }
            preparations.put(player.getUUID(), preparation);
        }
        validate(travel, preparation);
        if (preparation.committed) {
            return;
        }
        CaptureBudget captureBudget = new CaptureBudget(travel.preparedTravelCacheSelected(), System.nanoTime());
        for (int checked = 0; checked < preparation.coordinates.size(); checked++) {
            if (!captureBudget.allows(System.nanoTime())) {
                break;
            }
            TravelMessage.TravelCoordinate coordinate = travel.server().nextCapture();
            if (coordinate == null) {
                break;
            }
            if (!preparation.leases.containsKey(coordinate)) {
                if (!captureBudget.requestLease()) {
                    continue;
                }
                preparation.leases.put(coordinate, runtime.leases().retain(world,
                    MinecraftProjectionWorldView.worldId(world), coordinate.x(), coordinate.z()));
            }
            LevelChunk chunk = world.getChunkSource().getChunkNow(coordinate.x(), coordinate.z());
            if (chunk != null) {
                byte[] payload;
                try {
                    payload = encode(world, chunk);
                } catch (RuntimeException failure) {
                    LOGGER.error("Could not prepare portal {} arrival chunk {}, {} in {} for {}", source.getId(),
                        coordinate.x(), coordinate.z(), world.dimension().identifier(), player.getUUID(), failure);
                    retryAfter.put(player.getUUID(), System.currentTimeMillis() + 30_000L);
                    discard(travel);
                    return;
                }
                if (!travel.server().column(coordinate, travel.server().nextRevision(coordinate), payload)) {
                    LOGGER.warn("Could not retain portal {} prepared arrival chunk {}, {} in {} for {}; delaying another preparation",
                        source.getId(), coordinate.x(), coordinate.z(), world.dimension().identifier(), player.getUUID());
                    retryAfter.put(player.getUUID(), System.currentTimeMillis() + 30_000L);
                    discard(travel);
                    return;
                }
                preparation.captured.put(coordinate, new Captured(chunk, runtime.projections().changes().currentVersion()));
                captureBudget.captured(payload.length);
            }
        }
        travel.server().tick(System.currentTimeMillis(), SEND_BYTES_PER_TICK, travel::sendTravel);
    }

    Optional<TravelMessage.TravelCommit> commit(ClientViewTravel<MinecraftClientViewPeer> travel,
                                                    ServerPlayer player, UUID source, ServerLevel world,
                                                    TravelMessage.TravelPose arrival, Vec3d velocity) {
        Preparation preparation = preparations.get(player.getUUID());
        if (preparation == null || preparation.world != world || !travel.server().crossing()) {
            return Optional.empty();
        }
        validate(travel, preparation);
        Optional<TravelMessage.TravelCommit> result = travel.server().commit(new ClientPreparedTravelServer.Commit(source,
            player.level().dimension().identifier().toString(), world.dimension().identifier().toString(), arrival, velocity,
            System.currentTimeMillis()));
        if (result.isPresent()) {
            preparation.committed = true;
        }
        return result;
    }

    private void cross(ClientViewTravel<MinecraftClientViewPeer> travel, ServerPlayer player,
                       TravelMessage.TravelCross request) {
        Preparation preparation = preparations.get(player.getUUID());
        ApertureDescriptor geometry = preparation == null ? null : travel.travelGeometry(preparation.source.getId());
        Vec3d feet = new Vec3d(player.getX(), player.getY(), player.getZ());
        Vec3d velocity = runtime.portals().observedVelocity(player);
        boolean allowed = preparation != null && geometry != null && !preparation.committed
            && player.level() == runtime.portals().resolveLevel(preparation.source)
            && travel.player().portals().projectionDestination(preparation.source) == preparation.destination
            && travel.player().portals().routeIdentity(preparation.source) == preparation.route
            && player.getVehicle() == null && player.getPassengers().isEmpty();
        if (allowed) {
            validate(travel, preparation);
            allowed = travel.server().validCross(request, new ClientPreparedTravelServer.Authority(
                player.level().dimension().identifier().toString(), geometry,
                new TravelMessage.TravelPose(feet.x(), feet.y(), feet.z(), player.getYRot(), player.getXRot()),
                velocity, player.getEyeHeight()), System.currentTimeMillis());
        }
        if (allowed) {
            boolean front = geometry.signedDistance(request.previousEye().x(), request.previousEye().y(), request.previousEye().z()) > 0.0D;
            Vec3d admittedFeet = new Vec3d(request.sourcePose().x(), request.sourcePose().y(), request.sourcePose().z());
            Vec3 look = Vec3.directionFromRotation(request.sourcePose().pitch(), request.sourcePose().yaw());
            PlaneCrossing actual = new PlaneCrossing(preparation.source.getFrame().view(front), preparation.source.getOrigin(), admittedFeet,
                velocity, new Vec3d(look.x, look.y, look.z), front);
            allowed = dispatchCross(travel.player(), player, preparation.source, preparation.destination, geometry.kind(), actual);
        }
        if (!allowed) {
            discard(travel);
            player.connection.teleport(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
        }
    }

    void clear() {
        for (Preparation preparation : preparations.values()) {
            preparation.close();
        }
        preparations.clear();
        for (LandingWarmup landing : landings.values()) {
            landing.lease().close();
        }
        landings.clear();
        retryAfter.clear();
    }

    void complete(UUID player, TravelMessage.TravelCommit commit) {
        if (commit != null) {
            complete(player, commit.token(), commit.generation());
        }
    }

    void complete(UUID player, UUID token, long generation) {
        Preparation preparation = preparations.get(player);
        if (preparation == null || preparation.begin == null || !preparation.begin.token().equals(token)
            || preparation.begin.generation() != generation) {
            return;
        }
        if (preparations.remove(player, preparation)) {
            preparation.close();
        }
    }

    void complete(UUID player) {
        LandingWarmup landing = landings.remove(player);
        if (landing != null) {
            landing.lease().close();
        }
        Preparation preparation = preparations.remove(player);
        if (preparation != null) {
            preparation.close();
        }
    }

    MinecraftSeamlessMove.Context seamlessArrival(ClientViewTravel<MinecraftClientViewPeer> travel, ServerPlayer player, UUID source,
                                                  ServerLevel world, TravelMessage.TravelPose arrival, Vec3d velocity) {
        Preparation preparation = preparations.get(player.getUUID());
        if (preparation == null || !preparation.seamless || preparation.cross == null || preparation.world != world
            || !preparation.source.getId().equals(source) || !travel.server().crossing()) {
            return null;
        }
        RemoteRoute route = runtime.remoteRoutes().route(player.getUUID(), source);
        boolean changed = world != player.level();
        if (route == null || route.level() != world || route.handle() != preparation.handle || changed && !route.resident()) {
            return null;
        }
        long tick = runtime.server().getTickCount();
        TravelMessage.TravelPose pose = seamlessPose(arrival, preparation.cross, preparation.crossing, preparation.begin.rules(),
            preparation.destination.getFrame(), preparation.destination.getOrigin());
        Optional<TravelMessage.TravelAccept> accept = travel.server().accept(new ClientPreparedTravelServer.Commit(source,
            player.level().dimension().identifier().toString(), world.dimension().identifier().toString(), pose, velocity,
            System.currentTimeMillis()), route.handle(), changed, tick);
        if (accept.isEmpty()) {
            return null;
        }
        preparation.committed = true;
        return new MinecraftSeamlessMove.Context(runtime, player, world, new PositionMoveRotation(new Vec3(pose.x(), pose.y(), pose.z()),
            new Vec3(velocity.x(), velocity.y(), velocity.z()), pose.yaw(), pose.pitch()), route, returnRoute(travel, player, preparation, route.handle()),
            accept.get(), travel::sendTravel, tick);
    }

    boolean seamlessPreparation(UUID player) {
        Preparation preparation = preparations.get(player);
        return preparation != null && preparation.seamless;
    }

    void settleCross(ClientViewTravel<MinecraftClientViewPeer> travel, ServerPlayer player) {
        Optional<TravelMessage.TravelCross> crossing = travel.server().takeCross();
        if (crossing.isPresent()) {
            crossSeamless(travel, player, crossing.get());
        }
    }

    private void seamlessTick(ClientViewTravel<MinecraftClientViewPeer> travel, ServerPlayer player) {
        UUID playerId = player.getUUID();
        RemoteRoutes routes = runtime.remoteRoutes();
        travel.drainAcks(ack -> routes.ack(playerId, ack));
        List<RemoteRoutes.Candidate> candidates = seamlessCandidates(travel, player);
        routes.update(player, travel, candidates, runtime.server().getTickCount());
        RemoteRoutes.Candidate nearest = null;
        for (int index = 0; index < candidates.size(); index++) {
            RemoteRoutes.Candidate candidate = candidates.get(index);
            if ((nearest == null || candidate.distance() < nearest.distance()) && !runtime.portals().arrivalBlocks(player, candidate.source())) {
                nearest = candidate;
            }
        }
        RemoteRoute route = nearest == null ? null : routes.route(playerId, nearest.source().getId());
        if (route == null) {
            discard(travel);
            return;
        }
        int coreRadius = RemoteRoutes.coreRadius(RemoteRoutes.fullRadius(player.requestedViewDistance(),
            runtime.server().getPlayerList().getViewDistance()));
        Vec3d anchor = nearest.destination().getOrigin();
        int centerX = (int) Math.floor(anchor.x()) >> 4;
        int centerZ = (int) Math.floor(anchor.z()) >> 4;
        Preparation preparation = preparations.get(playerId);
        if (preparation == null || !preparation.seamless || preparation.source != nearest.source() || preparation.destination != nearest.destination()
            || preparation.world != nearest.level() || preparation.handle != route.handle() || !preparation.core.matches(centerX, centerZ, coreRadius)
            || preparation.route != travel.player().portals().routeIdentity(nearest.source()) || travel.server().preparing().isEmpty()) {
            discard(travel);
            preparation = createSeamless(travel, player, nearest, route, new RouteWindow(centerX, centerZ, coreRadius));
            if (preparation == null) {
                return;
            }
            preparations.put(playerId, preparation);
        }
        if (preparation.committed) {
            return;
        }
        markResident(travel, player, route, preparation);
        travel.server().tick(System.currentTimeMillis(), 0, travel::sendTravel);
    }

    private void markResident(ClientViewTravel<MinecraftClientViewPeer> travel, ServerPlayer player, RemoteRoute route, Preparation preparation) {
        if (route.resident() && preparation.marked == route.stream().version()) {
            return;
        }
        ChunkMap chunks = player.level().getChunkSource().chunkMap;
        LongList keys = preparation.core.keys();
        for (int index = 0; index < keys.size(); index++) {
            long key = keys.getLong(index);
            int x = ChunkPos.getX(key);
            int z = ChunkPos.getZ(key);
            TravelMessage.TravelCoordinate coordinate = new TravelMessage.TravelCoordinate(x, z);
            boolean ready = route.resident() ? route.stream().delivered(key) : chunks.isChunkTracked(player, x, z);
            if (ready) {
                travel.server().routed(coordinate, route.resident() ? route.stream().revision(key) : 1);
            } else {
                travel.server().invalidate(coordinate);
            }
        }
        preparation.marked = route.resident() ? route.stream().version() : Long.MIN_VALUE;
    }

    private List<RemoteRoutes.Candidate> seamlessCandidates(ClientViewTravel<MinecraftClientViewPeer> travel, ServerPlayer player) {
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
            if (world == null || RemoteRoutes.travelWorld(world).isEmpty() || !eligible(travel.player(), player, source, destination)) {
                continue;
            }
            candidates.add(new RemoteRoutes.Candidate(source, destination, world, source.getOrigin().distance(feet)));
        }
        return candidates;
    }

    private Preparation createSeamless(ClientViewTravel<MinecraftClientViewPeer> travel, ServerPlayer player, RemoteRoutes.Candidate candidate,
                                       RemoteRoute route, RouteWindow core) {
        MinecraftPortal source = candidate.source();
        MinecraftPortal destination = candidate.destination();
        ServerLevel world = candidate.level();
        ApertureDescriptor geometry = travel.travelGeometry(source.getId());
        MinecraftClientViewScene.Destination mapped = geometry == null ? null
            : portals.scene().destination(travel.player(), source.getId(), geometry.frontSide());
        if (mapped == null || geometry.mirror()) {
            return null;
        }
        Vec3d feet = mappedCrossing(player, source, destination);
        Vec3d eye = feet.add(new Vec3d(0, player.getEyeHeight(), 0));
        List<TravelMessage.TravelCoordinate> coordinates = core.coordinates();
        TravelMessage.TravelBegin begin = new TravelMessage.TravelBegin(UUID.randomUUID(), ++generation, source.getId(),
            player.level().dimension().identifier().toString(), geometry, mapped.frame().transform(), RemoteRoutes.travelWorld(world).orElseThrow(),
            new TravelMessage.TravelPose(feet.x(), feet.y(), feet.z(), player.getYRot(), player.getXRot()), coordinates,
            MinecraftPortalEnvironment.capture(world, eye, OpticTransform.IDENTITY, world.isFlat()),
            TravelMessage.MAX_TRAVEL_EXPIRY_MILLIS, rules(travel.player(), source), route.resident(), route.handle(), true);
        travel.server().begin(begin, System.currentTimeMillis());
        Preparation preparation = new Preparation(new PreparationOptions(source, destination, world,
            travel.player().portals().routeIdentity(source), coordinates));
        preparation.begin = begin;
        preparation.seamless = true;
        preparation.handle = route.handle();
        preparation.core = core;
        return preparation;
    }

    private void crossSeamless(ClientViewTravel<MinecraftClientViewPeer> travel, ServerPlayer player, TravelMessage.TravelCross request) {
        Preparation preparation = preparations.get(player.getUUID());
        TravelMessage.TravelBegin begin = preparation == null ? null : preparation.begin;
        PlaneCrossing actual = preparation == null || preparation.committed || !preparation.seamless ? null : seamlessCrossing(travel, player, preparation, request);
        if (actual != null) {
            preparation.cross = request;
            preparation.crossing = actual;
            dispatchCross(travel.player(), player, preparation.source, preparation.destination,
                travel.travelGeometry(preparation.source.getId()).kind(), actual);
        }
        if (actual == null || !runtime.clientViews().seamlessAccepted(player.getUUID(), begin.token())) {
            discard(travel);
            player.connection.teleport(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
        }
    }

    private PlaneCrossing seamlessCrossing(ClientViewTravel<MinecraftClientViewPeer> travel, ServerPlayer player, Preparation preparation,
                                           TravelMessage.TravelCross request) {
        ApertureDescriptor geometry = travel.travelGeometry(preparation.source.getId());
        RemoteRoute route = runtime.remoteRoutes().route(player.getUUID(), preparation.source.getId());
        if (geometry == null || route == null || route.level() != preparation.world || route.handle() != preparation.handle
            || player.level() != runtime.portals().resolveLevel(preparation.source)
            || travel.player().portals().projectionDestination(preparation.source) != preparation.destination
            || travel.player().portals().routeIdentity(preparation.source) != preparation.route
            || player.getVehicle() != null || !player.getPassengers().isEmpty()) {
            return null;
        }
        Vec3d feet = new Vec3d(player.getX(), player.getY(), player.getZ());
        Vec3d velocity = runtime.portals().observedVelocity(player);
        ClientPreparedTravelServer.SeamlessRejection rejection = travel.server().validSeamlessCross(request, new ClientPreparedTravelServer.Authority(
                player.level().dimension().identifier().toString(), geometry,
                new TravelMessage.TravelPose(feet.x(), feet.y(), feet.z(), player.getYRot(), player.getXRot()), velocity, player.getEyeHeight()),
            seamlessAuthority(player, runtime.server().getTickCount(), runtime.configuration().settings().getMain().teleportCooldownMillis),
            System.currentTimeMillis());
        if (rejection != ClientPreparedTravelServer.SeamlessRejection.NONE) {
            LOGGER.debug("Seamless crossing of {} through {} rejected: {}", player.getUUID(), preparation.source.getId(), rejection);
            return null;
        }
        boolean front = geometry.signedDistance(request.previousEye().x(), request.previousEye().y(), request.previousEye().z()) > 0.0D;
        Vec3 look = Vec3.directionFromRotation(request.sourcePose().pitch(), request.sourcePose().yaw());
        PlaneCrossing actual = new PlaneCrossing(preparation.source.getFrame().view(front), preparation.source.getOrigin(),
            new Vec3d(request.sourcePose().x(), request.sourcePose().y(), request.sourcePose().z()), velocity, new Vec3d(look.x, look.y, look.z), front);
        Vec3d arrival = actual.outPoint(preparation.destination.getFrame(), preparation.destination.getOrigin());
        return residentArrival(route, player, arrival)
            && destinationMatches(travel.player(), player, preparation.source, preparation.destination, actual)
            ? actual : null;
    }

    static ClientPreparedTravelServer.SeamlessAuthority seamlessAuthority(ServerPlayer player, long tick, long cooldownMillis) {
        return new ClientPreparedTravelServer.SeamlessAuthority(((SeamlessListenerAccess) player.connection).wormholesAwaitingPosition() != null,
            player.isChangingDimension(), tick, cooldownMillis);
    }

    static TravelMessage.TravelPose seamlessPose(TravelMessage.TravelPose arrival, TravelMessage.TravelCross cross, PlaneCrossing crossing,
                                                 TravelMessage.ArrivalRules rules, Frame destination, Vec3d destinationOrigin) {
        OpticTransform toward = crossing.toward(destination, destinationOrigin);
        TravelMessage.TravelPose source = cross.sourcePose();
        Vec3d position = new Vec3d(source.x(), source.y(), source.z());
        Pose crossed = PoseTransform.apply(new Pose(position, position, position, crossing.velocity(), source.yaw(), source.pitch(),
            source.yaw(), source.pitch(), source.yaw(), source.yaw(), source.yaw(), source.yaw()), toward);
        Frame view = crossing.frame();
        Frame exit = new Frame(toward.face(view.getNormal()), toward.face(view.getRight()), toward.face(view.getUp())).view(crossing.frontSide());
        Pose arrived = PoseTransform.arrive(crossed, crossing, exit, rules.orientation(), rules.gravityFlip(), rules.momentum(),
            rules.momentum().maxSpeed());
        return new TravelMessage.TravelPose(arrival.x(), arrival.y(), arrival.z(), arrived.yaw(), arrived.pitch());
    }

    static boolean residentArrival(RemoteRoute route, ServerPlayer player, Vec3d arrival) {
        int chunkX = arrival.getBlockX() >> 4;
        int chunkZ = arrival.getBlockZ() >> 4;
        return route.resident() ? route.stream().delivered(ChunkPos.pack(chunkX, chunkZ))
            : player.level().getChunkSource().chunkMap.isChunkTracked(player, chunkX, chunkZ);
    }

    boolean destinationMatches(MinecraftClientViewPeer peer, ServerPlayer player, MinecraftPortal source, MinecraftPortal destination,
                               PlaneCrossing crossing) {
        if (peer.door(source.getId()) == source) {
            return true;
        }
        NetworkMember selected = runtime.portals().resolveDestination(source, player, crossing);
        return selected != null && selected.isLocal() && selected.portalId().equals(destination.getId());
    }

    private RemoteRoutes.Return returnRoute(ClientViewTravel<MinecraftClientViewPeer> travel, ServerPlayer player, Preparation preparation,
                                            int handle) {
        MinecraftPortal arrival = preparation.destination;
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

    private TravelMessage.ArrivalRules rules(MinecraftClientViewPeer peer, MinecraftPortal source) {
        return peer.door(source.getId()) == source ? runtime.portals().doorArrivalRules() : runtime.portals().arrivalRules(source);
    }

    private MinecraftPortal nearest(ClientViewTravel<MinecraftClientViewPeer> travel, ServerPlayer player) {
        interested.clear();
        portals.interested(travel.player(), interested);
        MinecraftPortal nearest = null;
        double distance = Double.POSITIVE_INFINITY;
        Vec3d feet = new Vec3d(player.getX(), player.getY(), player.getZ());
        for (UUID id : interested) {
            MinecraftPortal source = portals.portal(travel.player(), id);
            if (source == null || source.isMirrorMode() || !source.isOpen()) {
                continue;
            }
            MinecraftPortal destination = travel.player().portals().projectionDestination(source);
            ServerLevel world = destination == null ? null : runtime.portals().resolveLevel(destination);
            if (world == null || !eligible(travel.player(), player, source, destination)) {
                continue;
            }
            double candidate = source.getOrigin().distance(feet);
            if (candidate < distance) {
                distance = candidate;
                nearest = source;
            }
        }
        return nearest;
    }

    boolean eligible(MinecraftClientViewPeer peer, ServerPlayer player, MinecraftPortal source, MinecraftPortal destination) {
        if (peer.door(source.getId()) == source) {
            return destination != null && runtime.doors().canPrepare(player, source.getId(), destination.getId());
        }
        return runtime.portals().get(source.getId()) == source
            && runtime.portals().canDepart(player, source) && runtime.portals().canArrive(player, destination);
    }

    boolean dispatchCross(MinecraftClientViewPeer peer, ServerPlayer player, MinecraftPortal source, MinecraftPortal destination,
                          int kind, PlaneCrossing crossing) {
        boolean door = peer.door(source.getId()) == source;
        if (door != (kind == ApertureKind.DOOR) || !eligible(peer, player, source, destination)) {
            return false;
        }
        return door ? runtime.doors().crossPrepared(player, source.getId(), crossing)
            : runtime.portals().crossPrepared(player, source.getId(), destination, crossing);
    }

    private Preparation create(ClientViewTravel<MinecraftClientViewPeer> travel, ServerPlayer player,
                               MinecraftPortal source, MinecraftPortal destination, ServerLevel world,
                               Vec3d feet, long route, int radius) {
        ApertureDescriptor geometry = travel.travelGeometry(source.getId());
        MinecraftClientViewScene.Destination mapped = geometry == null ? null
            : portals.scene().destination(travel.player(), source.getId(), geometry.frontSide());
        if (mapped == null || geometry.mirror()) {
            return null;
        }
        TravelMessage.TravelWorld metadata = RemoteRoutes.travelWorld(world).orElse(null);
        if (metadata == null) {
            return null;
        }
        List<TravelMessage.TravelCoordinate> coordinates = ClientTravelWindow.coordinates(feet.getBlockX() >> 4, feet.getBlockZ() >> 4, radius);
        Vec3d eye = feet.add(new Vec3d(0, player.getEyeHeight(), 0));
        TravelMessage.TravelBegin begin = new TravelMessage.TravelBegin(UUID.randomUUID(), ++generation, source.getId(),
            player.level().dimension().identifier().toString(), geometry, mapped.frame().transform(), metadata,
            new TravelMessage.TravelPose(feet.x(), feet.y(), feet.z(), player.getYRot(), player.getXRot()), coordinates,
            MinecraftPortalEnvironment.capture(world, eye, OpticTransform.IDENTITY, world.isFlat()),
            TravelMessage.MAX_TRAVEL_EXPIRY_MILLIS, rules(travel.player(), source), false, 0, false);
        travel.server().begin(begin, System.currentTimeMillis());
        travel.server().reuseSelected(travel.preparedTravelCacheSelected());
        travel.server().watchWorld(runtime.projections().changes(), MinecraftProjectionWorldView.worldId(world));
        Preparation preparation = new Preparation(new PreparationOptions(source, destination, world, route, coordinates));
        preparation.begin = begin;
        return preparation;
    }

    private void validate(ClientViewTravel<MinecraftClientViewPeer> travel, Preparation preparation) {
        UUID worldId = MinecraftProjectionWorldView.worldId(preparation.world);
        for (Map.Entry<TravelMessage.TravelCoordinate, Captured> entry : preparation.captured.entrySet()) {
            TravelMessage.TravelCoordinate coordinate = entry.getKey();
            Captured captured = entry.getValue();
            if (preparation.world.getChunkSource().getChunkNow(coordinate.x(), coordinate.z()) != captured.chunk()) {
                travel.server().unavailable(coordinate);
            } else if (runtime.projections().changes().dirtySince(worldId, coordinate.x(), coordinate.z(), coordinate.x(), coordinate.z(), captured.stamp())) {
                travel.server().invalidate(coordinate);
            }
        }
    }

    private void discard(ClientViewTravel<MinecraftClientViewPeer> travel) {
        complete(travel.playerId());
        travel.cancelTravel();
    }

    private boolean landingReady(ClientViewTravel<MinecraftClientViewPeer> travel,
                                 ServerPlayer player, LandingRoute route) {
        UUID playerId = player.getUUID();
        LandingWarmup landing = landings.get(playerId);
        if (landing != null && !landing.route().equals(route)) {
            discard(travel);
            landing = null;
        }
        if (landing == null && route.world().getChunkSource().getChunkNow(route.chunkX(), route.chunkZ()) != null) {
            return true;
        }
        if (landing == null) {
            discard(travel);
            ChunkLease lease = runtime.leases().retain(route.world(), MinecraftProjectionWorldView.worldId(route.world()),
                route.chunkX(), route.chunkZ());
            landing = new LandingWarmup(route, lease, System.currentTimeMillis() + TravelMessage.MAX_TRAVEL_EXPIRY_MILLIS);
            landings.put(playerId, landing);
        }
        if (!landing.lease().isValid() || System.currentTimeMillis() >= landing.deadline()) {
            LOGGER.warn("Could not warm portal {} arrival chunk {}, {} in {} for {}", route.source().getId(),
                route.chunkX(), route.chunkZ(), route.world().dimension().identifier(), playerId);
            retryAfter.put(playerId, System.currentTimeMillis() + 30_000L);
            discard(travel);
            return false;
        }
        if (!landing.lease().ready().isDone()) {
            return false;
        }
        try {
            if (Boolean.TRUE.equals(landing.lease().ready().getNow(false))
                && route.world().getChunkSource().getChunkNow(route.chunkX(), route.chunkZ()) != null) {
                return true;
            }
        } catch (CompletionException failure) {
            LOGGER.error("Could not warm portal {} arrival chunk {}, {} in {} for {}", route.source().getId(),
                route.chunkX(), route.chunkZ(), route.world().dimension().identifier(), playerId, failure);
        }
        retryAfter.put(playerId, System.currentTimeMillis() + 30_000L);
        discard(travel);
        return false;
    }

    private static Vec3d mappedCrossing(ServerPlayer player, MinecraftPortal source, MinecraftPortal destination) {
        return PlaneCrossing.planePoint(source.getFrame(), source.getOrigin(), new Vec3d(player.getX(), player.getY(), player.getZ()),
            destination.getFrame(), destination.getOrigin());
    }

    private static byte[] encode(ServerLevel world, LevelChunk chunk) {
        ClientboundLevelChunkWithLightPacket packet = new ClientboundLevelChunkWithLightPacket(chunk, world.getLightEngine(), null, null);
        return MinecraftChunkPacketEncoding.encode(world.registryAccess(), packet);
    }

    static final class CaptureBudget {
        private final boolean reusable;
        private final long deadline;
        private int columns;
        private int bytes;
        private int examined;
        private int leaseRequests;

        CaptureBudget(boolean reusable, long started) {
            this.reusable = reusable;
            this.deadline = started + CAPTURE_NANOS;
        }

        boolean allows(long now) {
            return columns < MAX_CAPTURE_COLUMNS && (examined++ == 0 || now < deadline)
                && (reusable || columns == 0 || bytes < SEND_BYTES_PER_TICK);
        }

        void captured(int length) {
            columns++;
            bytes += length;
        }

        boolean requestLease() {
            if (leaseRequests >= MAX_LEASE_REQUESTS) {
                return false;
            }
            leaseRequests++;
            return true;
        }
    }

    private record Captured(LevelChunk chunk, long stamp) {
    }

    private record LandingRoute(MinecraftPortal source, MinecraftPortal destination, ServerLevel world,
                                long identity, int chunkX, int chunkZ) {
    }

    private record LandingWarmup(LandingRoute route, ChunkLease lease, long deadline) {
    }

    private record PreparationOptions(MinecraftPortal source, MinecraftPortal destination, ServerLevel world,
                                      long route, List<TravelMessage.TravelCoordinate> coordinates) {
    }

    private static final class Preparation {
        private TravelMessage.TravelBegin begin;
        private final MinecraftPortal source;
        private final MinecraftPortal destination;
        private final ServerLevel world;
        private final long route;
        private final List<TravelMessage.TravelCoordinate> coordinates;
        private final Map<TravelMessage.TravelCoordinate, Captured> captured = new HashMap<>();
        private final Map<TravelMessage.TravelCoordinate, ChunkLease> leases = new HashMap<>();
        private boolean committed;
        private boolean seamless;
        private int handle;
        private long marked = Long.MIN_VALUE;
        private RouteWindow core;
        private TravelMessage.TravelCross cross;
        private PlaneCrossing crossing;

        private Preparation(PreparationOptions options) {
            this.source = options.source();
            this.destination = options.destination();
            this.world = options.world();
            this.route = options.route();
            this.coordinates = options.coordinates();
        }

        private boolean contains(Vec3d point) {
            int x = point.getBlockX() >> 4;
            int z = point.getBlockZ() >> 4;
            return coordinates.contains(new TravelMessage.TravelCoordinate(x - 1, z - 1))
                && coordinates.contains(new TravelMessage.TravelCoordinate(x + 1, z + 1));
        }

        private void close() {
            for (ChunkLease lease : leases.values()) {
                lease.close();
            }
            leases.clear();
            captured.clear();
        }
    }
}
