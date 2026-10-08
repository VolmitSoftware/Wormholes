package art.arcane.wormholes.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import art.arcane.optics.math.Vec3d;
import org.junit.jupiter.api.Test;

import art.arcane.optics.math.Axis;
import art.arcane.optics.aperture.ApertureCells;

public final class AmbientOutlineGeometryTest
{
	private static final double EPSILON = 0.000001D;

	@Test
	public void rectangleOutlineStaysOnThePlaneAndFollowsTheBoundary()
	{
		List<Vec3d> blocks = new ArrayList<Vec3d>();
		for(int x = 0; x < 2; x++)
		{
			for(int y = 64; y < 67; y++)
			{
				blocks.add(new Vec3d(x, y, 8));
			}
		}

		List<double[]> outline = AmbientOutlineGeometry.build(blocks, Axis.Z);

		assertEquals(10 * AmbientOutlineGeometry.SAMPLES_PER_EDGE, outline.size());
		for(double[] point : outline)
		{
			assertEquals(8.5D, point[2], EPSILON);
			boolean horizontalBoundary = Math.abs(point[1] - 64.0D) < EPSILON || Math.abs(point[1] - 67.0D) < EPSILON;
			boolean verticalBoundary = Math.abs(point[0]) < EPSILON || Math.abs(point[0] - 2.0D) < EPSILON;
			assertTrue(horizontalBoundary || verticalBoundary);
		}
	}

	@Test
	public void everyNormalAxisKeepsTheOutlineOnThePortalPlane()
	{
		for(Axis axis : Axis.values())
		{
			List<double[]> outline = AmbientOutlineGeometry.build(List.of(new Vec3d(4, 5, 6)), axis);
			assertEquals(4 * AmbientOutlineGeometry.SAMPLES_PER_EDGE, outline.size());
			for(double[] point : outline)
			{
				double normalCoordinate = switch(axis)
				{
					case X -> point[0];
					case Y -> point[1];
					case Z -> point[2];
				};
				double expected = switch(axis)
				{
					case X -> 4.5D;
					case Y -> 5.5D;
					case Z -> 6.5D;
				};
				assertEquals(expected, normalCoordinate, EPSILON);
			}
		}
	}

	@Test
	public void emptyInputProducesNoPoints()
	{
		assertTrue(AmbientOutlineGeometry.build(List.of(), Axis.Z).isEmpty());
	}

	@Test
	public void cacheReusesResultForSameRevisionAndAxisAndRebuildsOnChange()
	{
		AmbientOutlineGeometry geometry = new AmbientOutlineGeometry();
		ApertureCells structure = new ApertureCells();
        structure.setBlocks(List.of(new Vec3d(0, 0, 0), new Vec3d(1, 0, 0)));

		List<double[]> first = geometry.points(7L, Axis.Z, structure, null);
		List<double[]> repeated = geometry.points(7L, Axis.Z, structure, null);
		assertSame(first, repeated);

		List<double[]> reoriented = geometry.points(7L, Axis.Y, structure, null);
		assertNotSame(first, reoriented);

		List<double[]> revised = geometry.points(8L, Axis.Y, structure, null);
		assertNotSame(reoriented, revised);

		List<double[]> revisedRepeated = geometry.points(8L, Axis.Y, structure, null);
		assertSame(revised, revisedRepeated);

		assertFalse(revised.isEmpty());
	}

    @Test
    public void replacingGeometryWithTheSameRevisionRebuildsTheOutline() {
        AmbientOutlineGeometry cache = new AmbientOutlineGeometry();
        ApertureCells first = new ApertureCells();
        ApertureCells second = new ApertureCells();
        first.setBlocks(List.of(new Vec3d(-4, -5, -6)));
        second.setBlocks(List.of(new Vec3d(20, 30, 40)));
        assertEquals(first.getRevision(), second.getRevision());
        List<double[]> original = cache.points(first.getRevision(), Axis.Z, first, null);
        List<double[]> replacement = cache.points(second.getRevision(), Axis.Z, second, null);
        assertNotSame(original, replacement);
        assertSame(replacement, cache.points(second.getRevision(), Axis.Z, second, null));
        for (double[] point : replacement) {
            assertEquals(40.5D, point[2], EPSILON);
        }
    }

    @Test
    public void negativeRingsPreserveOuterAndInnerEdgesOnEveryAxis() {
        for (Axis axis : Axis.values()) {
            List<Vec3d> cells = new ArrayList<Vec3d>();
            for (int right = -3; right < 0; right++) {
                for (int up = -3; up < 0; up++) {
                    if (right == -2 && up == -2) {
                        continue;
                    }
                    cells.add(switch (axis) {
                        case X -> new Vec3d(-7, right, up);
                        case Y -> new Vec3d(right, -7, up);
                        case Z -> new Vec3d(right, up, -7);
                    });
                }
            }
            List<double[]> points = AmbientOutlineGeometry.build(cells, axis);
            assertEquals(16 * AmbientOutlineGeometry.SAMPLES_PER_EDGE, points.size());
            int inner = 0;
            for (double[] point : points) {
                double normal = point[axis.ordinal()];
                double right = point[axis == Axis.X ? 1 : 0];
                double up = point[axis == Axis.Z ? 1 : 2];
                assertEquals(-6.5D, normal, EPSILON);
                boolean outer = right == -3.0D || right == 0.0D || up == -3.0D || up == 0.0D;
                boolean hole = ((right == -2.0D || right == -1.0D) && up > -2.0D && up < -1.0D)
                    || ((up == -2.0D || up == -1.0D) && right > -2.0D && right < -1.0D);
                assertTrue(outer || hole);
                if (hole) {
                    inner++;
                }
            }
            assertEquals(4 * AmbientOutlineGeometry.SAMPLES_PER_EDGE, inner);
        }
    }

}
