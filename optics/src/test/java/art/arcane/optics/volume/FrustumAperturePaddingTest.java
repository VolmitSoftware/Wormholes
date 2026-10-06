package art.arcane.optics.volume;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;

public final class FrustumAperturePaddingTest {
    private static final double EPSILON = 1e-9D;

    @Test
    public void zeroPaddingKeepsOriginalFace() {
        Box face = new Box(1.0D, 1.0D, 10.0D, 14.0D, 20.0D, 24.0D);
        Box padded = Frustum.padAperture(face, Face.E, 0.0D);

        assertSame(face, padded);
    }

    @Test
    public void wallFacePaddingExpandsLateralAndVerticalAxesOnly() {
        Box face = new Box(5.0D, 5.0D, 64.0D, 68.0D, 10.0D, 14.0D);
        Box padded = Frustum.padAperture(face, Face.E, 1.0D);

        assertEquals(5.0D, padded.getXa(), EPSILON);
        assertEquals(5.0D, padded.getXb(), EPSILON);
        assertEquals(63.0D, padded.getYa(), EPSILON);
        assertEquals(69.0D, padded.getYb(), EPSILON);
        assertEquals(9.0D, padded.getZa(), EPSILON);
        assertEquals(15.0D, padded.getZb(), EPSILON);
    }

    @Test
    public void floorOrCeilingFacePaddingExpandsHorizontalScreenAxesOnly() {
        Box face = new Box(5.0D, 9.0D, 64.0D, 64.0D, 10.0D, 14.0D);
        Box padded = Frustum.padAperture(face, Face.D, 1.5D);

        assertEquals(3.5D, padded.getXa(), EPSILON);
        assertEquals(10.5D, padded.getXb(), EPSILON);
        assertEquals(64.0D, padded.getYa(), EPSILON);
        assertEquals(64.0D, padded.getYb(), EPSILON);
        assertEquals(8.5D, padded.getZa(), EPSILON);
        assertEquals(15.5D, padded.getZb(), EPSILON);
    }
}
