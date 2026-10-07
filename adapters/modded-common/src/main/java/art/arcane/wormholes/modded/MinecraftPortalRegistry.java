package art.arcane.wormholes.modded;

import art.arcane.optics.crossing.ArrivalMomentum;
import art.arcane.optics.crossing.ArrivalOrientation;
import art.arcane.optics.math.Angles;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.api.traversal.TraversalKind;
import art.arcane.wormholes.api.traversal.TraversalRefundReason;
import java.util.Optional;

import art.arcane.wormholes.nexus.NetworkMember;
import art.arcane.wormholes.network.MinecraftGatewayPolicies;
import art.arcane.optics.plate.ChunkLease;
import art.arcane.wormholes.chunk.presend.ChunkPreSendTicket;
import art.arcane.wormholes.config.toml.TransitConfig;
import art.arcane.wormholes.portal.Portal;
import art.arcane.wormholes.portal.PortalConstruction;
import art.arcane.optics.crossing.PlaneCrossing;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.aperture.ApertureCells;
import art.arcane.wormholes.portal.PortalStateCodec;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.DimensionalPortalKind;
import art.arcane.wormholes.transit.MomentumPolicy;
import art.arcane.wormholes.transit.OrientationPolicy;
import art.arcane.optics.crossing.MomentumRule;
import art.arcane.wormholes.modded.clientview.MinecraftClientViewService;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.stream.Stream;
import art.arcane.wormholes.network.client.TravelMessage;

public final class MinecraftPortalRegistry implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final TypeToken<Map<String, Object>> DOCUMENT = new TypeToken<>() { };

    private final WormholesModRuntime runtime;
    private final Options options;
    private final Map<UUID, MinecraftPortal> portals = new LinkedHashMap<>();
    private final Map<UUID, Arrival> arrivals = new HashMap<>();
    private final Map<UUID, Departure> pending = new HashMap<>();
    private final Map<UUID, Position> previousPositions = new HashMap<>();
    private final Map<UUID, Vec3d> observedVelocities = new HashMap<>();
    private final Map<UUID, DeferredCrossing> deferredCrossings = new HashMap<>();
    private final ExecutorService storage = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("Wormholes-portal-storage").factory());
    private final Set<UUID> visited = new HashSet<>();
    private long revision;
    private boolean closed;

    public MinecraftPortalRegistry(WormholesModRuntime runtime, Options options) {
        this.runtime = Objects.requireNonNull(runtime);
        this.options = Objects.requireNonNull(options);
    }

    public void load() throws IOException {
        runtime.requireServerThread();
        Files.createDirectories(options.directory());
        try (Stream<Path> paths = Files.walk(options.directory())) {
            for (Path path : paths.filter(Files::isRegularFile).filter(file -> file.toString().endsWith(".json")).sorted().toList()) {
                try {
                    MinecraftPortal portal = MinecraftPortal.read(JSON.fromJson(Files.readString(path), DOCUMENT));
                    MinecraftPortal previous = portals.putIfAbsent(portal.getId(), portal);
                    if (previous != null) {
                        throw new IllegalArgumentException("Duplicate portal " + portal.getId());
                    }
                    revision++;
                } catch (IOException | RuntimeException exception) {
                    LOGGER.error("Could not load Wormholes portal {}", path, exception);
                }
            }
        }
    }

    public List<MinecraftPortal> snapshot() {
        runtime.requireServerThread();
        return List.copyOf(portals.values());
    }

    public long revision() {
        runtime.requireServerThread();
        return revision;
    }

    public MinecraftPortal get(UUID id) {
        runtime.requireServerThread();
        return portals.get(id);
    }

    public MinecraftPortal at(ServerLevel level, BlockPos position) {
        runtime.requireServerThread();
        String worldKey = level.dimension().identifier().toString();
        for (MinecraftPortal portal : portals.values()) {
            if (worldKey.equals(portal.getWorldKey()) && portal.getGeometry().containsBlock(position.getX(), position.getY(), position.getZ())) {
                return portal;
            }
        }
        return null;
    }

    public ServerLevel resolveLevel(MinecraftPortal portal) {
        runtime.requireServerThread();
        for (ServerLevel level : runtime.server().getAllLevels()) {
            if (level.dimension().identifier().toString().equals(portal.getWorldKey())) {
                return level;
            }
        }
        return null;
    }

    public MinecraftPortal create(UUID owner, ServerLevel level, Collection<BlockPos> cells, PortalType type, Vec3 look) {
        runtime.requireServerThread();
        if (closed || cells == null || cells.isEmpty()) {
            throw new IllegalArgumentException("A portal needs aperture cells");
        }
        List<Vec3d> positions = new ArrayList<>(cells.size());
        for (BlockPos cell : cells) {
            if (cell.getY() < level.getMinY() || cell.getY() >= level.getMaxY()) {
                throw new IllegalArgumentException("Portal lies outside the world height");
            }
            if (at(level, cell) != null) {
                throw new IllegalArgumentException("Portal overlaps an existing aperture");
            }
            positions.add(new Vec3d(cell.getX(), cell.getY(), cell.getZ()));
        }
        ApertureCells geometry = new ApertureCells();
        geometry.setBlocks(positions);
        Box bounds = geometry.getArea();
        int xDepth = (int) Math.floor(bounds.getXb()) - (int) Math.floor(bounds.getXa());
        int yDepth = (int) Math.floor(bounds.getYb()) - (int) Math.floor(bounds.getYa());
        int zDepth = (int) Math.floor(bounds.getZb()) - (int) Math.floor(bounds.getZa());
        if (!PortalConstruction.isCoplanarPortalArea(xDepth, yDepth, zDepth)) {
            throw new IllegalArgumentException("Portal aperture must be flat");
        }
        Vec3 direction = look == null ? new Vec3(0, 0, -1) : look;
        Face normal = PortalConstruction.derivePortalNormal(xDepth, yDepth, zDepth, direction.x, direction.y, direction.z);
        Frame frame = Frame.fromDirectionAndLook(normal, vector(direction));
        UUID id = UUID.randomUUID();
        String typeName = type.name().toLowerCase(Locale.ROOT);
        String name = Character.toUpperCase(typeName.charAt(0)) + typeName.substring(1) + " " + id.toString().substring(0, 4);
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("owner", (owner == null ? id : owner).toString());
        properties.put("type", type.name());
        properties.put("projectionMode", "ON");
        properties.put("mirrorMode", false);
        properties.put("permissionMode", "BLACKLIST");
        properties.put("outgoingTraversalsEnabled", true);
        properties.put("incomingTraversalsEnabled", true);
        MinecraftPortal portal = new MinecraftPortal(new MinecraftPortal.Definition(
            new Portal.State(id, geometry.getApertureCenter(), name, frame, true), geometry,
            level.dimension().identifier().toString(), properties));
        portals.put(id, portal);
        revision++;
        save(portal);
        return portal;
    }

    public boolean canManage(ServerPlayer actor, MinecraftPortal portal) {
        return portal != null && portal.canManage(actor.getUUID(), options.access().administrator(actor));
    }

    public boolean canDepart(ServerPlayer actor, MinecraftPortal portal) {
        runtime.requireServerThread();
        return portal != null && !portal.isMirrorMode()
            && (options.access().administrator(actor) || portal.isOutgoingTraversalsEnabled())
            && portal.allows(actor.getUUID(), options.access().administrator(actor),
                node -> options.access().permission(actor, node), options.access().nameAlias());
    }

    public boolean canArrive(ServerPlayer actor, MinecraftPortal portal) {
        runtime.requireServerThread();
        return portal != null && !portal.isMirrorMode()
            && (options.access().administrator(actor) || portal.isIncomingTraversalsEnabled())
            && portal.allows(actor.getUUID(), options.access().administrator(actor),
                node -> options.access().permission(actor, node), options.access().nameAlias());
    }

    public boolean update(ServerPlayer actor, UUID id, Consumer<MinecraftPortal> mutation) {
        runtime.requireServerThread();
        MinecraftPortal portal = portals.get(id);
        if (!canManage(actor, portal)) {
            return false;
        }
        mutation.accept(portal);
        save(portal);
        return true;
    }

    public boolean link(ServerPlayer actor, UUID sourceId, UUID destinationId) {
        runtime.requireServerThread();
        MinecraftPortal source = portals.get(sourceId);
        if (!canManage(actor, source) || source.isMirrorMode() || source.getType() == PortalType.RTP || source.isManaged()) {
            return false;
        }
        if (destinationId == null) {
            source.unlink();
            save(source);
            return true;
        }
        MinecraftPortal destination = portals.get(destinationId);
        if (destination == null || destination.getType() == PortalType.RTP) {
            return false;
        }
        source.link(destination);
        save(source);
        return true;
    }

    public boolean remove(ServerPlayer actor, UUID id) {
        runtime.requireServerThread();
        MinecraftPortal portal = portals.get(id);
        if (!canManage(actor, portal)) {
            return false;
        }
        return remove(id);
    }

    public boolean remove(UUID id) {
        runtime.requireServerThread();
        MinecraftPortal removed = portals.remove(id);
        if (removed == null) {
            return false;
        }
        revision++;
        UUID counterpart = removed.getCounterpartId();
        if (counterpart != null) {
            MinecraftPortal paired = portals.get(counterpart);
            if (paired != null && id.equals(paired.getCounterpartId())) {
                paired.setCounterpartId(null);
                remove(counterpart);
            }
        }
        runtime.effects().removed(removed);
        runtime.network().portalRemoved(id);
        runtime.nexus().removed(removed);
        runtime.atlas().forget(id);
        storage.execute(() -> {
            try {
                Files.deleteIfExists(PortalStateCodec.file(options.directory(), id));
            } catch (IOException exception) {
                LOGGER.error("Could not delete Wormholes portal {}", id, exception);
            }
        });
        return true;
    }

    public void tick() {
        runtime.requireServerThread();
        if (closed) {
            return;
        }
        visited.clear();
        for (ServerPlayer player : runtime.server().getPlayerList().getPlayers()) {
            Position previous = previousPositions.get(player.getUUID());
            observedVelocities.put(player.getUUID(), previous != null && previous.level() == player.level()
                ? vector(player.position().subtract(previous.point())) : vector(player.getDeltaMovement()));
        }
        long now = System.currentTimeMillis();
        arrivals.entrySet().removeIf(entry -> releaseArrival(entry.getKey(), entry.getValue(), now));
        deferredCrossings.entrySet().removeIf(entry -> !retainedCrossing(entry.getValue(), now));
        List<UUID> expired = new ArrayList<>();
        for (Map.Entry<UUID, Departure> entry : pending.entrySet()) {
            if (entry.getValue().expiresAt() <= now) {
                expired.add(entry.getKey());
            }
        }
        for (UUID id : expired) {
            Departure departure = pending.remove(id);
            departure.lease().close();
        }
        MinecraftWormholesApi api = runtime.api();
        boolean resolving = api != null && api.hasResolvers();
        double radius = runtime.configuration().settings().getRender().captureZoneRadius;
        for (MinecraftPortal source : portals.values()) {
            boolean random = source.getType() == PortalType.RTP;
            if (source.getDimensionalKind().isReceiverOnly() || source.getDimensionalKind() == DimensionalPortalKind.END_EXIT
                || !source.isOpen() || source.isMirrorMode() || !random && !resolving && !runtime.nexus().perTraveler(source) && !MinecraftGatewayPolicies.active(source) && (source.getDestinationId() == null
                || !(source.getTunnelType().equals("LOCAL") || source.getTunnelType().equals("DIMENSIONAL")
                    || source.getTunnelType().equals("UNIVERSAL")))) {
                continue;
            }
            boolean universal = source.getTunnelType().equals("UNIVERSAL");
            MinecraftPortal destination = universal || random ? null : portals.get(source.getDestinationId());
            ServerLevel level = resolveLevel(source);
            if (level == null || !universal && !random && !resolving && !runtime.nexus().perTraveler(source) && !MinecraftGatewayPolicies.active(source) && (destination == null || !destination.isOpen())) {
                continue;
            }
            Box area = source.getGeometry().captureZone(radius);
            AABB search = new AABB(area.getXa(), area.getYa(), area.getZa(), area.getXb(), area.getYb(), area.getZb());
            for (Entity entity : captureEntities(source, level, search)) {
                Entity root = entity.getRootVehicle();
                if (pending.containsKey(root.getUUID()) || visited.contains(root.getUUID()) || runtime.doors().travelling(root.getUUID()) || runtime.network().handoffs().locked(root.getUUID()) || runtime.network().entityTransfers().locked(root.getUUID()) || runtime.rtp().locked(root.getUUID())) {
                    continue;
                }
                Vec3 current = root.position();
                Position previous = previousPositions.get(root.getUUID());
                Vec3d start = previous != null && previous.level() == level
                    ? vector(previous.point()) : new Vec3d(root.xo, root.yo, root.zo);
                Vec3d end = vector(current);
                Vec3d intersection = PlaneCrossing.intersection(source.getFrame(), source.getOrigin(), start, end);
                DeferredCrossing deferred = deferredCrossings.get(root.getUUID());
                boolean retained = deferred != null && deferred.source() == source && retainedCrossing(deferred, now) && deferred.crossing().frame().getNormal().x() * (end.x() - source.getOrigin().x())
                        + deferred.crossing().frame().getNormal().y() * (end.y() - source.getOrigin().y())
                        + deferred.crossing().frame().getNormal().z() * (end.z() - source.getOrigin().z()) <= 0.0D;
                if (!retained && (intersection == null || !source.getGeometry().contains(intersection))) {
                    continue;
                }
                Arrival arrival = arrivals.get(root.getUUID());
                if (arrival != null && arrival.blocks(source.getId(), overlaps(source, root), now)) {
                    continue;
                }
                visited.add(root.getUUID());
                Vec3 velocity = root instanceof ServerPlayer ? current.subtract(start.x(), start.y(), start.z()) : root.getDeltaMovement();
                PlaneCrossing crossing = PlaneCrossing.create(source.getFrame(), source.getOrigin(),
                    new PlaneCrossing.Motion(start, end, vector(velocity), vector(root.getLookAngle())));
                if (retained) {
                    crossing = deferred.crossing();
                }
                if (!random && root instanceof ServerPlayer player && runtime.clientViews().deferTravel(player.getUUID(), source.getId())) {
                    deferredCrossings.putIfAbsent(root.getUUID(), new DeferredCrossing(player, source, level, source.getDestinationId(), source.getDestinationServer(), crossing, now + 2_500L));
                    continue;
                }
                deferredCrossings.remove(root.getUUID());
                if (!MinecraftTransit.depart(runtime, source, root, crossing)) {
                    continue;
                }
                if (random) {
                    runtime.rtp().begin(root, source, crossing);
                } else {
                    if (root instanceof ServerPlayer player && MinecraftGatewayPolicies.active(source)) {
                        runtime.network().handoffs().begin(player, source.getDestinationServer(), source, crossing, source.getDestinationId());
                        continue;
                    }
                    NetworkMember selected = resolveDestination(source, root, crossing);
                    if (selected == null) {
                        continue;
                    }
                    if (!selected.isLocal()) {
                    if (runtime.network().entityTransfers().beginConvoy(root, source, crossing, selected)) {
                        continue;
                    }
                    if (root instanceof ServerPlayer player) {
                        runtime.network().handoffs().begin(player, selected.serverName(), source, crossing, selected.portalId());
                    } else {
                        runtime.network().entityTransfers().begin(root, source, crossing, selected);
                    }
                    } else {
                        MinecraftPortal selectedPortal = portals.get(selected.portalId());
                        if (selectedPortal != null && selectedPortal.isOpen()) {
                            depart(root, source, selectedPortal, crossing);
                        }
                    }
                }
            }
        }
        previousPositions.clear();
        for (ServerPlayer player : runtime.server().getPlayerList().getPlayers()) {
            previousPositions.put(player.getUUID(), new Position(player.level(), player.position()));
        }
    }

    public Vec3d observedVelocity(ServerPlayer player) {
        runtime.requireServerThread();
        return observedVelocities.getOrDefault(player.getUUID(), vector(player.getDeltaMovement()));
    }

    public NetworkMember resolveDestination(MinecraftPortal source, Entity traveler, PlaneCrossing crossing) {
        runtime.requireServerThread();
        MinecraftWormholesApi api = runtime.api();
        NetworkMember selected = api != null && api.hasResolvers() ? api.resolve(source, traveler.getUUID()) : null;
        if (selected == null) {
            selected = runtime.nexus().destination(source, traveler, crossing);
        }
        if (selected == null && !runtime.nexus().perTraveler(source) && source.getDestinationId() != null) {
            selected = new NetworkMember(source.getDestinationId(), "", "", 0, source.getDestinationServer());
        }
        return selected;
    }

    public TravelMessage.ArrivalRules arrivalRules(MinecraftPortal source) {
        TransitConfig config = runtime.configuration().settings().getTransit();
        MomentumPolicy momentum = MomentumPolicy.decode((String) source.setting("transit.momentum"));
        if (momentum == null) {
            momentum = MomentumPolicy.of(MomentumPolicy.Mode.parse(config.momentumDefault, MomentumPolicy.Mode.PRESERVE));
        }
        OrientationPolicy orientation = OrientationPolicy.parse((String) source.setting("transit.orientation"),
            OrientationPolicy.parse(config.orientationDefault, OrientationPolicy.FRAME));
        return arrivalRules(orientation, momentum, config.gravityFlipEnabled, config.momentumMaxSpeed);
    }

    public TravelMessage.ArrivalRules doorArrivalRules() {
        TransitConfig config = runtime.configuration().settings().getTransit();
        return arrivalRules(OrientationPolicy.FRAME, MomentumPolicy.of(MomentumPolicy.Mode.PRESERVE), false, config.momentumMaxSpeed);
    }

    static TravelMessage.ArrivalRules arrivalRules(OrientationPolicy orientation, MomentumPolicy momentum, boolean gravityFlip, double maxSpeed) {
        MomentumRule rule = momentum.rule();
        return new TravelMessage.ArrivalRules(orientation.rule(), gravityFlip,
            new MomentumRule(rule.mode(), rule.factor(), rule.maxSpeed() > 0.0D ? rule.maxSpeed() : maxSpeed, rule.impulse()));
    }

    public boolean arrivalBlocks(ServerPlayer player, MinecraftPortal source) {
        runtime.requireServerThread();
        Arrival arrival = arrivals.get(player.getUUID());
        return arrival != null && arrival.blocks(source.getId(), overlaps(source, player), System.currentTimeMillis());
    }

    public boolean crossPrepared(ServerPlayer player, UUID sourceId, MinecraftPortal destination, PlaneCrossing crossing) {
        runtime.requireServerThread();
        MinecraftPortal source = portals.get(sourceId);
        if (closed || source == null || !source.isOpen() || source.isMirrorMode() || source.getType() == PortalType.RTP
            || player.level() != resolveLevel(source) || player.getVehicle() != null || !player.getPassengers().isEmpty()
            || pending.containsKey(player.getUUID()) || runtime.doors().travelling(player.getUUID()) || runtime.network().handoffs().locked(player.getUUID())
            || runtime.network().entityTransfers().locked(player.getUUID()) || runtime.rtp().locked(player.getUUID())) {
            return false;
        }
        Arrival arrival = arrivals.get(player.getUUID());
        if (arrival != null && arrival.blocks(sourceId, overlaps(source, player), System.currentTimeMillis())) {
            return false;
        }
        if (destination == null || !destination.isOpen() || destination.isMirrorMode() || !admit(player, source, destination)) {
            return false;
        }
        ServerLevel targetLevel = resolveLevel(destination);
        Vec3d target = crossing.outPoint(destination.getFrame(), destination.getOrigin());
        if (targetLevel == null || !targetLevel.noCollision(player, player.getBoundingBox().move(
            target.x() - player.getX(), target.y() - player.getY(), target.z() - player.getZ()))
            || !MinecraftTransit.depart(runtime, source, player, crossing)) {
            return false;
        }
        deferredCrossings.remove(player.getUUID());
        visited.add(player.getUUID());
        depart(player, source, destination, crossing);
        return true;
    }

    private List<Entity> captureEntities(MinecraftPortal source, ServerLevel level, AABB search) {
        List<Entity> nearby = level.getEntities((Entity) null, search, Entity::isAlive);
        List<Entity> candidates = nearby;
        for (DeferredCrossing deferred : deferredCrossings.values()) {
            if (deferred.source() == source && deferred.level() == level && !nearby.contains(deferred.player())) {
                if (candidates == nearby) {
                    candidates = new ArrayList<>(nearby);
                }
                candidates.add(deferred.player());
            }
        }
        return candidates;
    }

    private boolean retainedCrossing(DeferredCrossing deferred, long now) {
        MinecraftPortal source = deferred.source();
        return now < deferred.expiresAt() && deferred.player().isAlive() && deferred.player().level() == deferred.level()
            && portals.get(source.getId()) == source && source.isOpen() && !source.isMirrorMode()
            && Objects.equals(source.getDestinationId(), deferred.destination())
            && Objects.equals(source.getDestinationServer(), deferred.destinationServer())
            && source.getOrigin().equals(deferred.crossing().origin())
            && source.getFrame().view(deferred.crossing().frontSide()).equals(deferred.crossing().frame())
            && !runtime.doors().travelling(deferred.player().getUUID());
    }

    private record DeferredCrossing(ServerPlayer player, MinecraftPortal source, ServerLevel level, UUID destination,
                                    String destinationServer, PlaneCrossing crossing, long expiresAt) {
    }

    boolean travelling(UUID entityId) {
        return pending.containsKey(entityId);
    }

    void recordTeleport(Entity entity) {
        UUID entityId = entity.getUUID();
        deferredCrossings.remove(entityId);
        Departure departure = pending.remove(entityId);
        if (departure != null) {
            departure.lease().close();
        }
        observedVelocities.remove(entityId);
        if (entity instanceof ServerPlayer player) {
            previousPositions.put(entityId, new Position(player.level(), player.position()));
        }
        visited.add(entityId);
    }

    public void recordArrival(Entity entity, MinecraftPortal destination) {
        runtime.requireServerThread();
        runtime.travelArrived(entity);
        long now = System.currentTimeMillis();
        arrivals.put(entity.getUUID(), new Arrival(destination.getId(),
            now + runtime.configuration().settings().getMain().teleportCooldownMillis, now + 60_000L));
        visited.add(entity.getUUID());
    }

    public void playerDisconnected(ServerPlayer player) {
        runtime.requireServerThread();
        arrivals.remove(player.getUUID());
        deferredCrossings.remove(player.getUUID());
        previousPositions.remove(player.getUUID());
        observedVelocities.remove(player.getUUID());
        Departure departure = pending.remove(player.getUUID());
        if (departure != null) {
            departure.lease().close();
        }
    }

    @Override
    public void close() {
        runtime.requireServerThread();
        closed = true;
        List<Departure> departures = List.copyOf(pending.values());
        pending.clear();
        for (Departure departure : departures) {
            departure.lease().close();
        }
        previousPositions.clear();
        deferredCrossings.clear();
        observedVelocities.clear();
        arrivals.clear();
        storage.close();
        portals.clear();
        revision++;
    }

    public CompletableFuture<Void> flushWrites() {
        runtime.requireServerThread();
        if (closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("Portal registry is closed"));
        }
        CompletableFuture<Void> flushed = new CompletableFuture<>();
        storage.execute(() -> flushed.complete(null));
        return flushed;
    }

    public void save(MinecraftPortal portal) {
        runtime.requireServerThread();
        if (closed || portals.get(portal.getId()) != portal) {
            throw new IllegalArgumentException("Portal is not registered");
        }
        runtime.network().portalChanged(portal);
        runtime.nexus().changed(portal);
        String encoded = JSON.toJson(portal.write());
        Path file = PortalStateCodec.file(options.directory(), portal.getId());
        storage.execute(() -> {
            try {
                write(file, encoded);
            } catch (IOException exception) {
                LOGGER.error("Could not save Wormholes portal {}", portal.getId(), exception);
            }
        });
    }

    static void write(Path file, String encoded) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temporary, encoded, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void depart(Entity entity, MinecraftPortal source, MinecraftPortal destination, PlaneCrossing crossing) {
        ServerLevel targetLevel = resolveLevel(destination);
        if (targetLevel == null || destination.getType() == PortalType.RTP || !admit(entity, source, destination)) {
            return;
        }
        for (Entity member : entity.getSelfAndPassengers().toList()) {
            if (!runtime.rules().depart(member, source, () -> depart(entity, source, destination, crossing))) {
                return;
            }
        }
        Vec3d target = crossing.outPoint(destination.getFrame(), destination.getOrigin());
        ChunkLease lease = runtime.leases().retain(targetLevel,
            UUID.nameUUIDFromBytes(destination.getWorldKey().getBytes(StandardCharsets.UTF_8)),
            target.blockX() >> 4, target.blockZ() >> 4);
        boolean predicted = entity instanceof ServerPlayer player && runtime.clientViews().crossing(player.getUUID());
        TravelMessage.TravelBegin attempted = predicted ? runtime.clientViews().preparation(entity.getUUID()).orElse(null) : null;
        Departure departure = new Departure(lease, entity.level(), entity.position(), System.currentTimeMillis() + 30_000L);
        pending.put(entity.getUUID(), departure);
        Flight flight = new Flight(entity, source, destination, crossing, targetLevel, target, departure, predicted, attempted);
        if (predicted && entity instanceof ServerPlayer player && entity.getPassengers().isEmpty() && runtime.clientViews().seamlessCrossing(player)
            && targetLevel.getChunkSource().getChunkNow(target.blockX() >> 4, target.blockZ() >> 4) != null) {
            land(flight, Boolean.TRUE, null);
            return;
        }
        lease.ready().whenCompleteAsync((ready, failure) -> land(flight, ready, failure), runtime.server());
    }

    private void land(Flight flight, Boolean ready, Throwable failure) {
        Entity entity = flight.entity();
        MinecraftPortal source = flight.source();
        MinecraftPortal destination = flight.destination();
        Departure departure = flight.departure();
        try {
            if (failure != null) {
                LOGGER.error("Could not prepare Wormholes destination {}", destination.getId(), failure);
            }
            if (closed || !Boolean.TRUE.equals(ready) || !entity.isAlive() || portals.get(source.getId()) != source
                || portals.get(destination.getId()) != destination || !runtime.nexus().perTraveler(source) && !(runtime.api() != null && runtime.api().hasResolvers()) && !Objects.equals(source.getDestinationId(), destination.getId())
                || !source.isOpen() || !destination.isOpen() || source.isMirrorMode() || destination.isMirrorMode()
                || runtime.doors().travelling(entity.getUUID()) || pending.get(entity.getUUID()) != departure || entity.level() != departure.level()
                || entity.position().distanceToSqr(departure.point()) > 1.0D
                || departure.expiresAt() <= System.currentTimeMillis() || !admit(entity, source, destination)
                || !screenRules(entity, source) || flight.predicted() && !runtime.clientViews().crossing(entity.getUUID(), flight.attempted())
                || flight.predicted() && !flight.targetLevel().noCollision(entity, entity.getBoundingBox().move(
                    flight.target().x() - entity.getX(), flight.target().y() - entity.getY(), flight.target().z() - entity.getZ()))) {
                return;
            }
            arrive(entity, destination, flight.crossing(), flight.targetLevel(), flight.target(), source, flight.predicted());
        } catch (RuntimeException exception) {
            LOGGER.error("Wormholes traversal failed from {} to {} for {}", source.getId(), destination.getId(), entity.getUUID(), exception);
        } finally {
            if (flight.predicted()) {
                for (Entity member : entity.getSelfAndPassengers().toList()) {
                    if (member instanceof ServerPlayer player) {
                        runtime.clientViews().cancelPreparation(player, flight.attempted());
                    }
                    runtime.rules().failed(member);
                }
            } else {
                failRules(entity.getSelfAndPassengers().toList());
            }
            if (pending.remove(entity.getUUID(), departure)) {
                departure.lease().close();
            }
        }
    }

    private boolean screenRules(Entity entity, MinecraftPortal source) {
        for (Entity member : entity.getSelfAndPassengers().toList()) {
            if (!runtime.rules().screeningAllowed(member, source)) {
                return false;
            }
        }
        return true;
    }

    private boolean admit(Entity entity, MinecraftPortal source, MinecraftPortal destination) {
        TransitConfig config = runtime.configuration().settings().getTransit();
        if (!config.convoyEnabled && !entity.getPassengers().isEmpty()) {
            return false;
        }
        List<Entity> rig = entity.getSelfAndPassengers().toList();
        if (rig.size() > config.convoyMaxEntities) {
            return false;
        }
        for (Entity member : rig) {
            if (!runtime.rules().arrivalAllowed(member, destination, true)) {
                return false;
            }
            if (member instanceof ServerPlayer player) {
                if (!canDepart(player, source) || !canArrive(player, destination)) {
                    return false;
                }
            } else if (!source.isOutgoingTraversalsEnabled() || !destination.isIncomingTraversalsEnabled()) {
                return false;
            }
        }
        return true;
    }

    private void arrive(Entity entity, MinecraftPortal destination, PlaneCrossing crossing, ServerLevel targetLevel,
                        Vec3d target, MinecraftPortal source, boolean predicted) {
        TransitConfig config = runtime.configuration().settings().getTransit();
        TravelMessage.ArrivalRules rules = arrivalRules(source);
        Vec3d velocity = ArrivalMomentum.apply(crossing.outVelocity(destination.getFrame()), rules.momentum(), config.momentumMaxSpeed);
        Angles.Look look = ArrivalOrientation.apply(crossing, destination.getFrame(), rules.orientation(), rules.gravityFlip());
        List<ChunkPreSendTicket<ServerLevel, ServerPlayer>> preSend = new ArrayList<>();
        List<MinecraftTravelCosts.Admission> payments = new ArrayList<>();
        List<PreparedCommit> preparedCommits = new ArrayList<>();
        boolean reloadExpected = entity.level() != targetLevel;
        Entity arrived;
        List<Entity> rig = entity.getSelfAndPassengers().toList();
        TravelMessage.TravelPose pose = new TravelMessage.TravelPose(target.x(), target.y(), target.z(), look.yaw(), look.pitch());
        boolean seamlessCrossing = predicted && rig.size() == 1 && entity instanceof ServerPlayer traveler
            && runtime.clientViews().seamlessCrossing(traveler);
        MinecraftClientViewService.SeamlessTicket seamless = null;
        try {
            for (Entity member : rig) {
                if (!runtime.rules().reserve(member, source)) {
                    failRules(rig);
                    refund(payments);
                    rollback(preSend);
                    return;
                }
                if (member instanceof ServerPlayer player) {
                    MinecraftTravelCosts.Admission payment = runtime.costs().open(new MinecraftTraversalContext(UUID.randomUUID(),
                        TraversalKind.LOCAL, player, source.getId(), source.getName(), MinecraftTraversalContext.Location.of(player),
                        Optional.of(new MinecraftTraversalContext.Destination("", destination.getId(),
                            new MinecraftTraversalContext.Location(targetLevel, vector(target), look.yaw(), look.pitch())))));
                    if (!payment.allowed()) {
                        refund(payments);
                        rollback(preSend);
                        return;
                    }
                    payments.add(payment);
                    if (!seamlessCrossing) {
                        preSend.add(runtime.preSend().preSend(player, targetLevel, target.blockX(), target.blockZ()));
                    }
                }
            }
            MinecraftTraversalCues.threshold(runtime, source, crossing.point(), entity);
            for (Entity member : rig) {
                if (member instanceof ServerPlayer player && seamlessCrossing) {
                    seamless = runtime.clientViews().seamlessArrival(player, source.getId(), targetLevel, pose, velocity);
                    if (seamless == null) {
                        failRules(rig);
                        refund(payments);
                        rollback(preSend);
                        return;
                    }
                } else if (member instanceof ServerPlayer player) {
                    TravelMessage.TravelCommit commit = runtime.clientViews().commitTravel(player, source.getId(), targetLevel, pose, velocity);
                    if (commit != null) {
                        preparedCommits.add(new PreparedCommit(player, commit));
                    } else if (predicted) {
                        failRules(rig);
                        refund(payments);
                        rollback(preSend);
                        return;
                    }
                }
            }
            try (WormholesModRuntime.TeleportScope scope = runtime.beginTeleport(entity)) {
                arrived = seamless != null ? runtime.clientViews().seamlessMove(seamless)
                    : entity.teleport(new TeleportTransition(targetLevel, vector(target), vector(velocity), look.yaw(), look.pitch(),
                        TeleportTransition.PLACE_PORTAL_TICKET));
            }
        } catch (RuntimeException exception) {
            cancelPrepared(preparedCommits);
            refund(payments);
            rollback(preSend);
            throw exception;
        }
        if (arrived == null) {
            cancelPrepared(preparedCommits);
            refund(payments);
            rollback(preSend);
            return;
        }
        for (PreparedCommit commit : preparedCommits) {
            runtime.clientViews().completeTravel(commit.player());
        }
        for (MinecraftTravelCosts.Admission payment : payments) {
            payment.commit();
        }
        long now = System.currentTimeMillis();
        for (ChunkPreSendTicket<ServerLevel, ServerPlayer> ticket : preSend) {
            MinecraftTransit.arrived(runtime, source, ticket.player(), reloadExpected, ticket, prepared(preparedCommits, ticket.player().getUUID()));
        }
        long cooldown = config.objectTransitContinuous && (arrived instanceof ItemEntity || arrived instanceof Projectile)
            ? 0 : runtime.configuration().settings().getMain().teleportCooldownMillis;
        for (Entity member : arrived.getSelfAndPassengers().toList()) {
            runtime.travelArrived(member);
            runtime.rules().arrived(member, destination);
            runtime.nexus().arrived(member, source);
            MinecraftWormholesApi api = runtime.api();
            if (api != null) {
                api.emit(new MinecraftWormholesApi.Event(MinecraftWormholesApi.Kind.HANDOFF_ADMITTED, member.getUUID(), source.getId(), null, "", null, null));
                api.emit(new MinecraftWormholesApi.Event(MinecraftWormholesApi.Kind.HANDOFF_COMPLETED, member.getUUID(), destination.getId(), null, "", null, null));
            }
            MinecraftTraversalCues.arrival(runtime, destination, member, seamless != null || prepared(preparedCommits, member.getUUID()));
            if (member instanceof ServerPlayer player) {
                runtime.atlas().departed(player, source);
            }
            arrivals.put(member.getUUID(), new Arrival(destination.getId(), now + cooldown, now + 60_000L));
            visited.add(member.getUUID());
        }
    }

    private void failRules(List<Entity> travelers) {
        for (Entity traveler : travelers) {
            if (traveler instanceof ServerPlayer player) {
                runtime.clientViews().cancelTravel(player, null);
            }
            runtime.rules().failed(traveler);
        }
    }

    private void cancelPrepared(List<PreparedCommit> commits) {
        for (PreparedCommit commit : commits) {
            runtime.clientViews().cancelTravel(commit.player(), commit.message());
        }
    }

    private static boolean prepared(List<PreparedCommit> commits, UUID traveler) {
        for (PreparedCommit commit : commits) {
            if (commit.player().getUUID().equals(traveler)) {
                return true;
            }
        }
        return false;
    }

    private record PreparedCommit(ServerPlayer player, TravelMessage.TravelCommit message) {
    }

    private void refund(List<MinecraftTravelCosts.Admission> payments) {
        for (int index = payments.size() - 1; index >= 0; index--) {
            payments.get(index).refund(TraversalRefundReason.TELEPORT_FAILED);
        }
    }

    private void rollback(List<ChunkPreSendTicket<ServerLevel, ServerPlayer>> tickets) {
        for (ChunkPreSendTicket<ServerLevel, ServerPlayer> ticket : tickets) {
            runtime.preSend().rollback(ticket);
        }
    }

    private boolean releaseArrival(UUID entityId, Arrival arrival, long now) {
        if (now >= arrival.expiresAt) {
            return true;
        }
        MinecraftPortal portal = portals.get(arrival.portalId);
        ServerLevel level = portal == null ? null : resolveLevel(portal);
        Entity entity = level == null ? null : level.getEntity(entityId);
        return entity == null ? now >= arrival.cooldownUntil : arrival.release(overlaps(portal, entity), now);
    }

    private static boolean overlaps(MinecraftPortal portal, Entity entity) {
        Box area = portal.getGeometry().getArea();
        return entity.getBoundingBox().intersects(area.getXa(), area.getYa(), area.getZa(), area.getXb(), area.getYb(), area.getZb());
    }

    private static Vec3d vector(Vec3 vector) {
        return new Vec3d(vector.x, vector.y, vector.z);
    }

    private static Vec3 vector(Vec3d vector) {
        return new Vec3(vector.x(), vector.y(), vector.z());
    }

    public record Options(Path directory, Access access) {
        public Options {
            Objects.requireNonNull(directory);
            Objects.requireNonNull(access);
        }
    }

    public interface Access {
        boolean administrator(ServerPlayer player);
        boolean permission(ServerPlayer player, String node);
        boolean nameAlias();
    }

    private record Position(ServerLevel level, Vec3 point) {
    }

    private record Departure(ChunkLease lease, Level level, Vec3 point, long expiresAt) {
    }

    private record Flight(Entity entity, MinecraftPortal source, MinecraftPortal destination, PlaneCrossing crossing, ServerLevel targetLevel,
                          Vec3d target, Departure departure, boolean predicted, TravelMessage.TravelBegin attempted) {
    }

    static final class Arrival {
        private final UUID portalId;
        private final long cooldownUntil;
        private final long expiresAt;
        private boolean exited;

        Arrival(UUID portalId, long cooldownUntil, long expiresAt) {
            this.portalId = portalId;
            this.cooldownUntil = cooldownUntil;
            this.expiresAt = expiresAt;
        }

        boolean release(boolean overlapping, long now) {
            if (!overlapping) {
                exited = true;
            }
            return now >= expiresAt || now >= cooldownUntil && exited;
        }

        boolean blocks(UUID source, boolean overlapping, long now) {
            return now < cooldownUntil || !exited && portalId.equals(source) && overlapping;
        }
    }
}
