package art.arcane.wormholes.render.atmosphere;

import java.util.UUID;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;

import org.bukkit.World;
import org.bukkit.entity.Player;

import art.arcane.wormholes.render.FidelitySettings;
import art.arcane.wormholes.render.ProjectedBlockClaim;
import art.arcane.wormholes.render.ProjectionCellKey;
import art.arcane.wormholes.render.ProjectionClaimArbiter;
import art.arcane.wormholes.render.view.ProjectionWorldView;

/**
 * Per (portal, observer) biome retint channel. After every claim submission it recomputes which chunk
 * sections the projected cells dominate, resolves the destination biome of each covered quart cell and
 * hands the overrides to the claim arbiter, which owns the restore path.
 */
public final class AtmosphereChannel {
    private static final int REFRESH_INTERVAL_PASSES = 40;

    private final Long2IntOpenHashMap remoteBiomeIds;
    private ProjectionWorldView cachedView;
    private long cachedRevision;
    private int passesSinceRefresh;
    private boolean active;

    public AtmosphereChannel() {
        this.remoteBiomeIds = new Long2IntOpenHashMap(256);
        this.remoteBiomeIds.defaultReturnValue(Integer.MIN_VALUE);
        this.cachedRevision = Long.MIN_VALUE;
        this.passesSinceRefresh = REFRESH_INTERVAL_PASSES;
    }

    public void update(Player observer,
                       UUID portalId,
                       World world,
                       Long2ObjectMap<ProjectedBlockClaim> claims,
                       ProjectionWorldView destView,
                       ProjectionClaimArbiter arbiter,
                       boolean claimsChanged) {
        if (destView != cachedView || destView.getRevision() != cachedRevision) {
            remoteBiomeIds.clear();
            cachedView = destView;
            cachedRevision = destView.getRevision();
            claimsChanged = true;
        }
        passesSinceRefresh++;
        if (!claimsChanged && passesSinceRefresh < REFRESH_INTERVAL_PASSES) {
            return;
        }
        passesSinceRefresh = 0;
        BiomeIdResolver ids = arbiter.biomeIds();
        Long2IntOpenHashMap overrides = AtmosphereDominance.compute(claims, FidelitySettings.biomeDominance,
            remoteKey -> resolve(destView, ids, remoteKey));
        if (overrides.isEmpty() && !active) {
            return;
        }
        active = !overrides.isEmpty();
        arbiter.submitBiomes(observer, portalId, world, overrides);
    }

    public void disable(Player observer, UUID portalId, World world, ProjectionClaimArbiter arbiter) {
        if (!active) {
            return;
        }
        active = false;
        arbiter.submitBiomes(observer, portalId, world, new Long2IntOpenHashMap());
    }

    public boolean isActive() {
        return active;
    }

    private int resolve(ProjectionWorldView destView, BiomeIdResolver ids, long remoteKey) {
        int rx = ProjectionCellKey.unpackX(remoteKey);
        int ry = ProjectionCellKey.unpackY(remoteKey);
        int rz = ProjectionCellKey.unpackZ(remoteKey);
        long quart = ProjectionCellKey.pack(rx >> 2, ry >> 2, rz >> 2);
        int cached = remoteBiomeIds.get(quart);
        if (cached != Integer.MIN_VALUE) {
            return cached;
        }
        String biome = destView.sampleBiome(rx, ry, rz);
        int id = biome == null ? -1 : ids.id(biome);
        remoteBiomeIds.put(quart, id);
        return id;
    }
}
