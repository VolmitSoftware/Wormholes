package art.arcane.wormholes.render.acoustics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class SoundAttenuationTest {
    @Test
    void volumeFallsWithDistanceButNeverBelowTheFloor() {
        assertEquals(1.0F, SoundAttenuation.volume(1.0F, 0.0D, 24.0D), 1.0E-6F);
        float near = SoundAttenuation.volume(1.0F, 6.0D, 24.0D);
        float far = SoundAttenuation.volume(1.0F, 18.0D, 24.0D);
        float beyond = SoundAttenuation.volume(1.0F, 100.0D, 24.0D);
        assertTrue(near > far, "near=" + near + " far=" + far);
        assertTrue(far > beyond);
        assertEquals(SoundAttenuation.VOLUME_FLOOR, beyond, 1.0E-6F, "sounds past the radius keep a whisper");
        assertEquals(2.0F, SoundAttenuation.volume(2.0F, 0.0D, 0.0D), 1.0E-6F, "a zero radius passes the base volume through");
    }

    @Test
    void pitchDropsSlightlyWithDistanceAndStaysInTheClientRange() {
        assertEquals(1.0F, SoundAttenuation.pitch(1.0F, 0.0D, 24.0D), 1.0E-6F);
        float far = SoundAttenuation.pitch(1.0F, 24.0D, 24.0D);
        assertTrue(far < 1.0F && far >= 0.8F, "far=" + far);
        assertEquals(2.0F, SoundAttenuation.pitch(5.0F, 0.0D, 24.0D), 1.0E-6F, "pitch is clamped to the client maximum");
        assertEquals(0.5F, SoundAttenuation.pitch(0.1F, 0.0D, 24.0D), 1.0E-6F, "pitch is clamped to the client minimum");
    }
}
