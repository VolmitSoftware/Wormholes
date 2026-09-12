package art.arcane.wormholes.render.atmosphere;

import java.util.Random;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;

import org.bukkit.Particle;
import org.bukkit.entity.Player;

import art.arcane.wormholes.render.ProjectedBlockClaim;
import art.arcane.wormholes.render.ProjectionCellKey;
import art.arcane.wormholes.render.view.ProjectionWorldView;

/**
 * Relays destination precipitation into the projected volume as short particle bursts, rate-capped
 * per (portal, observer). The matching light band comes from the storm-aware sky darken of the
 * destination view, which the lighting overlay already rebases.
 */
public final class WeatherRelay {
    public static final int BURST_INTERVAL_TICKS = 5;
    public static final int MAX_PARTICLES_PER_BURST = 24;
    private static final int RAIN_PARTICLES_PER_BURST = 12;

    public record Burst(Particle particle, int count) {
    }

    private long lastBurstTick = Long.MIN_VALUE;

    public Burst plan(boolean storm, boolean thunder, String biomeKey, long tick) {
        if (!storm) {
            return null;
        }
        if (lastBurstTick != Long.MIN_VALUE && tick - lastBurstTick < BURST_INTERVAL_TICKS) {
            return null;
        }
        lastBurstTick = tick;
        Particle particle = isCold(biomeKey) ? Particle.SNOWFLAKE : Particle.RAIN;
        return new Burst(particle, thunder ? MAX_PARTICLES_PER_BURST : RAIN_PARTICLES_PER_BURST);
    }

    public void spawn(Player observer, Long2ObjectMap<ProjectedBlockClaim> claims, Burst burst, Random random) {
        if (observer == null || burst == null || claims == null || claims.isEmpty()) {
            return;
        }
        LongArrayList airCells = new LongArrayList(Math.min(claims.size(), 256));
        for (Long2ObjectMap.Entry<ProjectedBlockClaim> entry : claims.long2ObjectEntrySet()) {
            ProjectedBlockClaim claim = entry.getValue();
            if (claim.isMaskAir() || ProjectionWorldView.isAir(claim.getData().getMaterial())) {
                airCells.add(entry.getLongKey());
            }
        }
        if (airCells.isEmpty()) {
            return;
        }
        int count = Math.min(burst.count(), airCells.size());
        for (int index = 0; index < count; index++) {
            long key = airCells.getLong(random.nextInt(airCells.size()));
            observer.spawnParticle(burst.particle(),
                ProjectionCellKey.unpackX(key) + 0.5D,
                ProjectionCellKey.unpackY(key) + 0.5D,
                ProjectionCellKey.unpackZ(key) + 0.5D,
                1, 0.4D, 0.5D, 0.4D, 0.0D);
        }
    }

    static boolean isCold(String biomeKey) {
        if (biomeKey == null) {
            return false;
        }
        return biomeKey.contains("snow") || biomeKey.contains("frozen") || biomeKey.contains("ice")
            || biomeKey.contains("grove") || biomeKey.contains("jagged_peaks");
    }
}
