package art.arcane.wormholes.modded.client;

import art.arcane.optics.stream.ViewStreamMessage;
import art.arcane.optics.entity.EntityDeltaCodec;
import art.arcane.optics.entity.EntitySnapshot;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.ArrayList;
import art.arcane.optics.entity.EntityAnimation;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;
import java.util.function.IntConsumer;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;

public final class ClientProjectedEntities {
    private final ClientSceneWorld world;
    private final Int2ObjectOpenHashMap<PortalEntities> portals;
    private final IntOpenHashSet meshIds = new IntOpenHashSet();
    private UUID hidden;
    private int nextId;
    private int clientTick;
    private long framesApplied;
    private long deltasWithoutBase;
    private long spawnFailures;

    public ClientProjectedEntities(ClientSceneWorld world) {
        this.world = Objects.requireNonNull(world, "world");
        this.portals = new Int2ObjectOpenHashMap<>(8);
        this.nextId = ClientEntityIds.PROJECTED_MAX;
    }

    public void apply(ViewStreamMessage.EntityFrame frame) {
        Objects.requireNonNull(frame, "frame");
        PortalEntities state = portals.computeIfAbsent(frame.portalKey(), ignored -> new PortalEntities());
        List<EntitySnapshot> visuals = frame.entities();
        for (int index = 0; index < visuals.size(); index++) {
            EntitySnapshot incoming = visuals.get(index);
            WormholesClient client = WormholesClient.instance();
            if (client != null && client.localMeshes().localEntity(frame.portalKey(), incoming.id())) {
                continue;
            }
            Tracked tracked = state.tracked.get(incoming.id());
            if (!incoming.isFull() && tracked == null) {
                deltasWithoutBase++;
                continue;
            }
            EntitySnapshot merged = EntityDeltaCodec.applyDelta(incoming, tracked == null ? null : tracked.visual);
            if (tracked == null) {
                tracked = new Tracked(frame.portalKey(), merged);
                state.tracked.put(merged.id(), tracked);
            } else {
                tracked.update(merged);
            }
        }
        if (frame.presence()) {
            List<UUID> present = frame.presentIds();
            Set<UUID> keep = new HashSet<>(Math.max(4, present.size() * 2));
            keep.addAll(present);
            state.events.removeIf(event -> !keep.contains(event.event.entityId()));
            Iterator<Map.Entry<UUID, Tracked>> iterator = state.tracked.entrySet().iterator();
            while (iterator.hasNext()) {
                Tracked tracked = iterator.next().getValue();
                if (!keep.contains(tracked.visual.id())) {
                    despawn(tracked);
                    iterator.remove();
                } else {
                    tracked.present = true;
                }
            }
        }
        framesApplied++;
    }

    public void apply(ViewStreamMessage.EntityEvent event) {
        WormholesClient client = WormholesClient.instance();
        if (client != null && client.localMeshes().localEntity(event.portalKey(), event.entityId())) {
            return;
        }
        PortalEntities state = portals.computeIfAbsent(event.portalKey(), ignored -> new PortalEntities());
        if (event.eventSeq() - state.eventSequence <= 0) {
            return;
        }
        state.eventSequence = event.eventSeq();
        if (state.events.size() < 128) {
            state.events.add(new PendingEvent(event, clientTick + 20));
        }
    }

    public void hide(UUID source) {
        hidden = source;
    }

    public void tick(IntFunction<ClientPortal> lookup, IntPredicate meshPortal) {
        clientTick++;
        ObjectIterator<Int2ObjectMap.Entry<PortalEntities>> portalIterator = portals.int2ObjectEntrySet().fastIterator();
        while (portalIterator.hasNext()) {
            Int2ObjectMap.Entry<PortalEntities> entry = portalIterator.next();
            ClientPortal portal = lookup.apply(entry.getIntKey());
            boolean mesh = meshPortal.test(entry.getIntKey());
            for (Tracked tracked : entry.getValue().tracked.values()) {
                boolean visible = portal != null && !tracked.visual.id().equals(hidden)
                    && (mesh || portal.ready() && inCone(portal, tracked.visual));
                if (!visible) {
                    despawn(tracked);
                    continue;
                }
                if (tracked.entityId == 0) {
                    spawn(tracked, mesh);
                } else {
                    if (mesh) {
                        meshIds.add(tracked.entityId);
                    } else {
                        meshIds.remove(tracked.entityId);
                    }
                    sync(tracked);
                }
                if (tracked.entityId != 0) {
                    deliverEvents(entry.getValue(), tracked);
                    world.tick(tracked.entityId, entry.getIntKey(), mesh);
                }
            }
            entry.getValue().events.removeIf(event -> event.expires < clientTick);
        }
    }

    private void deliverEvents(PortalEntities state, Tracked tracked) {
        Iterator<PendingEvent> iterator = state.events.iterator();
        while (iterator.hasNext()) {
            PendingEvent pending = iterator.next();
            ViewStreamMessage.EntityEvent event = pending.event;
            if (pending.expires < clientTick) {
                iterator.remove();
            } else if (event.entityId().equals(tracked.visual.id())) {
                world.event(tracked.entityId, new EntityAnimation(event.entityId(), event.hurt(), event.animation(), event.yaw()));
                iterator.remove();
            }
        }
    }

    public boolean hasMeshEntities() {
        return !meshIds.isEmpty();
    }

    public boolean meshEntity(int entityId) {
        return meshIds.contains(entityId);
    }

    public void forEachEntity(int portalKey, IntConsumer consumer) {
        PortalEntities state = portals.get(portalKey);
        if (state != null) {
            for (Tracked tracked : state.tracked.values()) {
                if (tracked.entityId != 0) {
                    consumer.accept(tracked.entityId);
                }
            }
        }
    }

    public void suppress(int portalKey, UUID source) {
        PortalEntities state = portals.get(portalKey);
        if (state == null) {
            return;
        }
        Tracked tracked = state.tracked.remove(source);
        if (tracked != null) {
            despawn(tracked);
        }
        state.events.removeIf(event -> event.event.entityId().equals(source));
    }

    public void drop(int portalKey) {
        PortalEntities state = portals.remove(portalKey);
        if (state == null) {
            return;
        }
        for (Tracked tracked : state.tracked.values()) {
            despawn(tracked);
        }
    }

    public void clear() {
        for (PortalEntities state : portals.values()) {
            for (Tracked tracked : state.tracked.values()) {
                despawn(tracked);
            }
        }
        portals.clear();
    }

    public int tracked() {
        int count = 0;
        for (PortalEntities state : portals.values()) {
            count += state.tracked.size();
        }
        return count;
    }

    public int spawned() {
        int count = 0;
        for (PortalEntities state : portals.values()) {
            for (Tracked tracked : state.tracked.values()) {
                if (tracked.entityId != 0) {
                    count++;
                }
            }
        }
        return count;
    }

    public int entityId(int portalKey, UUID id) {
        PortalEntities state = portals.get(portalKey);
        Tracked tracked = state == null ? null : state.tracked.get(id);
        return tracked == null ? 0 : tracked.entityId;
    }

    public EntitySnapshot visual(int portalKey, UUID id) {
        PortalEntities state = portals.get(portalKey);
        Tracked tracked = state == null ? null : state.tracked.get(id);
        return tracked == null ? null : tracked.visual;
    }

    public boolean presentPlayer(int portalKey, UUID id) {
        PortalEntities state = portals.get(portalKey);
        Tracked tracked = state == null ? null : state.tracked.get(id);
        return tracked != null && tracked.present && tracked.visual.isPlayer() && !id.equals(hidden);
    }

    public long framesApplied() {
        return framesApplied;
    }

    public long deltasWithoutBase() {
        return deltasWithoutBase;
    }

    public long spawnFailures() {
        return spawnFailures;
    }

    static boolean inCone(ClientPortal portal, EntitySnapshot visual) {
        int x = (int) Math.floor(visual.x());
        int y = (int) Math.floor(visual.y() + Math.max(0.0D, visual.height()) * 0.5D);
        int z = (int) Math.floor(visual.z());
        return portal.sweep().applied(x, y, z) || portal.sweep().applied(x, (int) Math.floor(visual.y()), z);
    }

    private void spawn(Tracked tracked, boolean mesh) {
        int id = nextId;
        nextId = ClientEntityIds.nextProjected(id);
        if (mesh) {
            meshIds.add(id);
        }
        if (!world.spawn(id, tracked.projectionId, tracked.visual)) {
            meshIds.remove(id);
            spawnFailures++;
            return;
        }
        tracked.entityId = id;
        byte[] equipment = tracked.visual.equipment();
        if (equipment != null && equipment.length > 0) {
            world.equipment(id, equipment);
        }
        byte[] metadata = tracked.visual.metadata();
        if (metadata != null && metadata.length > 0) {
            world.metadata(id, metadata);
        }
        tracked.syncedMetadata = metadata;
        tracked.syncedEquipment = equipment;
        tracked.synced = tracked.visual;
    }

    private void sync(Tracked tracked) {
        EntitySnapshot visual = tracked.visual;
        if (visual == tracked.synced) {
            return;
        }
        world.move(tracked.entityId, visual, tracked.synced);
        if (!Arrays.equals(visual.equipment(), tracked.syncedEquipment)) {
            world.equipment(tracked.entityId, visual.equipment());
            tracked.syncedEquipment = visual.equipment();
        }
        if (!Arrays.equals(visual.metadata(), tracked.syncedMetadata)) {
            world.metadata(tracked.entityId, visual.metadata());
            tracked.syncedMetadata = visual.metadata();
        }
        tracked.synced = visual;
    }

    private void despawn(Tracked tracked) {
        if (tracked.entityId == 0) {
            return;
        }
        world.remove(tracked.entityId, tracked.visual);
        meshIds.remove(tracked.entityId);
        tracked.entityId = 0;
        tracked.synced = null;
        tracked.syncedMetadata = null;
        tracked.syncedEquipment = null;
    }

    private static final class PortalEntities {
        private final HashMap<UUID, Tracked> tracked = new HashMap<>();
        private final ArrayList<PendingEvent> events = new ArrayList<>();
        private int eventSequence;
    }

    private record PendingEvent(ViewStreamMessage.EntityEvent event, int expires) {
    }

    private static final class Tracked {
        private final UUID projectionId;
        private EntitySnapshot visual;
        private EntitySnapshot synced;
        private byte[] syncedMetadata;
        private byte[] syncedEquipment;
        private int entityId;
        private boolean present;

        private Tracked(int portalKey, EntitySnapshot visual) {
            this.projectionId = UUID.nameUUIDFromBytes(("wormholes:projection:" + portalKey + ":" + visual.id())
                .getBytes(StandardCharsets.UTF_8));
            this.visual = visual;
        }

        private void update(EntitySnapshot next) {
            visual = next;
        }
    }
}
