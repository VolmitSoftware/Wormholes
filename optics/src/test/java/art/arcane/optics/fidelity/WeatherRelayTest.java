package art.arcane.optics.fidelity;

import art.arcane.optics.light.SkyMath;
import art.arcane.optics.claim.ProjectedBlockClaim;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;


final class WeatherRelayTest {
    @Test
    void precipitationSamplesOnlyProjectedAirAndMaskCells() {
        WeatherRelay relay = new WeatherRelay();
        Long2ObjectOpenHashMap<ProjectedBlockClaim<String, Object>> claims = new Long2ObjectOpenHashMap<>();
        claims.put(1L, new ProjectedBlockClaim<>("stone", null, 0L, false));
        claims.put(2L, new ProjectedBlockClaim<>("air", null, 0L, false));
        claims.put(3L, new ProjectedBlockClaim<>("stone", null, 0L, true));
        List<Long> emitted = new ArrayList<>();
        relay.spawn(new WeatherRelay.Emission<>(claims, relay.plan(true, true, "minecraft:snowy_plains", 0L),
            new Random(37L), "air"::equals), (particle, cell) -> {
                assertEquals(WeatherRelay.Precipitation.SNOWFLAKE, particle);
                emitted.add(cell);
            });
        assertEquals(2, emitted.size());
        assertTrue(emitted.stream().allMatch(cell -> cell == 2L || cell == 3L));
    }

    @Test
    void burstsAreRateCappedPerObserver() {
        WeatherRelay relay = new WeatherRelay();
        int bursts = 0;
        int particles = 0;
        for (long tick = 0; tick < 200; tick++) {
            WeatherRelay.Burst burst = relay.plan(true, false, "minecraft:plains", tick);
            if (burst == null) {
                continue;
            }
            bursts++;
            particles += burst.count();
            assertTrue(burst.count() <= WeatherRelay.MAX_PARTICLES_PER_BURST);
            assertEquals(WeatherRelay.Precipitation.RAIN, burst.particle());
        }
        assertTrue(bursts <= 200 / WeatherRelay.BURST_INTERVAL_TICKS, "bursts=" + bursts);
        assertTrue(bursts >= 200 / WeatherRelay.BURST_INTERVAL_TICKS - 1, "bursts=" + bursts);
        assertTrue(particles <= bursts * WeatherRelay.MAX_PARTICLES_PER_BURST);
    }

    @Test
    void clearWeatherAndColdBiomesChooseTheRightParticle() {
        WeatherRelay relay = new WeatherRelay();
        assertNull(relay.plan(false, false, "minecraft:plains", 0L));
        WeatherRelay.Burst snow = relay.plan(true, false, "minecraft:snowy_taiga", 100L);
        assertEquals(WeatherRelay.Precipitation.SNOWFLAKE, snow.particle());
        WeatherRelay.Burst thunder = relay.plan(true, true, "minecraft:plains", 200L);
        assertTrue(thunder.count() > relay.plan(true, false, "minecraft:plains", 300L).count(),
            "thunderstorms are denser than rain");
    }

    @Test
    void stormsDarkenTheSkyLightBand() {
        int clear = SkyMath.computeSkyDarken(6000L);
        int storm = SkyMath.computeSkyDarken(6000L, true, false);
        int thunder = SkyMath.computeSkyDarken(6000L, true, true);
        assertEquals(clear, SkyMath.computeSkyDarken(6000L, false, false));
        assertTrue(storm > clear, "storm=" + storm + " clear=" + clear);
        assertTrue(thunder > storm, "thunder=" + thunder + " storm=" + storm);
        assertTrue(thunder <= 11);
    }
}
