package art.arcane.wormholes.modded.clientview;

import art.arcane.wormholes.chunk.presend.ChunkCoordinate;
import art.arcane.wormholes.chunk.ChunkLease;
import art.arcane.wormholes.chunk.presend.ChunkPreSendPlanner;
import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.portal.PortalCrossing;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.MinecraftChunkPacketEncoding;
import art.arcane.wormholes.modded.MinecraftProjectionWorldView;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.network.client.ClientViewEnvironment;
import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.wormholes.network.client.ClientViewProtocol;
import art.arcane.wormholes.render.client.ClientPortalGeometry;
import art.arcane.wormholes.render.client.session.ClientPreparedTravelServer;
import art.arcane.wormholes.render.client.ClientViewEnvironmentTransform;
import art.arcane.wormholes.render.client.session.ClientViewServerSession;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

final class MinecraftPreparedTravel {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");
    private static final long CAPTURE_NANOS = 2_000_000L;
    private static final int MAX_CAPTURE_COLUMNS = 16;
    private static final int SEND_BYTES_PER_TICK = 128 * 1024;
    private final WormholesModRuntime runtime;
    private final MinecraftClientViewPortalAccess portals;
    private final Map<UUID, Preparation> preparations = new HashMap<>();
    private final Map<UUID, Long> retryAfter = new HashMap<>();
    private final List<UUID> interested = new ArrayList<>();
    private long generation;

    MinecraftPreparedTravel(WormholesModRuntime runtime, MinecraftClientViewPortalAccess portals) {
        this.runtime = runtime;
        this.portals = portals;
    }

    void tick(ClientViewServerSession<MinecraftClientViewPeer, BlockState> session, ServerPlayer player) {
        if (!session.preparedTravelSelected()) {
            discard(session);
            return;
        }
        Optional<ClientViewMessage.TravelCross> crossing = session.travel().takeCross();
        if (crossing.isPresent()) {
            cross(session, player, crossing.get());
            return;
        }
        Long retry = retryAfter.get(player.getUUID());
        if (retry != null && System.currentTimeMillis() < retry) {
            return;
        }
        MinecraftPortal source = nearest(session, player);
        if (source == null) {
            discard(session);
            return;
        }
        MinecraftPortal destination = session.player().portals().projectionDestination(source);
        ServerLevel world = destination == null ? null : runtime.portals().resolveLevel(destination);
        if (world == null || !eligible(session.player(), player, source, destination)) {
            discard(session);
            return;
        }
        Preparation preparation = preparations.get(player.getUUID());
        GeometryVector feet = mappedCrossing(player, source, destination);
        if (world.getChunkSource().getChunkNow(feet.getBlockX() >> 4, feet.getBlockZ() >> 4) == null) {
            discard(session);
            return;
        }
        long route = session.player().portals().routeIdentity(source);
        if (preparation == null || preparation.source != source || preparation.destination != destination
            || preparation.world != world || preparation.route != route || !preparation.contains(feet)
            || session.travel().preparing().isEmpty()) {
            discard(session);
            preparation = create(session, player, source, destination, world, feet, route);
            if (preparation == null) {
                return;
            }
            preparations.put(player.getUUID(), preparation);
        }
        validate(session, preparation);
        if (preparation.committed) {
            return;
        }
        CaptureBudget captureBudget = new CaptureBudget(session.preparedTravelCacheSelected(), System.nanoTime());
        for (int checked = 0; checked < preparation.coordinates.size(); checked++) {
            if (!captureBudget.allows(System.nanoTime())) {
                break;
            }
            ClientViewMessage.TravelCoordinate coordinate = session.travel().nextCapture();
            if (coordinate == null) {
                break;
            }
            LevelChunk chunk = world.getChunkSource().getChunkNow(coordinate.x(), coordinate.z());
            preparation.leases.computeIfAbsent(coordinate, position -> runtime.leases().retain(world,
                MinecraftProjectionWorldView.worldId(world), position.x(), position.z()));
            if (chunk != null) {
                byte[] payload;
                try {
                    payload = encode(world, chunk);
                } catch (RuntimeException failure) {
                    LOGGER.error("Could not prepare portal {} arrival chunk {}, {} in {} for {}", source.getId(),
                        coordinate.x(), coordinate.z(), world.dimension().identifier(), player.getUUID(), failure);
                    retryAfter.put(player.getUUID(), System.currentTimeMillis() + 30_000L);
                    discard(session);
                    return;
                }
                if (!session.travel().column(coordinate, session.travel().nextRevision(coordinate), payload)) {
                    discard(session);
                    return;
                }
                preparation.captured.put(coordinate, new Captured(chunk, runtime.projections().changes().currentVersion()));
                captureBudget.captured(payload.length);
            }
        }
        session.travel().tick(System.currentTimeMillis(), SEND_BYTES_PER_TICK, session::sendTravel);
    }

    Optional<ClientViewMessage.TravelCommit> commit(ClientViewServerSession<MinecraftClientViewPeer, BlockState> session,
                                                    ServerPlayer player, UUID source, ServerLevel world,
                                                    ClientViewMessage.TravelPose arrival, GeometryVector velocity) {
        Preparation preparation = preparations.get(player.getUUID());
        if (preparation == null || preparation.world != world || !session.travel().crossing()) {
            return Optional.empty();
        }
        validate(session, preparation);
        Optional<ClientViewMessage.TravelCommit> result = session.travel().commit(new ClientPreparedTravelServer.Commit(source,
            player.level().dimension().identifier().toString(), world.dimension().identifier().toString(), arrival, velocity,
            System.currentTimeMillis()));
        if (result.isPresent()) {
            preparation.committed = true;
        }
        return result;
    }

    private void cross(ClientViewServerSession<MinecraftClientViewPeer, BlockState> session, ServerPlayer player,
                       ClientViewMessage.TravelCross request) {
        Preparation preparation = preparations.get(player.getUUID());
        ClientPortalGeometry geometry = preparation == null ? null : session.travelGeometry(preparation.source.getId());
        GeometryVector feet = new GeometryVector(player.getX(), player.getY(), player.getZ());
        GeometryVector velocity = runtime.portals().observedVelocity(player);
        boolean allowed = preparation != null && geometry != null && !preparation.committed
            && player.level() == runtime.portals().resolveLevel(preparation.source)
            && session.player().portals().projectionDestination(preparation.source) == preparation.destination
            && session.player().portals().routeIdentity(preparation.source) == preparation.route
            && player.getVehicle() == null && player.getPassengers().isEmpty();
        if (allowed) {
            validate(session, preparation);
            allowed = session.travel().validCross(request, new ClientPreparedTravelServer.Authority(
                player.level().dimension().identifier().toString(), geometry,
                new ClientViewMessage.TravelPose(feet.x(), feet.y(), feet.z(), player.getYRot(), player.getXRot()),
                velocity, player.getEyeHeight()), System.currentTimeMillis());
        }
        if (allowed) {
            boolean front = geometry.signedDistance(request.previousEye().x(), request.previousEye().y(), request.previousEye().z()) > 0.0D;
            GeometryVector admittedFeet = new GeometryVector(request.sourcePose().x(), request.sourcePose().y(), request.sourcePose().z());
            Vec3 look = Vec3.directionFromRotation(request.sourcePose().pitch(), request.sourcePose().yaw());
            PortalCrossing actual = new PortalCrossing(preparation.source.getFrame().view(front), preparation.source.getOrigin(), admittedFeet,
                velocity, new GeometryVector(look.x, look.y, look.z), front);
            allowed = dispatchCross(session.player(), player, preparation.source, preparation.destination, geometry.kind(), actual);
        }
        if (!allowed) {
            discard(session);
            player.connection.teleport(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
        }
    }

    void clear() {
        for (Preparation preparation : preparations.values()) {
            preparation.close();
        }
        preparations.clear();
        retryAfter.clear();
    }

    void complete(UUID player, ClientViewMessage.TravelCommit commit) {
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
        Preparation preparation = preparations.remove(player);
        if (preparation != null) {
            preparation.close();
        }
    }

    private MinecraftPortal nearest(ClientViewServerSession<MinecraftClientViewPeer, BlockState> session, ServerPlayer player) {
        interested.clear();
        portals.interested(session.player(), interested);
        MinecraftPortal nearest = null;
        double distance = Double.POSITIVE_INFINITY;
        GeometryVector feet = new GeometryVector(player.getX(), player.getY(), player.getZ());
        for (UUID id : interested) {
            MinecraftPortal source = portals.portal(session.player(), id);
            if (source == null || source.isMirrorMode() || !source.isOpen()) {
                continue;
            }
            MinecraftPortal destination = session.player().portals().projectionDestination(source);
            ServerLevel world = destination == null ? null : runtime.portals().resolveLevel(destination);
            if (world == null || !eligible(session.player(), player, source, destination)) {
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
                          int kind, PortalCrossing crossing) {
        boolean door = peer.door(source.getId()) == source;
        if (door != (kind == ClientPortalGeometry.KIND_DOOR) || !eligible(peer, player, source, destination)) {
            return false;
        }
        return door ? runtime.doors().crossPrepared(player, source.getId(), crossing)
            : runtime.portals().crossPrepared(player, source.getId(), destination, crossing);
    }

    private Preparation create(ClientViewServerSession<MinecraftClientViewPeer, BlockState> session, ServerPlayer player,
                               MinecraftPortal source, MinecraftPortal destination, ServerLevel world,
                               GeometryVector feet, long route) {
        ClientPortalGeometry geometry = session.travelGeometry(source.getId());
        MinecraftClientViewScene.Destination mapped = geometry == null ? null
            : portals.scene().destination(session.player(), source.getId(), geometry.frontSide());
        if (mapped == null || geometry.mirror()) {
            return null;
        }
        int radius = 3;
        List<ClientViewMessage.TravelCoordinate> coordinates = new ArrayList<>((radius * 2 + 1) * (radius * 2 + 1));
        for (ChunkCoordinate coordinate : ChunkPreSendPlanner.ring(feet.getBlockX() >> 4, feet.getBlockZ() >> 4, radius)) {
            coordinates.add(new ClientViewMessage.TravelCoordinate(coordinate.x(), coordinate.z()));
        }
        ClientViewMessage.TravelWorld metadata = new ClientViewMessage.TravelWorld(world.dimension().identifier().toString(),
            world.dimensionTypeRegistration().unwrapKey().orElseThrow().identifier().toString(),
            BiomeManager.obfuscateSeed(world.getSeed()), world.isDebug(), world.isFlat(), world.getSeaLevel(), world.getMinY(), world.getHeight());
        GeometryVector eye = feet.add(new GeometryVector(0, player.getEyeHeight(), 0));
        ClientViewMessage.TravelBegin begin = new ClientViewMessage.TravelBegin(UUID.randomUUID(), ++generation, source.getId(),
            player.level().dimension().identifier().toString(), geometry, ClientViewEnvironmentTransform.of(mapped.frame()), metadata,
            new ClientViewMessage.TravelPose(feet.x(), feet.y(), feet.z(), player.getYRot(), player.getXRot()), coordinates,
            MinecraftPortalEnvironment.capture(world, eye, ClientViewEnvironment.Transform.IDENTITY, world.isFlat()),
            ClientViewProtocol.MAX_TRAVEL_EXPIRY_MILLIS);
        session.travel().begin(begin, System.currentTimeMillis());
        session.travel().reuseSelected(session.preparedTravelCacheSelected());
        session.travel().watchWorld(runtime.projections().changes(), MinecraftProjectionWorldView.worldId(world));
        Preparation preparation = new Preparation(new PreparationOptions(source, destination, world, route, coordinates));
        preparation.begin = begin;
        return preparation;
    }

    private void validate(ClientViewServerSession<MinecraftClientViewPeer, BlockState> session, Preparation preparation) {
        UUID worldId = MinecraftProjectionWorldView.worldId(preparation.world);
        for (Map.Entry<ClientViewMessage.TravelCoordinate, Captured> entry : preparation.captured.entrySet()) {
            ClientViewMessage.TravelCoordinate coordinate = entry.getKey();
            Captured captured = entry.getValue();
            if (preparation.world.getChunkSource().getChunkNow(coordinate.x(), coordinate.z()) != captured.chunk()) {
                session.travel().unavailable(coordinate);
            } else if (runtime.projections().changes().dirtySince(worldId, coordinate.x(), coordinate.z(), coordinate.x(), coordinate.z(), captured.stamp())) {
                session.travel().invalidate(coordinate);
            }
        }
    }

    private void discard(ClientViewServerSession<MinecraftClientViewPeer, BlockState> session) {
        complete(session.playerId());
        session.cancelTravel();
    }

    private static GeometryVector mappedCrossing(ServerPlayer player, MinecraftPortal source, MinecraftPortal destination) {
        return source.getFrame().transformCrossingPoint(new GeometryVector(player.getX(), player.getY(), player.getZ()), source.getOrigin(),
            destination.getOrigin(), destination.getFrame());
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
    }

    private record Captured(LevelChunk chunk, long stamp) {
    }

    private record PreparationOptions(MinecraftPortal source, MinecraftPortal destination, ServerLevel world,
                                      long route, List<ClientViewMessage.TravelCoordinate> coordinates) {
    }

    private static final class Preparation {
        private ClientViewMessage.TravelBegin begin;
        private final MinecraftPortal source;
        private final MinecraftPortal destination;
        private final ServerLevel world;
        private final long route;
        private final List<ClientViewMessage.TravelCoordinate> coordinates;
        private final Map<ClientViewMessage.TravelCoordinate, Captured> captured = new HashMap<>();
        private final Map<ClientViewMessage.TravelCoordinate, ChunkLease> leases = new HashMap<>();
        private boolean committed;

        private Preparation(PreparationOptions options) {
            this.source = options.source();
            this.destination = options.destination();
            this.world = options.world();
            this.route = options.route();
            this.coordinates = options.coordinates();
        }

        private boolean contains(GeometryVector point) {
            int x = point.getBlockX() >> 4;
            int z = point.getBlockZ() >> 4;
            return coordinates.contains(new ClientViewMessage.TravelCoordinate(x - 1, z - 1))
                && coordinates.contains(new ClientViewMessage.TravelCoordinate(x + 1, z + 1));
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
