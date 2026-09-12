package art.arcane.wormholes.render.atmosphere;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.bukkit.World;
import org.junit.jupiter.api.Test;

final class FogPlatePolicyTest {
    @Test
    void eachDimensionPicksItsShellAndCustomWorldsKeepTheBlackoutColour() {
        assertEquals("minecraft:light_blue_stained_glass", FogPlatePolicy.shellState(World.Environment.NORMAL));
        assertEquals("minecraft:netherrack", FogPlatePolicy.shellState(World.Environment.NETHER));
        assertEquals("minecraft:end_stone", FogPlatePolicy.shellState(World.Environment.THE_END));
        assertNull(FogPlatePolicy.shellState(World.Environment.CUSTOM));
        assertNull(FogPlatePolicy.shellState(null));
    }

    @Test
    void theFogPlateOnlyAppliesInFullModeWithTheGlobalKnobOn() {
        assertEquals(true, FogPlatePolicy.applies(true, AtmosphereMode.FULL));
        assertEquals(false, FogPlatePolicy.applies(true, AtmosphereMode.TINT_LIGHT));
        assertEquals(false, FogPlatePolicy.applies(false, AtmosphereMode.FULL));
    }
}
