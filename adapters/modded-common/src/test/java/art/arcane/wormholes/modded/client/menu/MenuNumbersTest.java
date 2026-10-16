package art.arcane.wormholes.modded.client.menu;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class MenuNumbersTest {
    private static final double EPSILON = 1.0E-9D;

    @Test
    public void fractionMapsTheRangeOntoZeroToOne() {
        assertEquals(0.5D, MenuNumbers.fraction(5.0D, 0.0D, 10.0D), EPSILON);
        assertEquals(0.0D, MenuNumbers.fraction(3.0D, 3.0D, 3.0D), EPSILON);
        assertEquals(1.0D, MenuNumbers.fraction(12.0D, 0.0D, 10.0D), EPSILON);
        assertEquals(0.0D, MenuNumbers.fraction(-2.0D, 0.0D, 10.0D), EPSILON);
    }

    @Test
    public void snapRoundsToTheStepGridInsideTheRange() {
        assertEquals(5.0D, MenuNumbers.snap(4.6D, 0.0D, 10.0D, 1.0D), EPSILON);
        assertEquals(0.25D, MenuNumbers.snap(0.3D, 0.0D, 1.0D, 0.25D), EPSILON);
        assertEquals(10.0D, MenuNumbers.snap(11.0D, 0.0D, 10.0D, 1.0D), EPSILON);
        assertEquals(9.0D, MenuNumbers.snap(9.9D, 0.0D, 10.0D, 3.0D), EPSILON);
        assertEquals(10.0D, MenuNumbers.snap(10.0D, 0.0D, 10.0D, 3.0D), EPSILON);
        assertEquals(2.0D, MenuNumbers.snap(1.0D, 2.0D, 8.0D, 2.0D), EPSILON);
    }

    @Test
    public void snapCleansFloatingPointNoise() {
        assertEquals(0.3D, MenuNumbers.snap(0.3D, 0.0D, 1.0D, 0.1D), 0.0D);
        assertEquals(0.7D, MenuNumbers.snap(0.68D, 0.0D, 1.0D, 0.1D), 0.0D);
    }

    @Test
    public void valueAtMapsSliderFractionsBack() {
        assertEquals(32.0D, MenuNumbers.valueAt(0.5D, 0.0D, 64.0D, 1.0D), EPSILON);
        assertEquals(10.0D, MenuNumbers.valueAt(1.0D, 0.0D, 10.0D, 3.0D), EPSILON);
        assertEquals(2.0D, MenuNumbers.valueAt(0.0D, 2.0D, 600.0D, 1.0D), EPSILON);
    }

    @Test
    public void steppedMovesOneStepAndClamps() {
        assertEquals(6.0D, MenuNumbers.stepped(5.0D, 1, 0.0D, 10.0D, 1.0D), EPSILON);
        assertEquals(10.0D, MenuNumbers.stepped(9.5D, 1, 0.0D, 10.0D, 1.0D), EPSILON);
        assertEquals(0.0D, MenuNumbers.stepped(0.0D, -1, 0.0D, 10.0D, 1.0D), EPSILON);
    }

    @Test
    public void formatDropsTrailingZeros() {
        assertEquals("24", MenuNumbers.format(24.0D));
        assertEquals("0.25", MenuNumbers.format(0.25D));
        assertEquals("1.5", MenuNumbers.format(1.5D));
        assertEquals("-3", MenuNumbers.format(-3.0D));
        assertEquals("0.33", MenuNumbers.format(1.0D / 3.0D));
    }
}
