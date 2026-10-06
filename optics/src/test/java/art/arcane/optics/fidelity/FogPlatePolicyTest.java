package art.arcane.optics.fidelity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

final class FogPlatePolicyTest {
    @Test
    void eachDimensionPicksItsShellAndCustomWorldsKeepTheBlackoutColour() {
        assertEquals("minecraft:light_blue_stained_glass", FogPlatePolicy.shellState(FogPlatePolicy.Dimension.OVERWORLD));
        assertEquals("minecraft:netherrack", FogPlatePolicy.shellState(FogPlatePolicy.Dimension.NETHER));
        assertEquals("minecraft:end_stone", FogPlatePolicy.shellState(FogPlatePolicy.Dimension.END));
        assertNull(FogPlatePolicy.shellState(FogPlatePolicy.Dimension.CUSTOM));
        assertNull(FogPlatePolicy.shellState(null));
    }

    @Test
    void theFogPlateOnlyAppliesInFullModeWithTheGlobalKnobOn() {
        assertEquals(true, FogPlatePolicy.applies(true, AtmosphereMode.FULL));
        assertEquals(false, FogPlatePolicy.applies(true, AtmosphereMode.TINT_LIGHT));
        assertEquals(false, FogPlatePolicy.applies(false, AtmosphereMode.FULL));
    }
}
