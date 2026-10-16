package art.arcane.wormholes.menu.transit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

final class TransitInputsTest {
    @Test
    void factorsAcceptZeroToTen() {
        assertEquals(Double.valueOf(2.5D), TransitInputs.parseFactor(" 2.5 "));
        assertEquals(Double.valueOf(0.0D), TransitInputs.parseFactor("0"));
        assertEquals(Double.valueOf(10.0D), TransitInputs.parseFactor("10"));
        assertNull(TransitInputs.parseFactor("10.01"));
        assertNull(TransitInputs.parseFactor("-1"));
        assertNull(TransitInputs.parseFactor("NaN"));
        assertNull(TransitInputs.parseFactor("fast"));
        assertNull(TransitInputs.parseFactor(null));
    }

    @Test
    void maskTicksClearOnEmptyAndAcceptMinusOneToTwoHundred() {
        assertEquals(Integer.valueOf(-1), TransitInputs.parseMaskTicks("  "));
        assertEquals(Integer.valueOf(-1), TransitInputs.parseMaskTicks("-1"));
        assertEquals(Integer.valueOf(200), TransitInputs.parseMaskTicks("200"));
        assertNull(TransitInputs.parseMaskTicks("201"));
        assertNull(TransitInputs.parseMaskTicks("-2"));
        assertNull(TransitInputs.parseMaskTicks("4.5"));
    }

    @Test
    void soundsLowerCaseAndClearOnDefaultWords() {
        assertEquals("minecraft:block.bell.use", TransitInputs.soundOrEmpty(" Minecraft:Block.Bell.Use "));
        assertEquals("", TransitInputs.soundOrEmpty("-"));
        assertEquals("", TransitInputs.soundOrEmpty("None"));
        assertEquals("", TransitInputs.soundOrEmpty("DEFAULT"));
        assertEquals("", TransitInputs.soundOrEmpty(null));
    }

    @Test
    void factorsFormatLikeTheChestLabel() {
        assertEquals("2", TransitInputs.formatFactor(2.0D));
        assertEquals("0.35", TransitInputs.formatFactor(0.35D));
        assertEquals("default", TransitInputs.labelOrDefault(" "));
        assertEquals("minecraft:x", TransitInputs.labelOrDefault("minecraft:x"));
    }
}
