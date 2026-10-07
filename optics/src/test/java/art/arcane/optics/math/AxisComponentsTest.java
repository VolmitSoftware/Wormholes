package art.arcane.optics.math;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class AxisComponentsTest {
    private static final Box BOX = new Box(-3.25D, 7.5D, 1.0D, 4.75D, -9.0D, -2.5D);

    @Test
    void faceAxisIndexMatchesItsNonZeroComponent() {
        for (Face face : Face.values()) {
            int expected = face.x() != 0 ? 0 : face.y() != 0 ? 1 : 2;
            assertEquals(expected, face.axisIndex(), face.name());
        }
    }

    @Test
    void faceComponentReadsEachAxis() {
        for (Face face : Face.values()) {
            assertEquals(face.x(), face.component(0), face.name());
            assertEquals(face.y(), face.component(1), face.name());
            assertEquals(face.z(), face.component(2), face.name());
        }
    }

    @Test
    void faceSignIsTheDirectionAlongItsAxis() {
        for (Face face : Face.values()) {
            assertEquals(face.x() + face.y() + face.z(), face.sign(), face.name());
        }
    }

    @Test
    void boxBoundsReadEachAxis() {
        assertEquals(BOX.getXa(), BOX.min(0));
        assertEquals(BOX.getYa(), BOX.min(1));
        assertEquals(BOX.getZa(), BOX.min(2));
        assertEquals(BOX.getXb(), BOX.max(0));
        assertEquals(BOX.getYb(), BOX.max(1));
        assertEquals(BOX.getZb(), BOX.max(2));
    }

    @Test
    void vectorComponentReadsEachAxis() {
        Vec3d vector = new Vec3d(1.5D, -2.0D, 9.25D);
        assertEquals(1.5D, vector.component(0));
        assertEquals(-2.0D, vector.component(1));
        assertEquals(9.25D, vector.component(2));
    }

    @Test
    void vectorBlockCoordinatesFloorEachComponent() {
        Vec3d vector = new Vec3d(-0.5D, 63.999D, 2.0D);
        assertEquals(-1, vector.blockX());
        assertEquals(63, vector.blockY());
        assertEquals(2, vector.blockZ());
    }

    @Test
    void axisComponentPicksFromLooseCoordinates() {
        assertEquals(4.5D, Axis.component(0, 4.5D, -1.0D, 8.0D));
        assertEquals(-1.0D, Axis.component(1, 4.5D, -1.0D, 8.0D));
        assertEquals(8.0D, Axis.component(2, 4.5D, -1.0D, 8.0D));
        assertEquals(4, Axis.component(0, 4, -1, 8));
        assertEquals(-1, Axis.component(1, 4, -1, 8));
        assertEquals(8, Axis.component(2, 4, -1, 8));
    }
}
