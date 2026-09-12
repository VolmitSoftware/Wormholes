package art.arcane.wormholes.portal.vanilla;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DimensionalScalingTest {
    private static final List<String> DEFAULTS = List.of("minecraft:the_nether:8.0");

    @Test
    void theShippedListGivesTheNetherEightToOneAndEveryOtherWorldOneToOne() {
        assertEquals(8.0D, DimensionalScaling.scaleOf("minecraft:the_nether", DEFAULTS));
        assertEquals(1.0D, DimensionalScaling.scaleOf("minecraft:overworld", DEFAULTS));
        assertEquals(1.0D, DimensionalScaling.scaleOf("custom:mine", DEFAULTS));
    }

    @Test
    void overworldToNetherDividesAndNetherToOverworldMultiplies() {
        assertEquals(12, DimensionalScaling.map(100, -100, 1.0D, 8.0D)[0]);
        assertEquals(-13, DimensionalScaling.map(100, -100, 1.0D, 8.0D)[1]);
        assertEquals(800, DimensionalScaling.map(100, -100, 8.0D, 1.0D)[0]);
        assertEquals(-800, DimensionalScaling.map(100, -100, 8.0D, 1.0D)[1]);
    }

    @Test
    void customRatiosScaleBothWays() {
        List<String> custom = List.of("minecraft:the_nether:4.0", "custom:mine:2.0");
        assertEquals(4.0D, DimensionalScaling.scaleOf("minecraft:the_nether", custom));
        assertEquals(2.0D, DimensionalScaling.scaleOf("custom:mine", custom));
        assertEquals(50, DimensionalScaling.map(100, 100, 1.0D, 2.0D)[0]);
        assertEquals(25, DimensionalScaling.map(100, 100, 2.0D, 8.0D)[0]);
    }

    @Test
    void malformedEntriesAreIgnoredRatherThanBreakingEveryWorld() {
        List<String> broken = List.of("minecraft:the_nether", "minecraft:the_end:zero", "custom:mine:2.0",
            "minecraft:bad:-3.0", "");
        assertEquals(1.0D, DimensionalScaling.scaleOf("minecraft:the_nether", broken));
        assertEquals(1.0D, DimensionalScaling.scaleOf("minecraft:the_end", broken));
        assertEquals(1.0D, DimensionalScaling.scaleOf("minecraft:bad", broken));
        assertEquals(2.0D, DimensionalScaling.scaleOf("custom:mine", broken));
    }

    @Test
    void aNetherToOverworldTripNeverLandsPastTheBorderMinusSixteen() {
        int[] scaled = DimensionalScaling.map(3_900_000, -3_900_000, 8.0D, 1.0D);
        assertEquals(31_200_000, scaled[0]);

        int[] clamped = DimensionalScaling.clampToBorder(scaled, DimensionalScaling.MAX_COORDINATE);
        assertEquals(DimensionalScaling.MAX_COORDINATE - DimensionalScaling.BORDER_MARGIN, clamped[0]);
        assertEquals(-(DimensionalScaling.MAX_COORDINATE - DimensionalScaling.BORDER_MARGIN), clamped[1]);

        int[] smallBorder = DimensionalScaling.clampToBorder(new int[]{5_000, -5_000}, 1_000.0D);
        assertEquals(984, smallBorder[0]);
        assertEquals(-984, smallBorder[1]);

        int[] inside = DimensionalScaling.clampToBorder(new int[]{100, -100}, 1_000.0D);
        assertEquals(100, inside[0]);
        assertEquals(-100, inside[1]);
    }
}
