package art.arcane.optics.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public final class SectionCacheLimitsTest {
    @Test
    public void fromConvertsMegabytesAndAppliesTheSharedClamps() {
        SectionCache.Limits limits = SectionCache.Limits.from(true, 64, 16, 200);
        assertTrue(limits.enabled());
        assertEquals(64L << 20, limits.maxBytes());
        assertEquals(16, limits.chunksPerTick());
        assertEquals(200, limits.ttlTicks());
    }

    @Test
    public void fromClampsEveryKnobIntoItsConfiguredRange() {
        SectionCache.Limits low = SectionCache.Limits.from(false, 0, 0, 1);
        assertFalse(low.enabled());
        assertEquals(1L << 20, low.maxBytes());
        assertEquals(1, low.chunksPerTick());
        assertEquals(20, low.ttlTicks());
        SectionCache.Limits high = SectionCache.Limits.from(true, 10_000, 5_000, 1_000_000);
        assertEquals(4096L << 20, high.maxBytes());
        assertEquals(1024, high.chunksPerTick());
        assertEquals(72_000, high.ttlTicks());
    }
}
