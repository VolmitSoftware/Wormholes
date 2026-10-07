package art.arcane.optics.fidelity;

import java.util.Random;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;

import java.util.function.Predicate;

import art.arcane.optics.claim.BlockClaim;
import art.arcane.optics.claim.WorldOutput;

/**
 * Relays destination precipitation into the projected volume as short particle bursts, rate-capped
 * per (portal, observer). The matching light band comes from the storm-aware sky darken of the
 * destination view, which the lighting overlay already rebases.
 */
public final class WeatherRelay {
    public static final int BURST_INTERVAL_TICKS = 5;
    public static final int MAX_PARTICLES_PER_BURST = 24;
    private static final int RAIN_PARTICLES_PER_BURST = 12;

    public record Burst(Precipitation particle, int count) {
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
        Precipitation particle = isCold(biomeKey) ? Precipitation.SNOWFLAKE : Precipitation.RAIN;
        return new Burst(particle, thunder ? MAX_PARTICLES_PER_BURST : RAIN_PARTICLES_PER_BURST);
    }

    public <O, B, V> void spawn(Emission<B, V> emission, WorldOutput<O> output, O observer) {
        Long2ObjectMap<BlockClaim<B, V>> claims = emission.claims();
        Burst burst = emission.burst();
        Random random = emission.random();
        if (burst == null || claims == null || claims.isEmpty()) {
            return;
        }
        LongArrayList airCells = new LongArrayList(Math.min(claims.size(), 256));
        for (Long2ObjectMap.Entry<BlockClaim<B, V>> entry : claims.long2ObjectEntrySet()) {
            BlockClaim<B, V> claim = entry.getValue();
            if (claim.isMaskAir() || emission.air().test(claim.getData())) {
                airCells.add(entry.getLongKey());
            }
        }
        if (airCells.isEmpty()) {
            return;
        }
        int count = Math.min(burst.count(), airCells.size());
        for (int index = 0; index < count; index++) {
            long key = airCells.getLong(random.nextInt(airCells.size()));
            output.weather(observer, burst.particle(), key);
        }
    }

    public enum Precipitation {
        RAIN,
        SNOWFLAKE
    }

    public record Emission<B, V>(Long2ObjectMap<BlockClaim<B, V>> claims, Burst burst, Random random, Predicate<B> air) {
    }

    static boolean isCold(String biomeKey) {
        if (biomeKey == null) {
            return false;
        }
        return biomeKey.contains("snow") || biomeKey.contains("frozen") || biomeKey.contains("ice")
            || biomeKey.contains("grove") || biomeKey.contains("jagged_peaks");
    }
}
