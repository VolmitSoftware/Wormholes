package art.arcane.optics.fidelity;


import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;


import art.arcane.optics.claim.ProjectedBlockClaim;
import art.arcane.optics.math.CellKeys;
import art.arcane.optics.view.ContentView;

/**
 * Per (portal, observer) biome retint channel. After every claim submission it recomputes which chunk
 * sections the projected cells dominate, resolves the destination biome of each covered quart cell and
 * hands the overrides to the claim arbiter, which owns the restore path.
 */
public final class AtmosphereChannel<B, V extends ContentView<?, ?>> {
    private static final int REFRESH_INTERVAL_PASSES = 40;

    private final Long2IntOpenHashMap remoteBiomeIds;
    private V cachedView;
    private long cachedRevision;
    private int passesSinceRefresh;
    private boolean active;

    public AtmosphereChannel() {
        this.remoteBiomeIds = new Long2IntOpenHashMap(256);
        this.remoteBiomeIds.defaultReturnValue(Integer.MIN_VALUE);
        this.cachedRevision = Long.MIN_VALUE;
        this.passesSinceRefresh = REFRESH_INTERVAL_PASSES;
    }

    public Long2IntOpenHashMap update(Scan<B, V> scan, BiomeIdResolver ids, FidelityOptions fidelity) {
        V destView = scan.destination();
        boolean claimsChanged = scan.claimsChanged();
        if (destView != cachedView || destView.getRevision() != cachedRevision) {
            remoteBiomeIds.clear();
            cachedView = destView;
            cachedRevision = destView.getRevision();
            claimsChanged = true;
        }
        passesSinceRefresh++;
        if (!claimsChanged && passesSinceRefresh < REFRESH_INTERVAL_PASSES) {
            return null;
        }
        passesSinceRefresh = 0;
        Long2IntOpenHashMap overrides = AtmosphereDominance.compute(scan.claims(), fidelity.biomeDominance(),
            remoteKey -> resolve(destView, ids, remoteKey));
        if (overrides.isEmpty() && !active) {
            return null;
        }
        active = !overrides.isEmpty();
        return overrides;
    }

    public boolean disable() {
        if (!active) {
            return false;
        }
        active = false;
        return true;
    }

    public boolean isActive() {
        return active;
    }

    private int resolve(V destView, BiomeIdResolver ids, long remoteKey) {
        int rx = CellKeys.unpackX(remoteKey);
        int ry = CellKeys.unpackY(remoteKey);
        int rz = CellKeys.unpackZ(remoteKey);
        long quart = CellKeys.pack(rx >> 2, ry >> 2, rz >> 2);
        int cached = remoteBiomeIds.get(quart);
        if (cached != Integer.MIN_VALUE) {
            return cached;
        }
        String biome = destView.sampleBiome(rx, ry, rz);
        int id = biome == null ? -1 : ids.id(biome);
        remoteBiomeIds.put(quart, id);
        return id;
    }
    public record Scan<B, V>(Long2ObjectMap<ProjectedBlockClaim<B, V>> claims, V destination, boolean claimsChanged) {
    }
}
