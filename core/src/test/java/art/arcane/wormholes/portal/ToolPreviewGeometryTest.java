package art.arcane.wormholes.portal;

import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.util.Axis;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class ToolPreviewGeometryTest
{
	private static final double EPSILON = 0.000001D;

	@Test
	public void rectangleProducesOnlyItsExactOuterBoundary()
	{
		ArrayList<GeometryVector> blocks = new ArrayList<GeometryVector>();
		for(int x = 0; x < 2; x++)
		{
			for(int y = 64; y < 67; y++)
			{
				blocks.add(new GeometryVector(x, y, 8));
			}
		}

		ToolPreviewGeometry.Geometry geometry = ToolPreviewGeometry.build(blocks, Axis.Z);

		assertEquals(6, geometry.cells().size());
		assertEquals(40, geometry.outlinePoints().size());
		for(ToolPreviewGeometry.PreviewPoint point : geometry.outlinePoints())
		{
			assertEquals(8.5D, point.z(), EPSILON);
			boolean horizontalBoundary = Math.abs(point.y() - 64.0D) < EPSILON || Math.abs(point.y() - 67.0D) < EPSILON;
			boolean verticalBoundary = Math.abs(point.x()) < EPSILON || Math.abs(point.x() - 2.0D) < EPSILON;
			assertTrue(horizontalBoundary || verticalBoundary);
		}
	}

	@Test
	public void lShapePreservesItsMissingCellAndInnerEdges()
	{
		ToolPreviewGeometry.Geometry geometry = ToolPreviewGeometry.build(List.of(
			new GeometryVector(0, 64, 8),
			new GeometryVector(1, 64, 8),
			new GeometryVector(0, 65, 8)), Axis.Z);

		assertEquals(3, geometry.cells().size());
		assertFalse(geometry.cells().contains(new ToolPreviewGeometry.Cell(1, 65, 8)));
		assertEquals(32, geometry.outlinePoints().size());
		assertTrue(geometry.outlinePoints().contains(new ToolPreviewGeometry.PreviewPoint(1.125D, 65.0D, 8.5D)));
		assertTrue(geometry.outlinePoints().contains(new ToolPreviewGeometry.PreviewPoint(1.0D, 65.125D, 8.5D)));
	}

	@Test
	public void ringPreservesItsCenterHoleAndInnerOutline()
	{
		ArrayList<GeometryVector> blocks = new ArrayList<GeometryVector>();
		for(int x = 0; x < 3; x++)
		{
			for(int y = 0; y < 3; y++)
			{
				if(x != 1 || y != 1)
				{
					blocks.add(new GeometryVector(x, y, 0));
				}
			}
		}

		ToolPreviewGeometry.Geometry geometry = ToolPreviewGeometry.build(blocks, Axis.Z);

		assertEquals(8, geometry.cells().size());
		assertFalse(geometry.cells().contains(new ToolPreviewGeometry.Cell(1, 1, 0)));
		assertEquals(64, geometry.outlinePoints().size());
		assertTrue(geometry.outlinePoints().contains(new ToolPreviewGeometry.PreviewPoint(1.125D, 1.0D, 0.5D)));
	}

    @ParameterizedTest
    @EnumSource(Axis.class)
    public void everyNormalAxisKeepsTheOutlineOnThePortalPlane(Axis axis) {
        ToolPreviewGeometry.Geometry geometry = ToolPreviewGeometry.build(List.of(new GeometryVector(4, 5, 6)), axis);
        assertEquals(1, geometry.cells().size());
        assertEquals(16, geometry.outlinePoints().size());
        double expected = switch (axis) {
            case X -> 4.5D;
            case Y -> 5.5D;
            case Z -> 6.5D;
        };
        for (ToolPreviewGeometry.PreviewPoint point : geometry.outlinePoints()) {
            double normalCoordinate = switch (axis) {
                case X -> point.x();
                case Y -> point.y();
                case Z -> point.z();
            };
            assertEquals(expected, normalCoordinate, EPSILON);
        }
    }

	@Test
	public void rangeUsesTheExactPortalBounds()
	{
		ToolPreviewGeometry.Geometry geometry = ToolPreviewGeometry.build(List.of(
			new GeometryVector(-2, 64, 8),
			new GeometryVector(-1, 64, 8)), Axis.Z);

		assertEquals(0.0D, geometry.distanceSquared(-1.0D, 64.5D, 8.5D), EPSILON);
		assertEquals(1024.0D, geometry.distanceSquared(32.0D, 64.5D, 8.5D), EPSILON);
	}

    @Test
    public void duplicateAndNullCellsDoNotChangeNegativeCoordinateOutlines() {
        ToolPreviewGeometry.Geometry geometry = ToolPreviewGeometry.build(Arrays.asList(
            new GeometryVector(-1.2D, -0.1D, -2.5D), null, new GeometryVector(-1.2D, -0.1D, -2.5D)), Axis.Z);

        assertEquals(List.of(new ToolPreviewGeometry.Cell(-2, -1, -3)), geometry.cells());
        assertEquals(16, geometry.outlinePoints().size());
        assertEquals(0.0D, geometry.distanceSquared(-1.5D, -0.5D, -2.5D), EPSILON);
        assertTrue(ToolPreviewGeometry.build(List.of(), Axis.Y).isEmpty());
    }

    @ParameterizedTest
    @EnumSource(Axis.class)
    public void outlineOffsetFacesTheViewerOnEveryAxis(Axis axis) {
        assertEquals(0.04D, ToolPreviewGeometry.viewerSideOffset(1, 1, 1, axis, 0, 0, 0), EPSILON);
        assertEquals(-0.04D, ToolPreviewGeometry.viewerSideOffset(-1, -1, -1, axis, 0, 0, 0), EPSILON);
        assertEquals(0.04D, ToolPreviewGeometry.viewerSideOffset(0, 0, 0, axis, 0, 0, 0), EPSILON);
    }

    @Test
    public void animationSamplesStayInsideTheOutlineAcrossFrameOverflow() {
        UUID portalId = UUID.fromString("7258c922-061f-4b3b-a28d-e8684e58e68f");
        for (long frame : new long[] {Long.MIN_VALUE, -1L, 0L, 1L, Long.MAX_VALUE}) {
            for (int size : new int[] {1, 16, 40, 4096}) {
                int sample = ToolPreviewGeometry.sampleStart(portalId, frame, size);
                assertTrue(sample >= 0 && sample < size);
            }
        }
    }
}
