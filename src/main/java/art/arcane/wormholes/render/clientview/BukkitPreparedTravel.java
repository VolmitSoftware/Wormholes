package art.arcane.wormholes.render.clientview;

import art.arcane.volmlib.nativelib.chunk.ChunkPacketAccess;
import art.arcane.volmlib.nativelib.chunk.ChunkPacketSnapshot;
import art.arcane.volmlib.nativelib.chunk.ChunkPosition;
import art.arcane.volmlib.nativelib.chunk.ChunkWorldContext;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.platform.WormholesPlatform;
import art.arcane.optics.plate.ChunkLease;
import art.arcane.wormholes.chunk.BukkitChunkLeaseProvider;
import art.arcane.wormholes.chunk.presend.ChunkCoordinate;
import art.arcane.wormholes.chunk.presend.ChunkPreSendPlanner;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.stream.ProjectionEnvironment;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.optics.crossing.PlaneCrossing;
import art.arcane.wormholes.util.BukkitGeometry;
import art.arcane.wormholes.door.view.DoorProjectionAdapter;
import org.bukkit.event.player.PlayerTeleportEvent;
import art.arcane.wormholes.render.ClientViewPortalSource;
import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.frame.ViewWindow;
import art.arcane.wormholes.render.client.session.ClientPreparedTravelServer;
import art.arcane.wormholes.render.client.session.ClientViewTravel;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import art.arcane.wormholes.network.client.TravelMessage;

final class BukkitPreparedTravel implements AutoCloseable {
    private static final int MAX_CAPTURE_COLUMNS = 4;
    private static final long CAPTURE_NANOS = 2_000_000L;
    private final Plugin plugin;
    private final ChunkPacketAccess packets;
    private final AtomicLong generation = new AtomicLong();
    private final Map<UUID, Preparation> preparations = new ConcurrentHashMap<>();
    private final Map<UUID, Long> retryAfter = new ConcurrentHashMap<>();

    BukkitPreparedTravel(Plugin plugin, ChunkPacketAccess packets) {
        this.plugin = plugin;
        this.packets = packets;
    }

    void tick(ClientViewTravel<ClientViewObserver> travel, Player player, List<ILocalPortal> interested) {
        if (!travel.preparedTravelSelected()) {
            discard(travel);
            return;
        }
        Optional<TravelMessage.TravelCross> crossing = travel.server().takeCross();
        if (crossing.isPresent()) {
            cross(travel, player, interested, crossing.get());
            return;
        }
        Long retry = retryAfter.get(player.getUniqueId());
        if (retry != null && System.currentTimeMillis() < retry) {
            return;
        }
        ILocalPortal source = nearest(travel, player, interested);
        ClientViewPortalSource route = source == null ? null : travel.player().source(source.getId());
        World world = route == null ? null : route.destinationWorld();
        ViewWindow frame = route == null ? null : route.transformFrame();
        if (world == null || frame == null || frame.mirror()
            || route.destinationAnchor() == null || route.destinationAnchor().isRemote()
            || route.destinationAnchor() instanceof ILocalPortal anchor && !anchor.canArrive(player)) {
            discard(travel);
            return;
        }
        Location location = player.getLocation();
        Vec3d feet = PlaneCrossing.planePoint(frame.localFrame(), frame.localOrigin(), new Vec3d(location.getX(), location.getY(), location.getZ()),
            frame.remoteFrame(), frame.remoteOrigin());
        Preparation preparation = preparations.get(player.getUniqueId());
        if (preparation != null && preparation.failed) {
            retryAfter.put(player.getUniqueId(), System.currentTimeMillis() + 30_000L);
            discard(travel);
            return;
        }
        if (preparation == null || preparation.world != world || !preparation.source.equals(source.getId())
            || !preparation.destination.equals(route.destinationAnchor().getId()) || !preparation.contains(feet) || !preparation.live.get()) {
            discard(travel);
            Optional<ChunkWorldContext> sourceContext = packets.context(player.getWorld());
            if (sourceContext.isEmpty()) {
                return;
            }
            ApertureDescriptor geometry = travel.travelGeometry(source.getId());
            if (geometry == null || geometry.mirror()) {
                return;
            }
            preparation = new Preparation(new PreparationOptions(geometry, frame.transform().normalized(), source.getId(), route.destinationAnchor().getId(), world, feet,
                new TravelMessage.TravelPose(feet.x(), feet.y(), feet.z(), location.getYaw(), location.getPitch()),
                player.getEyeHeight(), sourceContext.get().dimension(), generation.incrementAndGet()));
            preparations.put(player.getUniqueId(), preparation);
        }
        if (!drain(travel, preparation)) {
            return;
        }
        if (preparation.committed) {
            return;
        }
        if (preparation.begin == null) {
            scheduleBegin(preparation);
            return;
        }
        if (travel.server().preparing().isEmpty()) {
            travel.server().begin(preparation.begin, System.currentTimeMillis());
            travel.server().reuseSelected(travel.preparedTravelCacheSelected());
            travel.server().watchWorld(Wormholes.projectionChangeTracker, preparation.world.getUID());
        }
        validate(travel, preparation);
        scheduleColumns(travel.server(), preparation);
        travel.server().tick(System.currentTimeMillis(), 128 * 1024, travel::sendTravel);
        if (travel.server().preparing().isEmpty()) {
            discard(travel);
        }
    }

    TravelMessage.TravelCommit commit(ClientViewTravel<ClientViewObserver> travel,
                                         Player player, UUID source, Location target, Vec3d velocity) {
        Preparation preparation = preparations.get(player.getUniqueId());
        if (preparation == null || preparation.begin == null || preparation.world != target.getWorld() || !travel.server().crossing()) {
            return null;
        }
        if (!drain(travel, preparation)) {
            return null;
        }
        validate(travel, preparation);
        Optional<ChunkWorldContext> origin = packets.context(player.getWorld());
        if (origin.isEmpty()) {
            return null;
        }
        TravelMessage.TravelCommit commit = travel.server().commit(new ClientPreparedTravelServer.Commit(source,
            origin.get().dimension(), preparation.begin.world().dimension(),
            new TravelMessage.TravelPose(target.getX(), target.getY(), target.getZ(), target.getYaw(), target.getPitch()),
            velocity, System.currentTimeMillis())).orElse(null);
        if (commit != null && travel.sendTravel(commit)) {
            preparation.committed = true;
            return commit;
        }
        if (commit != null) {
            travel.sendTravel(new TravelMessage.TravelCancel(commit.token(), commit.generation()));
        }
        return null;
    }

    private void cross(ClientViewTravel<ClientViewObserver> travel, Player player,
                       List<ILocalPortal> interested, TravelMessage.TravelCross request) {
        Preparation preparation = preparations.get(player.getUniqueId());
        ILocalPortal source = null;
        if (preparation != null) {
            for (ILocalPortal candidate : interested) {
                if (candidate.getId().equals(preparation.source)) {
                    source = candidate;
                    break;
                }
            }
        }
        ClientViewPortalSource route = source == null ? null : travel.player().source(source.getId());
        ApertureDescriptor geometry = source == null ? null : travel.travelGeometry(source.getId());
        Optional<ChunkWorldContext> context = packets.context(player.getWorld());
        Location location = player.getLocation();
        Vec3d feet = BukkitGeometry.vector(location);
        Vec3d velocity = BukkitGeometry.vector(Wormholes.traversableManager.getVelocity(player));
        boolean allowed = preparation != null && source != null && geometry != null && context.isPresent()
            && !preparation.committed && preparation.live.get() && source.isOpen() && !source.isMirrorMode()
            && source.getStructure().getWorld() == player.getWorld() && source.canDepart(player)
            && route != null && route.destinationWorld() == preparation.world && route.destinationAnchor() != null
            && route.destinationAnchor().getId().equals(preparation.destination) && route.transformFrame() != null
            && route.transformFrame().transform().normalized().equals(preparation.destinationToSource)
            && route.destinationAnchor() instanceof ILocalPortal anchor && anchor.canArrive(player)
            && player.getVehicle() == null && player.getPassengers().isEmpty();
        if (allowed) {
            allowed = drain(travel, preparation);
            validate(travel, preparation);
            allowed &= travel.server().validCross(request, new ClientPreparedTravelServer.Authority(context.get().dimension(), geometry,
                new TravelMessage.TravelPose(feet.x(), feet.y(), feet.z(), location.getYaw(), location.getPitch()), velocity,
                player.getEyeHeight()), System.currentTimeMillis());
        }
        if (allowed) {
            boolean front = geometry.signedDistance(request.previousEye().x(), request.previousEye().y(), request.previousEye().z()) > 0.0D;
            Vec3d admittedFeet = new Vec3d(request.sourcePose().x(), request.sourcePose().y(), request.sourcePose().z());
            Location admitted = new Location(player.getWorld(), admittedFeet.x(), admittedFeet.y(), admittedFeet.z(),
                request.sourcePose().yaw(), request.sourcePose().pitch());
            PlaneCrossing actual = new PlaneCrossing(source.getFrame().view(front), source.getOrigin(), admittedFeet, velocity,
                BukkitGeometry.vector(admitted.getDirection()), front);
            allowed = source instanceof LocalPortal local ? local.crossPrepared(player, actual)
                : source instanceof DoorProjectionAdapter && Wormholes.dimensionalDoorManager != null
                    && Wormholes.dimensionalDoorManager.crossPrepared(player, source.getId(), actual);
        }
        if (!allowed) {
            discard(travel);
            WormholesPlatform.teleport(plugin, player, location, PlayerTeleportEvent.TeleportCause.PLUGIN).whenComplete((moved, failure) -> {
                if (failure != null) {
                    plugin.getLogger().log(Level.WARNING, "Could not correct rejected portal crossing for " + player.getUniqueId(), failure);
                }
            });
        }
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
        Preparation preparation = preparations.remove(player);
        if (preparation != null) {
            preparation.close();
        }
    }

    @Override
    public void close() {
        for (Preparation preparation : preparations.values()) {
            preparation.close();
        }
        preparations.clear();
        retryAfter.clear();
    }

    private boolean drain(ClientViewTravel<ClientViewObserver> travel, Preparation preparation) {
        if (!preparation.live.get()) {
            discard(travel);
            return false;
        }
        Snapshot snapshot;
        while ((snapshot = preparation.completed.poll()) != null) {
            if (travel.server().column(snapshot.coordinate(), snapshot.revision(), snapshot.packet().payload())) {
                preparation.stamps.put(snapshot.coordinate(), snapshot.stamp());
            } else {
                discard(travel);
                return false;
            }
        }
        return preparation.live.get();
    }

    private void validate(ClientViewTravel<ClientViewObserver> travel, Preparation preparation) {
        for (Map.Entry<TravelMessage.TravelCoordinate, Long> entry : preparation.stamps.entrySet()) {
            TravelMessage.TravelCoordinate coordinate = entry.getKey();
            ChunkLease lease = preparation.leases.get(coordinate);
            if (lease == null || !lease.isValid()) {
                travel.server().unavailable(coordinate);
            } else if (Wormholes.projectionChangeTracker.dirtySince(preparation.world.getUID(), coordinate.x(), coordinate.z(),
                coordinate.x(), coordinate.z(), entry.getValue())) {
                travel.server().invalidate(coordinate);
            }
        }
    }

    private void scheduleBegin(Preparation preparation) {
        if (!preparation.busy.compareAndSet(false, true)) {
            return;
        }
        int x = preparation.feet.getBlockX() >> 4;
        int z = preparation.feet.getBlockZ() >> 4;
        preparation.retain(new TravelMessage.TravelCoordinate(x, z));
        if (!FoliaScheduler.runRegion(plugin, preparation.world, x, z, () -> captureBegin(preparation))) {
            preparation.busy.set(false);
        }
    }

    private void captureBegin(Preparation preparation) {
        try {
            if (!preparation.live.get() || !preparation.world.isChunkLoaded(preparation.feet.getBlockX() >> 4,
                preparation.feet.getBlockZ() >> 4)) {
                return;
            }
            Optional<ChunkWorldContext> context = packets.context(preparation.world);
            if (context.isEmpty()) {
                return;
            }
            ChunkWorldContext metadata = context.get();
            Vec3d eye = preparation.feet.add(new Vec3d(0, preparation.eyeHeight, 0));
            ProjectionEnvironment environment = authoritativeEnvironment(BukkitPortalEnvironment.capture(preparation.world, eye,
                OpticTransform.IDENTITY), metadata);
            preparation.begin = new TravelMessage.TravelBegin(UUID.randomUUID(), preparation.generation, preparation.source,
                preparation.sourceWorld, preparation.sourceGeometry, preparation.destinationToSource, new TravelMessage.TravelWorld(metadata.dimension(), metadata.dimensionType(), metadata.seed(),
                    metadata.debug(), metadata.flat(), metadata.seaLevel(), metadata.minY(), metadata.height()), preparation.arrival,
                preparation.coordinates, environment, TravelMessage.MAX_TRAVEL_EXPIRY_MILLIS, TravelMessage.ArrivalRules.FRAME, false, 0, false);
        } catch (RuntimeException failure) {
            plugin.getLogger().log(Level.SEVERE, "Could not prepare portal destination " + preparation.destination, failure);
            preparation.failed = true;
            preparation.close();
        } finally {
            preparation.busy.set(false);
        }
    }

    private void scheduleColumns(ClientPreparedTravelServer travel, Preparation preparation) {
        scheduleColumns(travel, preparation, System.nanoTime() + CAPTURE_NANOS);
    }

    private void scheduleColumns(ClientPreparedTravelServer travel, Preparation preparation, long deadline) {
        if (!preparation.live.get()) {
            return;
        }
        int scheduled = 0;
        for (int checked = 0; checked < preparation.coordinates.size(); checked++) {
            if (scheduled >= MAX_CAPTURE_COLUMNS || preparation.capturing.size() >= MAX_CAPTURE_COLUMNS
                || scheduled > 0 && System.nanoTime() >= deadline) {
                break;
            }
            TravelMessage.TravelCoordinate coordinate = travel.nextCapture();
            if (coordinate == null) {
                break;
            }
            if (!preparation.capturing.contains(coordinate)) {
                scheduleColumn(preparation, coordinate, travel.nextRevision(coordinate));
                scheduled++;
            }
        }
    }

    private void scheduleColumn(Preparation preparation, TravelMessage.TravelCoordinate coordinate, int revision) {
        if (!preparation.live.get() || !preparation.capturing.add(coordinate)) {
            return;
        }
        preparation.retain(coordinate);
        if (!FoliaScheduler.runRegion(plugin, preparation.world, coordinate.x(), coordinate.z(),
            () -> captureColumn(preparation, coordinate, revision))) {
            preparation.capturing.remove(coordinate);
        }
    }

    private void captureColumn(Preparation preparation, TravelMessage.TravelCoordinate coordinate, int revision) {
        try {
            if (!preparation.live.get()) {
                return;
            }
            Optional<ChunkPacketSnapshot> snapshot = packets.snapshot(preparation.world, new ChunkPosition(coordinate.x(), coordinate.z()));
            if (snapshot.isPresent()) {
                preparation.completed.add(new Snapshot(coordinate, revision, snapshot.get(), Wormholes.projectionChangeTracker.currentVersion()));
            }
        } catch (RuntimeException failure) {
            plugin.getLogger().log(Level.SEVERE, "Could not capture portal arrival chunk " + coordinate, failure);
            preparation.failed = true;
            preparation.close();
        } finally {
            preparation.capturing.remove(coordinate);
        }
    }

    private void discard(ClientViewTravel<ClientViewObserver> travel) {
        complete(travel.playerId());
        travel.cancelTravel();
    }

    private static ILocalPortal nearest(ClientViewTravel<ClientViewObserver> travel,
                                       Player player, List<ILocalPortal> interested) {
        Location point = player.getLocation();
        Vec3d feet = new Vec3d(point.getX(), point.getY(), point.getZ());
        ILocalPortal nearest = null;
        double distance = Double.POSITIVE_INFINITY;
        for (ILocalPortal portal : interested) {
            if (portal.isMirrorMode() || !portal.isOpen() || !portal.canDepart(player)) {
                continue;
            }
            ClientViewPortalSource route = travel.player().source(portal.getId());
            if (route == null || route.destinationWorld() == null
                || route.destinationAnchor() == null || route.destinationAnchor().isRemote()) {
                continue;
            }
            double candidate = portal.getOrigin().distance(feet);
            if (candidate < distance) {
                nearest = portal;
                distance = candidate;
            }
        }
        return nearest;
    }

    private static ProjectionEnvironment authoritativeEnvironment(ProjectionEnvironment environment, ChunkWorldContext metadata) {
        ProjectionEnvironment.World world = environment.world();
        return new ProjectionEnvironment(metadata.gameTime(), environment.sky(), environment.fog(), environment.lighting(),
            environment.clouds(), environment.transform(), environment.dimension(), new ProjectionEnvironment.World(metadata.dimension(),
                metadata.clockTime(), world.biomeKey(), metadata.seaLevel(), world.blockLight(), world.skyLight(), world.logicalHeight(),
                world.hasCeiling(), world.ambientLight(), world.eyeMedium(), world.hasFixedTime()));
    }

    private record Snapshot(TravelMessage.TravelCoordinate coordinate, int revision, ChunkPacketSnapshot packet, long stamp) {
    }

    private record PreparationOptions(ApertureDescriptor sourceGeometry, OpticTransform destinationToSource, UUID source, UUID destination, World world, Vec3d feet,
                                      TravelMessage.TravelPose arrival, double eyeHeight, String sourceWorld, long generation) {
    }

    private static final class Preparation implements AutoCloseable {
        private final ApertureDescriptor sourceGeometry;
        private final OpticTransform destinationToSource;
        private final UUID source;
        private final UUID destination;
        private final World world;
        private final Vec3d feet;
        private final TravelMessage.TravelPose arrival;
        private final double eyeHeight;
        private final String sourceWorld;
        private final long generation;
        private final List<TravelMessage.TravelCoordinate> coordinates = new ArrayList<>(49);
        private final Map<TravelMessage.TravelCoordinate, ChunkLease> leases = new ConcurrentHashMap<>();
        private final Map<TravelMessage.TravelCoordinate, Long> stamps = new HashMap<>();
        private final Set<TravelMessage.TravelCoordinate> capturing = ConcurrentHashMap.newKeySet();
        private final ConcurrentLinkedQueue<Snapshot> completed = new ConcurrentLinkedQueue<>();
        private final AtomicBoolean busy = new AtomicBoolean();
        private final AtomicBoolean live = new AtomicBoolean(true);
        private volatile TravelMessage.TravelBegin begin;
        private boolean committed;
        private volatile boolean failed;

        private Preparation(PreparationOptions options) {
            this.sourceGeometry = options.sourceGeometry();
            this.destinationToSource = options.destinationToSource();
            this.source = options.source();
            this.destination = options.destination();
            this.world = options.world();
            this.feet = options.feet();
            this.arrival = options.arrival();
            this.eyeHeight = options.eyeHeight();
            this.sourceWorld = options.sourceWorld();
            this.generation = options.generation();
            for (ChunkCoordinate coordinate : ChunkPreSendPlanner.ring(feet.getBlockX() >> 4, feet.getBlockZ() >> 4, 3)) {
                coordinates.add(new TravelMessage.TravelCoordinate(coordinate.x(), coordinate.z()));
            }
        }

        private boolean contains(Vec3d point) {
            int x = point.getBlockX() >> 4;
            int z = point.getBlockZ() >> 4;
            return coordinates.contains(new TravelMessage.TravelCoordinate(x - 1, z - 1))
                && coordinates.contains(new TravelMessage.TravelCoordinate(x + 1, z + 1));
        }

        private synchronized void retain(TravelMessage.TravelCoordinate coordinate) {
            if (!live.get()) {
                return;
            }
            leases.computeIfAbsent(coordinate, position -> BukkitChunkLeaseProvider.registry().retain(world, world.getUID(), position.x(), position.z()));
        }

        @Override
        public synchronized void close() {
            live.set(false);
            for (ChunkLease lease : leases.values()) {
                lease.close();
            }
            leases.clear();
            completed.clear();
            capturing.clear();
        }
    }
}
