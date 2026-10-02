package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.wormholes.network.view.EntityDeltaCodec;
import art.arcane.wormholes.network.view.EntityVisual;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
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
    private int nextId;
    private long framesApplied;
    private long deltasWithoutBase;
    private long spawnFailures;

    public ClientProjectedEntities(ClientSceneWorld world) {
        this.world = Objects.requireNonNull(world, "world");
        this.portals = new Int2ObjectOpenHashMap<>(8);
        this.nextId = ClientEntityIds.PROJECTED_MAX;
    }

    public void apply(ClientViewMessage.EntityFrame frame) {
        Objects.requireNonNull(frame, "frame");
        PortalEntities state = portals.computeIfAbsent(frame.portalKey(), ignored -> new PortalEntities());
        List<EntityVisual> visuals = frame.entities();
        for (int index = 0; index < visuals.size(); index++) {
            EntityVisual incoming = visuals.get(index);
            Tracked tracked = state.tracked.get(incoming.id());
            if (!incoming.isFull() && tracked == null) {
                deltasWithoutBase++;
                continue;
            }
            EntityVisual merged = EntityDeltaCodec.applyDelta(incoming, tracked == null ? null : tracked.visual);
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
            Iterator<Map.Entry<UUID, Tracked>> iterator = state.tracked.entrySet().iterator();
            while (iterator.hasNext()) {
                Tracked tracked = iterator.next().getValue();
                if (!keep.contains(tracked.visual.id())) {
                    despawn(tracked);
                    iterator.remove();
                }
            }
        }
        framesApplied++;
    }

    public void tick(IntFunction<ClientPortal> lookup, IntPredicate meshPortal) {
        ObjectIterator<Int2ObjectMap.Entry<PortalEntities>> portalIterator = portals.int2ObjectEntrySet().fastIterator();
        while (portalIterator.hasNext()) {
            Int2ObjectMap.Entry<PortalEntities> entry = portalIterator.next();
            ClientPortal portal = lookup.apply(entry.getIntKey());
            boolean mesh = meshPortal.test(entry.getIntKey());
            for (Tracked tracked : entry.getValue().tracked.values()) {
                boolean visible = portal != null && (mesh || portal.ready() && inCone(portal, tracked.visual));
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
                    world.tick(tracked.entityId, mesh);
                }
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

    public void discard() {
        meshIds.clear();
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

    public EntityVisual visual(int portalKey, UUID id) {
        PortalEntities state = portals.get(portalKey);
        Tracked tracked = state == null ? null : state.tracked.get(id);
        return tracked == null ? null : tracked.visual;
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

    static boolean inCone(ClientPortal portal, EntityVisual visual) {
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
        byte[] metadata = tracked.visual.metadata();
        if (metadata != null && metadata.length > 0) {
            world.metadata(id, metadata);
        }
        byte[] equipment = tracked.visual.equipment();
        if (equipment != null && equipment.length > 0) {
            world.equipment(id, equipment);
        }
        tracked.syncedMetadata = metadata;
        tracked.syncedEquipment = equipment;
        tracked.synced = tracked.visual;
    }

    private void sync(Tracked tracked) {
        EntityVisual visual = tracked.visual;
        if (visual == tracked.synced) {
            return;
        }
        world.move(tracked.entityId, visual, tracked.synced);
        if (!Arrays.equals(visual.metadata(), tracked.syncedMetadata)) {
            world.metadata(tracked.entityId, visual.metadata());
            tracked.syncedMetadata = visual.metadata();
        }
        if (!Arrays.equals(visual.equipment(), tracked.syncedEquipment)) {
            world.equipment(tracked.entityId, visual.equipment());
            tracked.syncedEquipment = visual.equipment();
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
    }

    private static final class Tracked {
        private final UUID projectionId;
        private EntityVisual visual;
        private EntityVisual synced;
        private byte[] syncedMetadata;
        private byte[] syncedEquipment;
        private int entityId;

        private Tracked(int portalKey, EntityVisual visual) {
            this.projectionId = UUID.nameUUIDFromBytes(("wormholes:projection:" + portalKey + ":" + visual.id())
                .getBytes(StandardCharsets.UTF_8));
            this.visual = visual;
        }

        private void update(EntityVisual next) {
            visual = next;
        }
    }
}
