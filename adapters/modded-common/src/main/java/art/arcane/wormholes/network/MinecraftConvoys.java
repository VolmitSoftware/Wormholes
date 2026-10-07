package art.arcane.wormholes.network;

import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.nexus.NetworkMember;
import art.arcane.optics.plate.ChunkLease;
import art.arcane.wormholes.config.toml.TransitConfig;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.network.convoy.ConvoyArrivalPlacer;
import art.arcane.wormholes.network.convoy.ConvoyLedger;
import art.arcane.wormholes.network.convoy.ConvoyManifest;
import art.arcane.wormholes.network.convoy.ConvoyTransferService;
import art.arcane.optics.crossing.PlaneCrossing;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

final class MinecraftConvoys implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");
    private static final String HELD_KEY = "wormholes:convoy_held";
    private static final String DISPATCHED_KEY = "wormholes:convoy_dispatched";

    private final WormholesModRuntime runtime;
    private final NetworkManager network;
    private final MinecraftEntityTransfers entities;
    private final Map<UUID, TraversalEntityTransit.TransitState> frozen = new HashMap<>();
    private final Map<UUID, Hold> held = new HashMap<>();
    private final Map<UUID, MinecraftPortal> ruleSources = new HashMap<>();
    private final Map<UUID, List<ConvoyTransferService.Member<Entity>>> departing = new HashMap<>();
    private final Map<UUID, Preparation> preparations = new HashMap<>();
    private final ConvoyTransferService<Entity, ServerPlayer, List<ConvoyTransferService.Member<Entity>>, NetworkMember, PlaneCrossing, MinecraftPortal> transfers;
    private final ConvoyArrivalPlacer<Entity, MinecraftPortal, PlaneCrossing, Destination> arrivals;
    private boolean closed;

    MinecraftConvoys(WormholesModRuntime runtime, NetworkManager network, MinecraftEntityTransfers entities) {
        this.runtime = runtime;
        this.network = network;
        this.entities = entities;
        transfers = new ConvoyTransferService<>(new ConvoyLedger(), new Transport(), new Rig(), System::currentTimeMillis);
        arrivals = new ConvoyArrivalPlacer<>(new ConvoyLedger(), new Spawner(), network::send, System::currentTimeMillis);
    }

    boolean begin(Entity seed, MinecraftPortal source, PlaneCrossing crossing, NetworkMember destination) {
        runtime.requireServerThread();
        List<ConvoyTransferService.Member<Entity>> members = members(seed);
        if (members.size() < 2) {
            return false;
        }
        TransitConfig config = runtime.configuration().settings().getTransit();
        ServerPlayer player = null;
        int players = 0;
        for (ConvoyTransferService.Member<Entity> member : members) {
            if (member.entity() instanceof ServerPlayer rider) {
                player = rider;
                players++;
            }
        }
        if (closed || !config.convoyEnabled || members.size() > config.convoyMaxEntities || players != 1
            || !source.isOpen() || !source.isOutgoingTraversalsEnabled() || player == null
            || !runtime.portals().canDepart(player, source)) {
            reject(seed, source, crossing);
            return true;
        }
        for (ConvoyTransferService.Member<Entity> member : members) {
            if (!member.entity().isAlive() || entities.locked(member.entity().getUUID())
                || member.entity().level() != seed.level()) {
                reject(seed, source, crossing);
                return true;
            }
        }
        for (ConvoyTransferService.Member<Entity> member : members) {
            if (!runtime.rules().screeningAllowed(member.entity(), source)) {
                reject(seed, source, crossing);
                return true;
            }
        }
        for (ConvoyTransferService.Member<Entity> member : members) {
            if (!runtime.rules().depart(member.entity(), source, () -> begin(seed, source, crossing, destination))) {
                return true;
            }
        }
        for (ConvoyTransferService.Member<Entity> member : members) {
            if (!(member.entity() instanceof ServerPlayer) && !runtime.rules().reserve(member.entity(), source)) {
                for (ConvoyTransferService.Member<Entity> candidate : members) {
                    runtime.rules().failed(candidate.entity());
                }
                reject(seed, source, crossing);
                return true;
            }
        }
        for (ConvoyTransferService.Member<Entity> member : members) {
            ruleSources.put(member.entity().getUUID(), source);
        }
        departing.put(player.getUUID(), members);
        if (!transfers.begin(player, members, destination, crossing, source,
            Math.max(1L, config.convoyCrossServerTimeoutSec) * 1000L)) {
            departing.remove(player.getUUID());
        }
        return true;
    }

    boolean pending(UUID playerId) {
        return transfers.ledger().findByPlayer(playerId) != null;
    }

    boolean locked(UUID entityId) {
        if (frozen.containsKey(entityId) || held.containsKey(entityId)) {
            return true;
        }
        for (ConvoyLedger.Group group : transfers.ledger().inFlight()) {
            if (group.members().contains(entityId)) {
                return true;
            }
        }
        return false;
    }

    void receive(String peer, WireMessage message) {
        if (closed) {
            return;
        }
        switch (message) {
            case WireMessage.ConvoyTransfer transfer -> arrivals.admit(peer, transfer.manifest(),
                Math.max(1L, runtime.configuration().settings().getTransit().convoyCrossServerTimeoutSec) * 1000L);
            case WireMessage.ConvoyAck ack -> transfers.onAck(peer, ack);
            case WireMessage.HandoffResult result -> {
                transfers.onHandoffResult(peer, result);
                if (transfers.ledger().findByPlayer(result.playerId()) == null) {
                    departing.remove(result.playerId());
                    ruleSources.remove(result.playerId());
                }
            }
            default -> {
            }
        }
    }

    void playerPlaced(ServerPlayer player, MinecraftPortal portal, PlaneCrossing crossing) {
        arrivals.onPlayerPlaced(player, portal, crossing);
    }

    void playerDispatched(UUID playerId) {
        List<ConvoyTransferService.Member<Entity>> members = departing.get(playerId);
        if (members == null) {
            return;
        }
        for (ConvoyTransferService.Member<Entity> member : members) {
            if (!(member.entity() instanceof ServerPlayer)) {
                CompoundTag data = data(member.entity());
                data.putBoolean(DISPATCHED_KEY, true);
                member.entity().setComponent(DataComponents.CUSTOM_DATA, CustomData.of(data));
            }
        }
    }

    void playerFailed(UUID playerId, String reason) {
        transfers.playerFailed(playerId, reason);
    }

    boolean reconcile(Entity entity) {
        if (data(entity).getBoolean(DISPATCHED_KEY).orElse(false) && !frozen.containsKey(entity.getUUID())
            || data(entity).getBoolean(HELD_KEY).orElse(false) && !held.containsKey(entity.getUUID())) {
            entity.discard();
            return true;
        }
        return false;
    }

    void tick() {
        arrivals.expire();
        for (Hold hold : held.values()) {
            if (!hold.entity().isRemoved()) {
                hold.entity().setInvisible(true);
                hold.entity().setDeltaMovement(Vec3.ZERO);
                hold.entity().setPos(hold.position());
            }
        }
        departing.entrySet().removeIf(entry -> {
            if (transfers.ledger().findByPlayer(entry.getKey()) != null) {
                return false;
            }
            ruleSources.remove(entry.getKey());
            return true;
        });
        for (List<ConvoyTransferService.Member<Entity>> members : departing.values()) {
            for (ConvoyTransferService.Member<Entity> member : members) {
                runtime.rules().retain(member.entity());
            }
        }
        long now = System.currentTimeMillis();
        for (Preparation preparation : List.copyOf(preparations.values())) {
            if (now >= preparation.deadline()) {
                finish(preparation, false);
            }
        }
        transfers.ledger().prune(now, 60_000L);
        arrivals.ledger().prune(now, 60_000L);
    }

    @Override
    public void close() {
        closed = true;
        transfers.close();
        arrivals.close();
        for (Preparation preparation : List.copyOf(preparations.values())) {
            finish(preparation, false);
        }
        departing.clear();
        ruleSources.clear();
    }

    private List<ConvoyTransferService.Member<Entity>> members(Entity seed) {
        int maximum = Math.min(ConvoyManifest.MAX_MEMBERS, runtime.configuration().settings().getTransit().convoyMaxEntities);
        List<Entity> nearby = seed.level().getEntities(seed, seed.getBoundingBox().inflate(32), Entity::isAlive);
        Map<UUID, Entity> found = new LinkedHashMap<>();
        ArrayDeque<Entity> queue = new ArrayDeque<>();
        found.put(seed.getUUID(), seed);
        queue.add(seed);
        while (!queue.isEmpty() && found.size() <= maximum) {
            Entity current = queue.removeFirst();
            List<Entity> linked = new ArrayList<>(current.getPassengers());
            if (current.getVehicle() != null) {
                linked.add(current.getVehicle());
            }
            Entity holder = holder(current);
            if (holder != null) {
                linked.add(holder);
            }
            for (Entity candidate : nearby) {
                if (holder(candidate) == current) {
                    linked.add(candidate);
                }
            }
            for (Entity entity : linked) {
                if (found.putIfAbsent(entity.getUUID(), entity) == null) {
                    queue.add(entity);
                }
            }
        }
        List<ConvoyTransferService.Member<Entity>> members = new ArrayList<>(found.size());
        for (Entity entity : found.values()) {
            members.add(new ConvoyTransferService.Member<>(entity, entity.getVehicle(), holder(entity)));
        }
        return members;
    }

    private void finish(Preparation preparation, boolean ready) {
        if (!preparations.remove(preparation.id(), preparation)) {
            return;
        }
        try {
            if (ready && !closed && System.currentTimeMillis() < preparation.deadline()) {
                preparation.task().run();
            } else {
                preparation.rejected().run();
            }
        } finally {
            preparation.lease().close();
        }
    }

    private void reject(Entity entity, MinecraftPortal source, PlaneCrossing crossing) {
        if (entity.isRemoved()) {
            return;
        }
        Vec3d point = crossing.rejectionPoint();
        entity.teleportTo(point.x(), point.y(), point.z());
        entity.setDeltaMovement(Vec3.ZERO);
        runtime.portals().recordArrival(entity, source);
    }

    private static Entity holder(Entity entity) {
        return entity instanceof Leashable leashable ? leashable.getLeashHolder() : null;
    }

    private static CompoundTag data(Entity entity) {
        CustomData custom = entity.get(DataComponents.CUSTOM_DATA);
        return custom == null ? new CompoundTag() : custom.copyTag();
    }

    private static void held(Entity entity, boolean value) {
        CompoundTag data = data(entity);
        if (value) {
            data.putBoolean(HELD_KEY, true);
        } else {
            data.remove(HELD_KEY);
        }
        entity.setComponent(DataComponents.CUSTOM_DATA, CustomData.of(data));
    }

    private static Vec3d geometry(Vec3 vector) {
        return new Vec3d(vector.x, vector.y, vector.z);
    }

    private final class Transport implements ConvoyTransferService.Transport {
        public boolean peerReady(String peer) { return network.getPeer(peer) != null && network.isPeerReady(peer); }
        public boolean peerSupportsConvoy(String peer) { return network.peerSupports(peer, WireCapability.CONVOY); }
        public boolean send(String peer, WireMessage message) { return network.send(peer, message); }
    }

    private final class Rig implements ConvoyTransferService.Rig<Entity, ServerPlayer, List<ConvoyTransferService.Member<Entity>>, NetworkMember, PlaneCrossing, MinecraftPortal> {
        public UUID id(Entity entity) { return entity.getUUID(); }
        public UUID playerId(ServerPlayer player) { return player.getUUID(); }
        public String name(Entity entity) { return entity.getName().getString(); }
        public String playerName(ServerPlayer player) { return player.getGameProfile().name(); }
        public String peer(NetworkMember target) { return target.serverName(); }
        public UUID destination(NetworkMember target) { return target.portalId(); }
        public List<ConvoyTransferService.Member<Entity>> members(List<ConvoyTransferService.Member<Entity>> members) { return members; }
        public WireTraversive crossing(Entity entity, PlaneCrossing crossing) {
            return WireTraversive.fromCrossing(new PlaneCrossing(crossing.frame(), crossing.origin(), geometry(entity.position()),
                geometry(entity.getDeltaMovement()), geometry(entity.getLookAngle()), crossing.frontSide()));
        }
        public byte[] snapshot(Entity entity) { return MinecraftEntitySnapshots.captureMember(entity); }
        public void freeze(Entity entity, BooleanSupplier pending) {
            frozen.put(entity.getUUID(), entities.captureState(entity));
            entities.freeze(entity, pending);
        }
        public void restore(Entity entity) {
            CompoundTag data = data(entity);
            data.remove(DISPATCHED_KEY);
            entity.setComponent(DataComponents.CUSTOM_DATA, CustomData.of(data));
            ruleSources.remove(entity.getUUID());
            runtime.rules().failed(entity);
            TraversalEntityTransit.TransitState state = frozen.remove(entity.getUUID());
            if (state != null) {
                entities.restore(entity, state);
            }
        }
        public void remove(Entity entity) {
            MinecraftPortal source = ruleSources.remove(entity.getUUID());
            if (source != null) {
                runtime.rules().dispatched(entity, source);
            }
            frozen.remove(entity.getUUID());
            entity.discard();
        }
        public boolean dispatchPlayer(ServerPlayer player, NetworkMember tunnel, PlaneCrossing crossing, MinecraftPortal source) {
            player.stopRiding();
            player.ejectPassengers();
            return runtime.network().handoffs().begin(player, tunnel.serverName(), source, crossing, tunnel.portalId());
        }
        public void rejectSource(ServerPlayer player, MinecraftPortal source, PlaneCrossing crossing) {
            List<ConvoyTransferService.Member<Entity>> members = departing.remove(player.getUUID());
            runtime.rules().failed(player);
            ruleSources.remove(player.getUUID());
            reject(player, source, crossing);
            if (members != null) {
                for (ConvoyTransferService.Member<Entity> member : members) {
                    if (member.vehicle() != null && !member.entity().isRemoved() && !member.vehicle().isRemoved()) {
                        member.entity().startRiding(member.vehicle(), true, false);
                    }
                }
            }
        }
        public void clearInFlight(Entity entity) {
            ruleSources.remove(entity.getUUID());
            runtime.rules().failed(entity);
        }
        public void notice(ServerPlayer player, String reason) { player.sendSystemMessage(Component.literal("Convoy transfer failed: " + reason)); }
        public boolean schedule(Runnable task, long delayTicks) { return runtime.schedule(task, delayTicks); }
    }

    private final class Spawner implements ConvoyArrivalPlacer.Spawner<Entity, MinecraftPortal, PlaneCrossing, Destination> {
        public UUID id(Entity entity) { return entity.getUUID(); }
        public String name(Entity entity) { return entity.getName().getString(); }
        public PlaneCrossing crossing(WireTraversive crossing, Entity entity) { return crossing.crossing(); }
        public MinecraftPortal exit(UUID id) { return runtime.portals().get(id); }
        public boolean accepts(MinecraftPortal portal) {
            return !closed && runtime.configuration().settings().getTransit().convoyEnabled
                && runtime.portals().get(portal.getId()) == portal && portal.isOpen()
                && runtime.portals().resolveLevel(portal) != null && TraversalAdmissionPolicy.acceptsInbound(portal);
        }
        public Destination target(MinecraftPortal portal, WireTraversive crossing) {
            return new Destination(portal, entities.target(portal, crossing));
        }
        public Entity spawn(MinecraftPortal portal, byte[] snapshot, Destination target) {
            Entity entity = entities.spawn(portal, snapshot, target.point());
            if (entity != null && !runtime.rules().arrivalAllowed(entity, portal, false)) {
                entity.discard();
                return null;
            }
            return entity;
        }
        public void hold(Entity entity) {
            held.put(entity.getUUID(), new Hold(entity, entity.position(), entities.captureState(entity), entity.isInvisible()));
            held(entity, true);
            entity.setInvisible(true);
            entity.setPermanentlyInvulnerable(true);
            entity.setSilent(true);
            entity.setNoGravity(true);
            entity.setDeltaMovement(Vec3.ZERO);
        }
        public void reveal(Entity entity) {
            Hold hold = held.remove(entity.getUUID());
            if (hold != null) {
                held(entity, false);
                entity.setInvisible(hold.invisible());
                entity.setPermanentlyInvulnerable(hold.state().invulnerable());
                entity.setSilent(hold.state().silent());
                entity.setNoGravity(!hold.state().gravity());
            }
        }
        public void remove(Entity entity) {
            held.remove(entity.getUUID());
            entity.discard();
        }
        public void mount(Entity vehicle, Entity passenger) {
            if (!passenger.startRiding(vehicle, true, false)) {
                throw new IllegalStateException("Could not restore convoy passenger attachment");
            }
        }
        public void leash(Entity entity, Entity holder) {
            if (!(entity instanceof Leashable leashable)) {
                throw new IllegalStateException("Convoy member does not support a leash");
            }
            leashable.setLeashedTo(holder, true);
        }
        public void settle(MinecraftPortal portal, Entity entity, PlaneCrossing crossing) { entities.settle(entity, portal, crossing); }
        public boolean runRegion(Destination destination, Runnable task, Runnable rejected) {
            ServerLevel level = runtime.portals().resolveLevel(destination.portal());
            UUID world = UUID.nameUUIDFromBytes(destination.portal().getWorldKey().getBytes(StandardCharsets.UTF_8));
            ChunkLease lease = runtime.leases().retain(level, world, destination.point().blockX() >> 4, destination.point().blockZ() >> 4);
            Preparation preparation = new Preparation(UUID.randomUUID(), lease, task, rejected, System.currentTimeMillis()
                + Math.max(1L, runtime.configuration().settings().getTransit().convoyCrossServerTimeoutSec) * 1000L);
            preparations.put(preparation.id(), preparation);
            lease.ready().whenComplete((ready, error) -> runtime.server().execute(() -> {
                if (error != null) {
                    LOGGER.error("Could not prepare convoy destination", error);
                }
                finish(preparation, error == null && Boolean.TRUE.equals(ready));
            }));
            return true;
        }
        public boolean schedule(Runnable task, long delayTicks) { return runtime.schedule(task, delayTicks); }
    }

    private record Destination(MinecraftPortal portal, Vec3d point) {
    }

    private record Hold(Entity entity, Vec3 position, TraversalEntityTransit.TransitState state, boolean invisible) {
    }

    private record Preparation(UUID id, ChunkLease lease, Runnable task, Runnable rejected, long deadline) {
    }
}
