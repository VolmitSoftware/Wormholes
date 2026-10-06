package art.arcane.wormholes.render;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.bukkit.Location;
import org.bukkit.entity.Entity;

import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.player.Equipment;

import art.arcane.optics.math.Vec3d;
import org.bukkit.World;
import art.arcane.wormholes.Settings;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.optics.entity.CandidateCache;

final class EntityRenderCaches {
    private static final long ENTITY_STATE_REFRESH_MILLIS = 500L;
    private static final long STATIC_CACHE_EVICT_MILLIS = 10_000L;
    private static final CandidateCache<World, Entity> REMOTE_ENTITY_CACHE = new CandidateCache<>(BukkitEntityVisualHost.FEED);
    private static final CandidateCache<World, Entity> LOCAL_ENTITY_CACHE = new CandidateCache<>(BukkitEntityVisualHost.FEED);
    private static final Map<UUID, EntityStateSnapshot> ENTITY_STATE_CACHE = new ConcurrentHashMap<UUID, EntityStateSnapshot>();
    private static final AtomicLong STATIC_CACHE_SWEEP_DUE = new AtomicLong(0L);

    private EntityRenderCaches() {
    }

    static Collection<Entity> nearbyRemoteEntities(ILocalPortal portal, Location center, double range) {
        return nearbyEntities(REMOTE_ENTITY_CACHE, portal, center, range);
    }

    static Collection<Entity> nearbyLocalEntities(ILocalPortal portal, Location center, double range) {
        return nearbyEntities(LOCAL_ENTITY_CACHE, portal, center, range);
    }

    private static Collection<Entity> nearbyEntities(CandidateCache<World, Entity> cache, ILocalPortal portal, Location center, double range) {
        if (portal == null || portal.getId() == null || center == null || center.getWorld() == null) {
            return List.of();
        }
        return cache.nearby(new CandidateCache.Query<>(portal.getId(), center.getWorld(),
            new Vec3d(center.getX(), center.getY(), center.getZ()), range, Settings.ENTITY_CANDIDATE_CACHE_TICKS), System.currentTimeMillis());
    }

    static EntityStateSnapshot freshEntityState(UUID entityId, long now) {
        EntityStateSnapshot cached = ENTITY_STATE_CACHE.get(entityId);
        if (cached != null && now - cached.stampMillis <= ENTITY_STATE_REFRESH_MILLIS) {
            return cached;
        }
        return null;
    }

    static void putEntityState(UUID entityId, EntityStateSnapshot snapshot) {
        ENTITY_STATE_CACHE.put(entityId, snapshot);
    }

    static void sweepStaticCaches(long now) {
        long due = STATIC_CACHE_SWEEP_DUE.get();
        if (now < due || !STATIC_CACHE_SWEEP_DUE.compareAndSet(due, now + STATIC_CACHE_EVICT_MILLIS)) {
            return;
        }
        ENTITY_STATE_CACHE.values().removeIf(snapshot -> now - snapshot.stampMillis > STATIC_CACHE_EVICT_MILLIS);
    }

    static final class EntityStateSnapshot {
        private final long stampMillis;
        final List<EntityData<?>> metadata;
        final String metadataSig;
        final List<Equipment> equipment;
        final String equipmentSig;

        EntityStateSnapshot(long stampMillis, List<EntityData<?>> metadata, String metadataSig, List<Equipment> equipment, String equipmentSig) {
            this.stampMillis = stampMillis;
            this.metadata = metadata;
            this.metadataSig = metadataSig;
            this.equipment = equipment;
            this.equipmentSig = equipmentSig;
        }
    }

}
