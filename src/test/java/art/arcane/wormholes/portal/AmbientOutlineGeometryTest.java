package art.arcane.wormholes.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.util.Axis;

public final class AmbientOutlineGeometryTest
{
	private static final double EPSILON = 0.000001D;

	@Test
	public void rectangleOutlineStaysOnThePlaneAndFollowsTheBoundary()
	{
		List<Vector> blocks = new ArrayList<Vector>();
		for(int x = 0; x < 2; x++)
		{
			for(int y = 64; y < 67; y++)
			{
				blocks.add(new Vector(x, y, 8));
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
			List<double[]> outline = AmbientOutlineGeometry.build(List.of(new Vector(4, 5, 6)), axis);
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
		List<Vector> positions = List.of(new Vector(0, 0, 0), new Vector(1, 0, 0));

		List<double[]> first = geometry.points(7L, Axis.Z, positions);
		List<double[]> repeated = geometry.points(7L, Axis.Z, positions);
		assertSame(first, repeated);

		List<double[]> reoriented = geometry.points(7L, Axis.Y, positions);
		assertNotSame(first, reoriented);

		List<double[]> revised = geometry.points(8L, Axis.Y, positions);
		assertNotSame(reoriented, revised);

		List<double[]> revisedRepeated = geometry.points(8L, Axis.Y, positions);
		assertSame(revised, revisedRepeated);

		assertFalse(revised.isEmpty());
	}
}
