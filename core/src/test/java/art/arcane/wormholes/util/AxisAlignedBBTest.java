package art.arcane.wormholes.util;

import art.arcane.wormholes.geometry.GeometryVector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class AxisAlignedBBTest {
    @Test
    public void normalizesCornersAndIncludesBoundaryPoints() {
        AxisAlignedBB bounds = new AxisAlignedBB(4.0D, -2.0D, 8.0D, 2.0D, 10.0D, 4.0D);
        assertEquals(new GeometryVector(-2.0D, 2.0D, 4.0D), bounds.min());
        assertEquals(new GeometryVector(4.0D, 8.0D, 10.0D), bounds.max());
        assertEquals(new GeometryVector(1.0D, 5.0D, 7.0D), bounds.center());
        assertEquals(216.0D, bounds.volume());
        assertTrue(bounds.contains(bounds.min()));
        assertTrue(bounds.contains(bounds.max()));
        assertFalse(bounds.containsPrimitive(4.0001D, 5.0D, 7.0D));
    }

    @Test
    public void computesFacesWithoutChangingOriginalBounds() {
        AxisAlignedBB bounds = new AxisAlignedBB(-2.0D, 4.0D, 2.0D, 8.0D, 4.0D, 10.0D);
        AxisAlignedBB face = bounds.getFace(Direction.W);
        assertEquals(-2.0D, face.getXa());
        assertEquals(-2.0D, face.getXb());
        assertEquals(Axis.X, face.getThinAxis());
        assertEquals(4.0D, bounds.getXb());
        assertTrue(bounds.intersects(face));
    }

    @Test
    public void encapsulationUnionsCoordinatesAndCopiesStayIndependent() {
        AxisAlignedBB original = new AxisAlignedBB(-2.0D, 4.0D, 2.0D, 8.0D, 4.0D, 10.0D);
        AxisAlignedBB copy = new AxisAlignedBB(original);
        copy.encapsulate(7.0D, -4.0D, 1.0D, -6.0D, 3.0D, 11.0D);
        assertEquals(new GeometryVector(-6.0D, -4.0D, 1.0D), copy.min());
        assertEquals(new GeometryVector(7.0D, 8.0D, 11.0D), copy.max());
        assertEquals(new GeometryVector(-2.0D, 2.0D, 4.0D), original.min());
    }
}
