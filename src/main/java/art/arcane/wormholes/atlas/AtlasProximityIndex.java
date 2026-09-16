package art.arcane.wormholes.atlas;

import art.arcane.wormholes.portal.ILocalPortal;
import org.bukkit.Location;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Chunk-bucketed portal centres. Discovery probes the player's chunk and its neighbours instead of
 * walking every portal on the server; the buckets are rebuilt on a slow cadence. Vanilla nether and
 * end portals Wormholes replaced are left out: they are not atlas destinations.
 */
final class AtlasProximityIndex {
    private volatile Map<Long, List<Anchor>> byChunk = Map.of();
    private volatile int portalCount;

    void rebuild(List<ILocalPortal> portals) {
        Map<Long, List<Anchor>> buckets = new HashMap<>();
        int indexed = 0;
        for (ILocalPortal portal : portals) {
            if (portal == null || portal.isDestroyed() || portal.getDimensionalPortalKind().isManagedPortal()) {
                continue;
            }
            Location center = portal.getCenter();
            if (center == null || center.getWorld() == null) {
                continue;
            }
            Anchor anchor = new Anchor(portal.getId(), center.getWorld().getUID(),
                    center.getX(), center.getY(), center.getZ());
            buckets.computeIfAbsent(chunkKey(center.getBlockX() >> 4, center.getBlockZ() >> 4),
                    ignored -> new ArrayList<>()).add(anchor);
            indexed++;
        }
        byChunk = Map.copyOf(buckets);
        portalCount = indexed;
    }

    int portalCount() {
        return portalCount;
    }

    /** Portal ids whose centre is within {@code radius} of the point. Empty when nothing is near. */
    List<UUID> near(UUID worldId, double x, double y, double z, double radius) {
        Map<Long, List<Anchor>> buckets = byChunk;
        if (buckets.isEmpty() || worldId == null) {
            return List.of();
        }
        int chunkRadius = Math.max(0, (int) Math.ceil(radius / 16.0D));
        int centerChunkX = ((int) Math.floor(x)) >> 4;
        int centerChunkZ = ((int) Math.floor(z)) >> 4;
        double radiusSquared = radius * radius;
        List<UUID> found = null;
        for (int chunkX = centerChunkX - chunkRadius; chunkX <= centerChunkX + chunkRadius; chunkX++) {
            for (int chunkZ = centerChunkZ - chunkRadius; chunkZ <= centerChunkZ + chunkRadius; chunkZ++) {
                List<Anchor> bucket = buckets.get(chunkKey(chunkX, chunkZ));
                if (bucket == null) {
                    continue;
                }
                for (int index = 0; index < bucket.size(); index++) {
                    Anchor anchor = bucket.get(index);
                    if (!anchor.worldId.equals(worldId) || anchor.distanceSquared(x, y, z) > radiusSquared) {
                        continue;
                    }
                    if (found == null) {
                        found = new ArrayList<>(2);
                    }
                    found.add(anchor.portalId);
                }
            }
        }
        return found == null ? List.of() : found;
    }

    void clear() {
        byChunk = Map.of();
        portalCount = 0;
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }

    private record Anchor(UUID portalId, UUID worldId, double x, double y, double z) {
        private double distanceSquared(double px, double py, double pz) {
            double dx = x - px;
            double dy = y - py;
            double dz = z - pz;
            return dx * dx + dy * dy + dz * dz;
        }
    }
}
