package art.arcane.wormholes.render;

import java.util.ArrayList;
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

import art.arcane.volmlib.util.bukkit.WorldIdentity;
import art.arcane.wormholes.Settings;
import art.arcane.wormholes.portal.ILocalPortal;

final class EntityRenderCaches {
    private static final long ENTITY_STATE_REFRESH_MILLIS = 500L;
    private static final long STATIC_CACHE_EVICT_MILLIS = 10_000L;
    private static final Map<CandidateKey, EntityCandidateSnapshot> REMOTE_ENTITY_CACHE = new ConcurrentHashMap<CandidateKey, EntityCandidateSnapshot>();
    private static final Map<CandidateKey, EntityCandidateSnapshot> LOCAL_ENTITY_CACHE = new ConcurrentHashMap<CandidateKey, EntityCandidateSnapshot>();
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

    private static Collection<Entity> nearbyEntities(Map<CandidateKey, EntityCandidateSnapshot> cache, ILocalPortal portal, Location center, double range) {
        if (portal == null || portal.getId() == null || center == null || center.getWorld() == null) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        sweepStaticCaches(now);
        int queryRange = candidateQueryRange(range);
        CandidateKey key = new CandidateKey(portal.getId(), queryRange);
        EntityCandidateSnapshot snapshot = cache.get(key);
        long maxAgeMillis = Math.max(1, Settings.ENTITY_CANDIDATE_CACHE_TICKS) * 50L;
        if (snapshot != null && snapshot.matches(center) && now - snapshot.createdAtMillis <= maxAgeMillis) {
            return snapshot.entities;
        }
        Collection<Entity> entities = center.getWorld().getNearbyEntities(center, queryRange, queryRange, queryRange);
        EntityCandidateSnapshot next = new EntityCandidateSnapshot(center, now, new ArrayList<Entity>(entities));
        cache.put(key, next);
        return next.entities;
    }

    static int candidateQueryRange(double range) {
        if (!Double.isFinite(range) || range <= 1.0D) {
            return 1;
        }
        return (int) Math.ceil(range);
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
        REMOTE_ENTITY_CACHE.values().removeIf(candidate -> now - candidate.createdAtMillis > STATIC_CACHE_EVICT_MILLIS);
        LOCAL_ENTITY_CACHE.values().removeIf(candidate -> now - candidate.createdAtMillis > STATIC_CACHE_EVICT_MILLIS);
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

    private record CandidateKey(UUID portalId, int rangeBlocks) {
    }

    private static final class EntityCandidateSnapshot {
        private final String worldKey;
        private final int centerBlockX;
        private final int centerBlockY;
        private final int centerBlockZ;
        private final long createdAtMillis;
        private final List<Entity> entities;

        private EntityCandidateSnapshot(Location center, long createdAtMillis, List<Entity> entities) {
            this.worldKey = WorldIdentity.serialize(center.getWorld());
            this.centerBlockX = center.getBlockX();
            this.centerBlockY = center.getBlockY();
            this.centerBlockZ = center.getBlockZ();
            this.createdAtMillis = createdAtMillis;
            this.entities = entities;
        }

        private boolean matches(Location center) {
            return worldKey.equals(WorldIdentity.serialize(center.getWorld()))
                && centerBlockX == center.getBlockX()
                && centerBlockY == center.getBlockY()
                && centerBlockZ == center.getBlockZ();
        }
    }
}
