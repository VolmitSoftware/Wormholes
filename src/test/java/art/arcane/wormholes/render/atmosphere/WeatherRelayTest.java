package art.arcane.wormholes.render.atmosphere;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.Particle;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.render.view.ProjectionWorldView;

final class WeatherRelayTest {
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
            assertEquals(Particle.RAIN, burst.particle());
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
        assertEquals(Particle.SNOWFLAKE, snow.particle());
        WeatherRelay.Burst thunder = relay.plan(true, true, "minecraft:plains", 200L);
        assertTrue(thunder.count() > relay.plan(true, false, "minecraft:plains", 300L).count(),
            "thunderstorms are denser than rain");
    }

    @Test
    void stormsDarkenTheSkyLightBand() {
        int clear = ProjectionWorldView.computeSkyDarken(6000L);
        int storm = ProjectionWorldView.computeSkyDarken(6000L, true, false);
        int thunder = ProjectionWorldView.computeSkyDarken(6000L, true, true);
        assertEquals(clear, ProjectionWorldView.computeSkyDarken(6000L, false, false));
        assertTrue(storm > clear, "storm=" + storm + " clear=" + clear);
        assertTrue(thunder > storm, "thunder=" + thunder + " storm=" + storm);
        assertTrue(thunder <= 11);
    }
}
