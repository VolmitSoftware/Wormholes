package art.arcane.wormholes.network;

import art.arcane.optics.crossing.ArrivalMomentum;
import art.arcane.optics.crossing.ArrivalOrientation;
import art.arcane.optics.math.Angles;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.nexus.NetworkMember;
import art.arcane.optics.plate.ChunkLease;
import art.arcane.wormholes.config.toml.TransitConfig;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.optics.crossing.PlaneCrossing;
import art.arcane.wormholes.transit.MomentumPolicy;
import art.arcane.wormholes.transit.OrientationPolicy;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class MinecraftEntityTransfers implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");
    private static final String STAMP_KEY = "wormholes:entity_transit_state";

    private final WormholesModRuntime runtime;
    private final TraversalTransferLocks locks = new TraversalTransferLocks();
    private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock();
    private final AtomicLong completed = new AtomicLong();
    private final TraversalFailureLedger failures = new TraversalFailureLedger(new TraversalFailureLedger.Options(
        LOGGER::isDebugEnabled, LOGGER::debug, LOGGER::warn));
    private final Map<UUID, Preparation> preparations = new HashMap<>();
    private final Map<UUID, MinecraftPortal> ruleSources = new HashMap<>();
    private final Map<UUID, Pin> pinned = new HashMap<>();
    private final EntityHost host = new EntityHost();
    private final ArrivalHost arrivalHost = new ArrivalHost();
    private final MinecraftConvoys convoys;
    private final TraversalEntityTransit<Entity, PlaneCrossing> transit;
    private final OutboundEntityTransfers<Entity, PlaneCrossing> outbound;
    private final InboundEntityTransfers<Entity, MinecraftPortal, PlaneCrossing, Vec3d> inbound;
    private boolean closed;

    public MinecraftEntityTransfers(WormholesModRuntime runtime, NetworkManager network) {
        this.runtime = runtime;
        transit = new TraversalEntityTransit<>(new TraversalEntityTransit.Options(this::liveTransfer, failures), host);
        outbound = new OutboundEntityTransfers<>(new OutboundEntityTransfers.Options<>(network, locks, failures, transit,
            () -> closed, lifecycle.readLock(), completed, System::currentTimeMillis), host);
        inbound = new InboundEntityTransfers<>(new InboundEntityTransfers.Options(network, failures, () -> closed,
            lifecycle.readLock(), System::currentTimeMillis), arrivalHost);
        convoys = new MinecraftConvoys(runtime, network, this);
    }

    public void start() {
        runtime.requireServerThread();
        for (ServerLevel level : runtime.server().getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                reconcile(entity);
            }
        }
    }

    public boolean begin(Entity entity, MinecraftPortal source, PlaneCrossing crossing, NetworkMember destination) {
        runtime.requireServerThread();
        if (closed || entity instanceof ServerPlayer || !entity.isAlive() || !source.isOpen()
            || !source.isOutgoingTraversalsEnabled() || destination == null
            || entity.isPassenger() || entity.isVehicle() || entity instanceof Leashable leashable && leashable.isLeashed()) {
            return false;
        }
        if (!runtime.rules().screeningAllowed(entity, source)
            || !runtime.rules().depart(entity, source, () -> begin(entity, source, crossing, destination))
            || !runtime.rules().reserve(entity, source)) {
            runtime.rules().failed(entity);
            return false;
        }
        ruleSources.put(entity.getUUID(), source);
        boolean started = outbound.begin(new OutboundEntityTransfers.Request<>(entity, destination.serverName(),
            destination.portalId(), source.getId(), crossing, runtime.configuration().settings().getNetwork().handoffTimeoutMs));
        if (!started) {
            ruleSources.remove(entity.getUUID());
            runtime.rules().failed(entity);
        }
        return started;
    }

    public boolean locked(UUID entityId) {
        return locks.isLocked(entityId, System.currentTimeMillis()) || convoys.locked(entityId);
    }

    public boolean beginConvoy(Entity entity, MinecraftPortal source, PlaneCrossing crossing, NetworkMember destination) {
        return convoys.begin(entity, source, crossing, destination);
    }

    public boolean convoyPending(UUID playerId) {
        return convoys.pending(playerId);
    }

    public void playerPlaced(ServerPlayer player, MinecraftPortal portal, PlaneCrossing crossing) {
        convoys.playerPlaced(player, portal, crossing);
    }

    public void playerDispatched(UUID playerId) {
        convoys.playerDispatched(playerId);
    }

    public void playerFailed(UUID playerId, String reason) {
        convoys.playerFailed(playerId, reason);
    }

    TraversalEntityTransit.TransitState captureState(Entity entity) {
        return transit.capture(entity);
    }

    void freeze(Entity entity, BooleanSupplier pending) {
        transit.markInTransit(entity, pending);
    }

    void restore(Entity entity, TraversalEntityTransit.TransitState state) {
        transit.restoreRejected(entity, state, null, null);
    }

    void settle(Entity entity, MinecraftPortal portal, PlaneCrossing crossing) {
        arrivalHost.settle(portal, entity, crossing);
    }

    Vec3d target(MinecraftPortal portal, WireTraversive crossing) {
        return arrivalHost.target(portal, crossing).position();
    }

    Entity spawn(MinecraftPortal portal, byte[] snapshot, Vec3d point) {
        return arrivalHost.spawn(portal, snapshot, point);
    }

    public void receive(String peer, WireMessage message) {
        runtime.requireServerThread();
        switch (message) {
            case WireMessage.EntityTransfer transfer -> inbound.receive(peer, transfer);
            case WireMessage.EntityTransferAck ack -> outbound.acknowledge(peer, ack);
            default -> convoys.receive(peer, message);
        }
    }

    public void reconcile(Entity entity) {
        runtime.requireServerThread();
        if (!closed && !(entity instanceof ServerPlayer)) {
            if (!convoys.reconcile(entity)) {
                transit.reconcileLoadedEntity(entity);
            }
        }
    }

    public void tick() {
        runtime.requireServerThread();
        if (closed) {
            return;
        }
        convoys.tick();
        for (Pin pin : List.copyOf(pinned.values())) {
            if (pin.entity().isRemoved()) {
                pinned.remove(pin.entity().getUUID());
                continue;
            }
            pin.entity().setDeltaMovement(Vec3.ZERO);
            pin.entity().setPos(pin.position());
        }
        for (OutboundEntityTransfers.Pending<Entity, PlaneCrossing> pending : outbound.pending().values()) {
            runtime.rules().retain(pending.entity());
        }
        outbound.prunePendingEntityTransfers();
        transit.drainQueuedTransitRestores();
        inbound.retryAcknowledgements();
        long now = System.currentTimeMillis();
        for (Preparation preparation : List.copyOf(preparations.values())) {
            if (preparation.deadline() <= now) {
                finishPreparation(preparation, false);
            }
        }
    }

    public long completed() {
        return completed.get();
    }

    public long failed() {
        return failures.failed();
    }

    @Override
    public void close() {
        runtime.requireServerThread();
        lifecycle.writeLock().lock();
        try {
            closed = true;
            convoys.close();
            for (OutboundEntityTransfers.Pending<Entity, PlaneCrossing> pending : outbound.pending().values()) {
                transit.restoreRejectedForShutdown(pending.entity(), pending.transitState(), pending.sourcePortalId(), pending.traversive());
            }
            outbound.pending().clear();
            locks.clear();
            for (Preparation preparation : List.copyOf(preparations.values())) {
                finishPreparation(preparation, false);
            }
            inbound.clear();
            pinned.clear();
            ruleSources.clear();
        } finally {
            lifecycle.writeLock().unlock();
        }
    }

    private boolean liveTransfer(UUID entityId) {
        if (convoys != null && convoys.locked(entityId)) {
            return true;
        }
        for (OutboundEntityTransfers.Pending<Entity, PlaneCrossing> pending : outbound.pending().values()) {
            if (pending.entity().getUUID().equals(entityId)) {
                return true;
            }
        }
        return false;
    }

    private void finishPreparation(Preparation preparation, boolean ready) {
        if (!preparations.remove(preparation.transferId(), preparation)) {
            return;
        }
        try {
            if (ready && !closed && preparation.deadline() > System.currentTimeMillis()) {
                preparation.task().run().run();
            } else {
                preparation.task().rejected().run();
            }
        } finally {
            preparation.lease().close();
        }
    }

    private boolean schedule(Entity entity, TraversalEntityTransit.Task task) {
        if (task.delayTicks() <= 0L) {
            if (!entity.isRemoved()) {
                task.run().run();
            } else if (task.retired() != null) {
                task.retired().run();
            }
            return true;
        }
        return runtime.schedule(() -> {
            if (!entity.isRemoved()) {
                task.run().run();
            } else if (task.retired() != null) {
                task.retired().run();
            }
        }, task.delayTicks());
    }

    private void reject(Entity entity, UUID portalId, PlaneCrossing crossing) {
        MinecraftPortal source = portalId == null ? null : runtime.portals().get(portalId);
        if (source == null || crossing == null || entity.isRemoved() || entity.level() != runtime.portals().resolveLevel(source)) {
            return;
        }
        Vec3d point = crossing.rejectionPoint();
        entity.teleportTo(point.x(), point.y(), point.z());
        double strength = 3.0D * runtime.configuration().settings().getMain().portalPushbackMultiplier;
        entity.setDeltaMovement(new Vec3(crossing.frame().getNormal().x() * strength,
            crossing.frame().getNormal().y() * strength, crossing.frame().getNormal().z() * strength));
        runtime.portals().recordArrival(entity, source);
    }

    private static Vec3d geometry(Vec3 vector) {
        return new Vec3d(vector.x, vector.y, vector.z);
    }

    private static Vec3 vector(Vec3d vector) {
        return new Vec3(vector.x(), vector.y(), vector.z());
    }

    private static CompoundTag data(Entity entity) {
        CustomData data = entity.get(DataComponents.CUSTOM_DATA);
        return data == null ? new CompoundTag() : data.copyTag();
    }

    private static void stamp(Entity entity, Byte stamp) {
        CompoundTag data = data(entity);
        if (stamp == null) {
            data.remove(STAMP_KEY);
        } else {
            data.putByte(STAMP_KEY, stamp.byteValue());
        }
        entity.setComponent(DataComponents.CUSTOM_DATA, CustomData.of(data));
    }

    private final class EntityHost implements TraversalEntityTransit.Host<Entity, PlaneCrossing>,
        OutboundEntityTransfers.Host<Entity, PlaneCrossing> {
        public UUID id(Entity entity) { return entity.getUUID(); }
        public boolean valid(Entity entity) { return !entity.isRemoved(); }
        public boolean player(Entity entity) { return entity instanceof ServerPlayer; }
        public String description(Entity entity) { return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(); }
        public WireTraversive wire(PlaneCrossing crossing) { return WireTraversive.fromCrossing(crossing); }
        public void clearInFlight(UUID entityId) { locks.unlock(entityId); }
        public void remove(Entity entity) {
            MinecraftPortal source = ruleSources.remove(entity.getUUID());
            if (source != null) {
                runtime.rules().dispatched(entity, source);
            }
            pinned.remove(entity.getUUID());
            entity.discard();
        }
        public boolean schedule(Entity entity, TraversalEntityTransit.Task task) { return MinecraftEntityTransfers.this.schedule(entity, task); }
        public void reject(Entity entity, UUID portalId, PlaneCrossing crossing) { MinecraftEntityTransfers.this.reject(entity, portalId, crossing); }
        public void rejectDeparture(Entity entity, TraversalEntityTransit.Rejection<PlaneCrossing> rejection) {
            reject(entity, rejection.sourcePortalId(), rejection.traversive());
        }
        public TraversalEntityTransit.TransitState capture(Entity entity) {
            return new TraversalEntityTransit.TransitState(entity.isPermanentlyInvulnerable(), entity.isSilent(), !entity.isNoGravity(), geometry(entity.getDeltaMovement()));
        }
        public void freeze(Entity entity, byte flags) {
            pinned.put(entity.getUUID(), new Pin(entity, entity.position()));
            MinecraftEntityTransfers.stamp(entity, Byte.valueOf(flags));
            entity.setPermanentlyInvulnerable(true);
            entity.setSilent(true);
            entity.setNoGravity(true);
            entity.setDeltaMovement(Vec3.ZERO);
        }
        public void restore(Entity entity, TraversalEntityTransit.TransitState state) {
            pinned.remove(entity.getUUID());
            ruleSources.remove(entity.getUUID());
            runtime.rules().failed(entity);
            entity.setPermanentlyInvulnerable(state.invulnerable());
            entity.setSilent(state.silent());
            entity.setNoGravity(!state.gravity());
            entity.setDeltaMovement(vector(state.velocity()));
            MinecraftEntityTransfers.stamp(entity, null);
        }
        public Byte stamp(Entity entity) { return data(entity).getByte(STAMP_KEY).orElse(null); }
        public void restoreStamp(Entity entity, byte flags) {
            entity.setPermanentlyInvulnerable(TraversalEntityTransit.stampInvulnerable(flags));
            entity.setSilent(TraversalEntityTransit.stampSilent(flags));
            entity.setNoGravity(!TraversalEntityTransit.stampGravity(flags));
            MinecraftEntityTransfers.stamp(entity, null);
        }
        public byte[] snapshot(Entity entity) {
            try {
                return MinecraftEntitySnapshots.capture(entity);
            } catch (RuntimeException error) {
                LOGGER.error("Could not snapshot Wormholes entity {}", entity.getUUID(), error);
                return null;
            }
        }
    }

    private final class ArrivalHost implements InboundEntityTransfers.Host<Entity, MinecraftPortal, PlaneCrossing, Vec3d> {
        public MinecraftPortal exit(UUID portalId) { return runtime.portals().get(portalId); }
        public boolean available(MinecraftPortal portal) { return portal.isOpen() && runtime.portals().resolveLevel(portal) != null; }
        public boolean acceptsPortal(MinecraftPortal portal) { return TraversalAdmissionPolicy.acceptsInbound(portal); }
        public UUID id(Entity entity) { return entity.getUUID(); }
        public boolean valid(Entity entity) { return !entity.isRemoved(); }
        public void remove(Entity entity) {
            for (Entity member : entity.getSelfAndPassengers().toList()) {
                member.discard();
            }
        }
        public void failure(String peer, Throwable error) { LOGGER.error("Could not receive Wormholes entity from {}", peer, error); }
        public InboundEntityTransfers.Target<PlaneCrossing, Vec3d> target(MinecraftPortal portal, WireTraversive wire) {
            PlaneCrossing crossing = wire.crossing();
            Vec3d target = crossing.outPoint(portal.getFrame(), portal.getOrigin());
            ServerLevel level = runtime.portals().resolveLevel(portal);
            if (!Double.isFinite(target.x()) || !Double.isFinite(target.y()) || !Double.isFinite(target.z())
                || target.y() < level.getMinY() || target.y() >= level.getMaxY()
                || Math.abs(target.x()) > 29_999_984 || Math.abs(target.z()) > 29_999_984) {
                throw new IllegalArgumentException("Entity arrival position is outside destination bounds");
            }
            return new InboundEntityTransfers.Target<>(crossing, target);
        }
        public boolean schedule(InboundEntityTransfers.Arrival<MinecraftPortal, PlaneCrossing, Vec3d> arrival,
                                InboundEntityTransfers.Task task) {
            ServerLevel level = runtime.portals().resolveLevel(arrival.exit());
            Vec3d target = arrival.target().position();
            UUID world = UUID.nameUUIDFromBytes(arrival.exit().getWorldKey().getBytes(StandardCharsets.UTF_8));
            ChunkLease lease = runtime.leases().retain(level, world, target.getBlockX() >> 4, target.getBlockZ() >> 4);
            Preparation preparation = new Preparation(arrival.transfer().transferId(), lease, task,
                System.currentTimeMillis() + runtime.configuration().settings().getNetwork().handoffTimeoutMs);
            preparations.put(preparation.transferId(), preparation);
            lease.ready().whenComplete((ready, error) -> runtime.server().execute(() -> {
                if (error != null) {
                    LOGGER.error("Could not prepare Wormholes entity arrival {}", preparation.transferId(), error);
                }
                finishPreparation(preparation, error == null && Boolean.TRUE.equals(ready));
            }));
            return true;
        }
        public Entity spawn(InboundEntityTransfers.Arrival<MinecraftPortal, PlaneCrossing, Vec3d> arrival) {
            return spawn(arrival.exit(), arrival.transfer().entitySnapshot(), arrival.target().position());
        }
        private Entity spawn(MinecraftPortal portal, byte[] snapshot, Vec3d point) {
            if (runtime.portals().get(portal.getId()) != portal || !available(portal) || !acceptsPortal(portal)) {
                return null;
            }
            ServerLevel level = runtime.portals().resolveLevel(portal);
            Entity entity = MinecraftEntitySnapshots.create(snapshot, level);
            if (entity == null) {
                return null;
            }
            for (Entity member : entity.getSelfAndPassengers().toList()) {
                String type = BuiltInRegistries.ENTITY_TYPE.getKey(member.getType()).getPath().toUpperCase(Locale.ROOT);
                if (member instanceof ServerPlayer || TraversalAdmissionPolicy.isEntityTypeDenied(type,
                    runtime.configuration().settings().getNetwork().entityTransferDenyTypes)) {
                    remove(entity);
                    return null;
                }
            }
            entity.snapTo(point.x(), point.y(), point.z(), entity.getYRot(), entity.getXRot());
            if (!level.tryAddFreshEntityWithPassengers(entity)) {
                remove(entity);
                return null;
            }
            return entity;
        }
        public boolean acceptsEntity(MinecraftPortal portal, Entity entity) {
            return entity != null && entity.isAlive() && runtime.portals().get(portal.getId()) == portal
                && available(portal) && acceptsPortal(portal) && runtime.rules().arrivalAllowed(entity, portal, false);
        }
        public void settle(MinecraftPortal portal, Entity entity, PlaneCrossing crossing) {
            TransitConfig config = runtime.configuration().settings().getTransit();
            MomentumPolicy momentum = MomentumPolicy.decode((String) portal.setting("transit.momentum"));
            if (momentum == null) {
                momentum = MomentumPolicy.of(MomentumPolicy.Mode.parse(config.momentumDefault, MomentumPolicy.Mode.PRESERVE));
            }
            Vec3d velocity = ArrivalMomentum.apply(crossing.outVelocity(portal.getFrame()), momentum.rule(), config.momentumMaxSpeed);
            Angles.Look look = ArrivalOrientation.apply(crossing, portal.getFrame(),
                OrientationPolicy.parse((String) portal.setting("transit.orientation"), OrientationPolicy.parse(config.orientationDefault, OrientationPolicy.FRAME)).rule(),
                config.gravityFlipEnabled);
            entity.setYRot(look.yaw());
            entity.setXRot(look.pitch());
            entity.setDeltaMovement(vector(velocity));
            for (Entity member : entity.getSelfAndPassengers().toList()) {
                runtime.portals().recordArrival(member, portal);
                runtime.rules().arrived(member, portal);
            }
        }
    }

    private record Pin(Entity entity, Vec3 position) {
    }

    private record Preparation(UUID transferId, ChunkLease lease, InboundEntityTransfers.Task task, long deadline) {
    }
}
