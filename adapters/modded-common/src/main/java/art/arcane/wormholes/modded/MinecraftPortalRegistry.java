package art.arcane.wormholes.modded;

import art.arcane.wormholes.api.traversal.TraversalKind;
import art.arcane.wormholes.api.traversal.TraversalRefundReason;
import java.util.Optional;

import art.arcane.wormholes.nexus.NetworkMember;
import art.arcane.wormholes.network.MinecraftGatewayPolicies;
import art.arcane.wormholes.chunk.ChunkLease;
import art.arcane.wormholes.chunk.presend.ChunkPreSendTicket;
import art.arcane.wormholes.config.toml.TransitConfig;
import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.portal.Portal;
import art.arcane.wormholes.portal.PortalConstruction;
import art.arcane.wormholes.portal.PortalCrossing;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalGeometry;
import art.arcane.wormholes.portal.PortalStateCodec;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.DimensionalPortalKind;
import art.arcane.wormholes.transit.MomentumPolicy;
import art.arcane.wormholes.transit.MomentumTransform;
import art.arcane.wormholes.transit.OrientationPolicy;
import art.arcane.wormholes.transit.OrientationTransform;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;
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
        List<GeometryVector> positions = new ArrayList<>(cells.size());
        for (BlockPos cell : cells) {
            if (cell.getY() < level.getMinY() || cell.getY() >= level.getMaxY()) {
                throw new IllegalArgumentException("Portal lies outside the world height");
            }
            if (at(level, cell) != null) {
                throw new IllegalArgumentException("Portal overlaps an existing aperture");
            }
            positions.add(new GeometryVector(cell.getX(), cell.getY(), cell.getZ()));
        }
        PortalGeometry geometry = new PortalGeometry();
        geometry.setBlocks(positions);
        AxisAlignedBB bounds = geometry.getArea();
        int xDepth = (int) Math.floor(bounds.getXb()) - (int) Math.floor(bounds.getXa());
        int yDepth = (int) Math.floor(bounds.getYb()) - (int) Math.floor(bounds.getYa());
        int zDepth = (int) Math.floor(bounds.getZb()) - (int) Math.floor(bounds.getZa());
        if (!PortalConstruction.isCoplanarPortalArea(xDepth, yDepth, zDepth)) {
            throw new IllegalArgumentException("Portal aperture must be flat");
        }
        Vec3 direction = look == null ? new Vec3(0, 0, -1) : look;
        Direction normal = PortalConstruction.derivePortalNormal(xDepth, yDepth, zDepth, direction.x, direction.y, direction.z);
        PortalFrame frame = PortalFrame.fromDirectionAndLook(normal, vector(direction));
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
        long now = System.currentTimeMillis();
        arrivals.entrySet().removeIf(entry -> releaseArrival(entry.getKey(), entry.getValue(), now));
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
            AxisAlignedBB area = source.getGeometry().captureZone(radius);
            AABB search = new AABB(area.getXa(), area.getYa(), area.getZa(), area.getXb(), area.getYb(), area.getZb());
            for (Entity entity : level.getEntities((Entity) null, search, Entity::isAlive)) {
                Entity root = entity.getRootVehicle();
                if (pending.containsKey(root.getUUID()) || visited.contains(root.getUUID()) || runtime.network().handoffs().locked(root.getUUID()) || runtime.network().entityTransfers().locked(root.getUUID()) || runtime.rtp().locked(root.getUUID())) {
                    continue;
                }
                Vec3 current = root.position();
                Position previous = previousPositions.get(root.getUUID());
                GeometryVector start = previous != null && previous.level() == level
                    ? vector(previous.point()) : new GeometryVector(root.xo, root.yo, root.zo);
                GeometryVector end = vector(current);
                GeometryVector intersection = PortalCrossing.intersection(source.getFrame(), source.getOrigin(), start, end);
                if (intersection == null || !source.getGeometry().contains(intersection)) {
                    continue;
                }
                Arrival arrival = arrivals.get(root.getUUID());
                if (arrival != null && (now < arrival.cooldownUntil()
                    || arrival.portalId().equals(source.getId()) && overlaps(source, root))) {
                    continue;
                }
                visited.add(root.getUUID());
                Vec3 velocity = root instanceof ServerPlayer ? current.subtract(start.x(), start.y(), start.z()) : root.getDeltaMovement();
                PortalCrossing crossing = PortalCrossing.create(source.getFrame(), source.getOrigin(),
                    new PortalCrossing.Motion(start, end, vector(velocity), vector(root.getLookAngle())));
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
                    NetworkMember selected = resolving ? api.resolve(source, root.getUUID()) : null;
                    if (selected == null) {
                        selected = runtime.nexus().destination(source, root, crossing);
                    }
                    if (selected == null && !runtime.nexus().perTraveler(source) && source.getDestinationId() != null) {
                        selected = new NetworkMember(source.getDestinationId(), "", "", 0, source.getDestinationServer());
                    }
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

    public void recordArrival(Entity entity, MinecraftPortal destination) {
        runtime.requireServerThread();
        long now = System.currentTimeMillis();
        arrivals.put(entity.getUUID(), new Arrival(destination.getId(),
            now + runtime.configuration().settings().getMain().teleportCooldownMillis, now + 60_000L));
        visited.add(entity.getUUID());
    }

    public void playerDisconnected(ServerPlayer player) {
        runtime.requireServerThread();
        arrivals.remove(player.getUUID());
        previousPositions.remove(player.getUUID());
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

    private void depart(Entity entity, MinecraftPortal source, MinecraftPortal destination, PortalCrossing crossing) {
        ServerLevel targetLevel = resolveLevel(destination);
        if (targetLevel == null || destination.getType() == PortalType.RTP || !admit(entity, source, destination)) {
            return;
        }
        for (Entity member : entity.getSelfAndPassengers().toList()) {
            if (!runtime.rules().depart(member, source, () -> depart(entity, source, destination, crossing))) {
                return;
            }
        }
        GeometryVector target = crossing.outPoint(destination.getFrame(), destination.getOrigin());
        ChunkLease lease = runtime.leases().retain(targetLevel,
            UUID.nameUUIDFromBytes(destination.getWorldKey().getBytes(StandardCharsets.UTF_8)),
            target.getBlockX() >> 4, target.getBlockZ() >> 4);
        Departure departure = new Departure(lease, entity.level(), entity.position(), System.currentTimeMillis() + 30_000L);
        pending.put(entity.getUUID(), departure);
        lease.ready().whenCompleteAsync((ready, failure) -> {
            try {
                if (failure != null) {
                    LOGGER.error("Could not prepare Wormholes destination {}", destination.getId(), failure);
                }
                if (closed || !Boolean.TRUE.equals(ready) || !entity.isAlive() || portals.get(source.getId()) != source
                    || portals.get(destination.getId()) != destination || !runtime.nexus().perTraveler(source) && !(runtime.api() != null && runtime.api().hasResolvers()) && !Objects.equals(source.getDestinationId(), destination.getId())
                    || !source.isOpen() || !destination.isOpen() || source.isMirrorMode() || destination.isMirrorMode()
                    || pending.get(entity.getUUID()) != departure || entity.level() != departure.level()
                    || entity.position().distanceToSqr(departure.point()) > 1.0D
                    || departure.expiresAt() <= System.currentTimeMillis() || !admit(entity, source, destination)
                    || !screenRules(entity, source)) {
                    return;
                }
                arrive(entity, destination, crossing, targetLevel, target, source);
            } catch (RuntimeException exception) {
                LOGGER.error("Wormholes traversal failed from {} to {} for {}", source.getId(), destination.getId(), entity.getUUID(), exception);
            } finally {
                failRules(entity.getSelfAndPassengers().toList());
                pending.remove(entity.getUUID(), departure);
                lease.close();
            }
        }, runtime.server());
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

    private void arrive(Entity entity, MinecraftPortal destination, PortalCrossing crossing, ServerLevel targetLevel,
                        GeometryVector target, MinecraftPortal source) {
        TransitConfig config = runtime.configuration().settings().getTransit();
        MomentumPolicy momentum = MomentumPolicy.decode((String) source.setting("transit.momentum"));
        if (momentum == null) {
            momentum = MomentumPolicy.of(MomentumPolicy.Mode.parse(config.momentumDefault, MomentumPolicy.Mode.PRESERVE));
        }
        OrientationPolicy orientation = OrientationPolicy.parse((String) source.setting("transit.orientation"),
            OrientationPolicy.parse(config.orientationDefault, OrientationPolicy.FRAME));
        GeometryVector velocity = MomentumTransform.apply(crossing.outVelocity(destination.getFrame()), momentum, config.momentumMaxSpeed);
        OrientationTransform.Look look = OrientationTransform.apply(crossing, destination.getFrame(), orientation, config.gravityFlipEnabled);
        List<ChunkPreSendTicket<ServerLevel, ServerPlayer>> preSend = new ArrayList<>();
        List<MinecraftTravelCosts.Admission> payments = new ArrayList<>();
        boolean reloadExpected = entity.level() != targetLevel;
        Entity arrived;
        List<Entity> rig = entity.getSelfAndPassengers().toList();
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
                    preSend.add(runtime.preSend().preSend(player, targetLevel, target.getBlockX(), target.getBlockZ()));
                }
            }
            MinecraftTraversalCues.threshold(runtime, source, crossing.point());
            arrived = entity.teleport(new TeleportTransition(targetLevel, vector(target), vector(velocity), look.yaw(), look.pitch(),
                TeleportTransition.PLACE_PORTAL_TICKET));
        } catch (RuntimeException exception) {
            refund(payments);
            rollback(preSend);
            throw exception;
        }
        if (arrived == null) {
            refund(payments);
            rollback(preSend);
            return;
        }
        for (MinecraftTravelCosts.Admission payment : payments) {
            payment.commit();
        }
        long now = System.currentTimeMillis();
        for (ChunkPreSendTicket<ServerLevel, ServerPlayer> ticket : preSend) {
            MinecraftTransit.arrived(runtime, source, ticket.player(), reloadExpected, ticket);
        }
        long cooldown = config.objectTransitContinuous && (arrived instanceof ItemEntity || arrived instanceof Projectile)
            ? 0 : runtime.configuration().settings().getMain().teleportCooldownMillis;
        for (Entity member : arrived.getSelfAndPassengers().toList()) {
            runtime.rules().arrived(member, destination);
            runtime.nexus().arrived(member, source);
            MinecraftWormholesApi api = runtime.api();
            if (api != null) {
                api.emit(new MinecraftWormholesApi.Event(MinecraftWormholesApi.Kind.HANDOFF_ADMITTED, member.getUUID(), source.getId(), null, "", null, null));
                api.emit(new MinecraftWormholesApi.Event(MinecraftWormholesApi.Kind.HANDOFF_COMPLETED, member.getUUID(), destination.getId(), null, "", null, null));
            }
            MinecraftTraversalCues.arrival(runtime, destination, member);
            if (member instanceof ServerPlayer player) {
                runtime.atlas().departed(player, source);
            }
            arrivals.put(member.getUUID(), new Arrival(destination.getId(), now + cooldown, now + 60_000L));
            visited.add(member.getUUID());
        }
    }

    private void failRules(List<Entity> travelers) {
        for (Entity traveler : travelers) {
            runtime.rules().failed(traveler);
        }
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
        if (arrival.expiresAt() <= now) {
            return true;
        }
        if (arrival.cooldownUntil() > now) {
            return false;
        }
        MinecraftPortal portal = portals.get(arrival.portalId());
        ServerLevel level = portal == null ? null : resolveLevel(portal);
        Entity entity = level == null ? null : level.getEntity(entityId);
        return entity == null || !overlaps(portal, entity);
    }

    private static boolean overlaps(MinecraftPortal portal, Entity entity) {
        AxisAlignedBB area = portal.getGeometry().getArea();
        return entity.getBoundingBox().intersects(area.getXa(), area.getYa(), area.getZa(), area.getXb(), area.getYb(), area.getZb());
    }

    private static GeometryVector vector(Vec3 vector) {
        return new GeometryVector(vector.x, vector.y, vector.z);
    }

    private static Vec3 vector(GeometryVector vector) {
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

    private record Arrival(UUID portalId, long cooldownUntil, long expiresAt) {
    }
}
