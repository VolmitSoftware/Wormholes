package art.arcane.wormholes.door;

import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class DoorPlanePairingApertureTest
{
	private static final BlockFace[] CARDINALS =
		{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};
	private static final double TOLERANCE = 1.0E-9D;

	@Test
	void hingedPairsPreserveHeightAndMirrorLateralPosition()
	{
		for(BlockFace sourceFacing : CARDINALS)
		{
			DoorwayPlane source = new DoorwayPlane(0, 64, 0, sourceFacing);
			DoorwayCrossing crossing = doorCrossing(source, 0.3D, 1.75D);
			for(BlockFace destinationFacing : CARDINALS)
			{
				DoorwayPlane destination = new DoorwayPlane(40, 20, -30, destinationFacing);
				DoorVec3 mapped = DoorPlanePairing.mapAperturePoint(source, destination, crossing);

				assertEquals(0.0D, destination.signedDistance(mapped), TOLERANCE);
				assertEquals(destination.blockY() + 1.75D, mapped.y(), TOLERANCE);
				assertEquals(-0.3D, lateralOffset(destination, mapped), TOLERANCE);
			}
		}
	}

	@Test
	void fastDiagonalCrossingUsesTheIntersectionInsteadOfTheSampleEndpoint()
	{
		DoorwayPlane source = new DoorwayPlane(0, 64, 0, BlockFace.SOUTH);
		DoorwayPlane destination = new DoorwayPlane(40, 20, -30, BlockFace.EAST);
		DoorVec3 center = source.center();
		DoorVec3 from = new DoorVec3(center.x() - 0.4D, 66.4D, center.z() + 1.0D);
		DoorVec3 to = new DoorVec3(center.x() + 0.4D, 64.4D, center.z() - 3.0D);
		DoorwayCrossing crossing = source.crossing(from, to).orElseThrow();

		DoorVec3 mapped = DoorPlanePairing.mapAperturePoint(source, destination, crossing);

		assertEquals(0.25D, crossing.segmentFraction(), TOLERANCE);
		assertEquals(1.9D, crossing.verticalOffset(), TOLERANCE);
		assertEquals(0.2D, crossing.lateralOffset(), TOLERANCE);
		assertEquals(destination.blockY() + 1.9D, mapped.y(), TOLERANCE);
		assertEquals(-0.2D, lateralOffset(destination, mapped), TOLERANCE);
	}

	@Test
	void doorHeightScalesOntoTheTrapdoorThirdAxis()
	{
		DoorwayPlane source = new DoorwayPlane(0, 64, 0, BlockFace.NORTH);
		DoorwayPlane destination = DoorwayPlane.trapdoor(
			20, 30, -8, BlockFace.EAST, Bisected.Half.BOTTOM, DoorOpenState.OPEN);
		DoorwayCrossing crossing = doorCrossing(source, 0.2D, 1.75D);

		DoorVec3 mapped = DoorPlanePairing.mapAperturePoint(source, destination, crossing);

		assertEquals(0.0D, destination.signedDistance(mapped), TOLERANCE);
		assertEquals(0.2D, lateralOffset(destination, mapped), TOLERANCE);
		assertEquals(0.375D, thirdAxisOffset(destination, mapped), TOLERANCE);
	}

	@Test
	void trapdoorDepthScalesOntoTheDoorHeight()
	{
		DoorwayPlane source = DoorwayPlane.trapdoor(
			0, 64, 0, BlockFace.SOUTH, Bisected.Half.TOP, DoorOpenState.OPEN);
		DoorwayPlane destination = new DoorwayPlane(20, 30, -8, BlockFace.WEST);
		DoorwayCrossing crossing = trapdoorCrossing(source, -0.2D, 0.25D);

		DoorVec3 mapped = DoorPlanePairing.mapAperturePoint(source, destination, crossing);

		assertEquals(0.0D, destination.signedDistance(mapped), TOLERANCE);
		assertEquals(-0.2D, lateralOffset(destination, mapped), TOLERANCE);
		assertEquals(destination.blockY() + 0.5D, mapped.y(), TOLERANCE);
	}

	@Test
	void trapdoorPairsPreserveBothNormalizedInPlaneCoordinates()
	{
		DoorwayPlane source = DoorwayPlane.trapdoor(
			0, 64, 0, BlockFace.EAST, Bisected.Half.BOTTOM, DoorOpenState.OPEN);
		DoorwayPlane destination = DoorwayPlane.trapdoor(
			20, 30, -8, BlockFace.NORTH, Bisected.Half.TOP, DoorOpenState.OPEN);
		DoorwayCrossing crossing = trapdoorCrossing(source, 0.35D, -0.4D);

		DoorVec3 mapped = DoorPlanePairing.mapAperturePoint(source, destination, crossing);

		assertEquals(0.0D, destination.signedDistance(mapped), TOLERANCE);
		assertEquals(0.35D, lateralOffset(destination, mapped), TOLERANCE);
		assertEquals(0.4D, thirdAxisOffset(destination, mapped), TOLERANCE);
	}

	private static DoorwayCrossing doorCrossing(
		DoorwayPlane plane,
		double lateralOffset,
		double verticalOffset)
	{
		DoorVec3 center = plane.center();
		DoorVec3 point = new DoorVec3(
			center.x() + (lateralOffset * -plane.facing().getModZ()),
			plane.blockY() + verticalOffset,
			center.z() + (lateralOffset * plane.facing().getModX()));
		return crossingThrough(plane, point);
	}

	private static DoorwayCrossing trapdoorCrossing(
		DoorwayPlane plane,
		double lateralOffset,
		double secondaryOffset)
	{
		DoorVec3 center = plane.center();
		DoorVec3 point = new DoorVec3(
			center.x()
				+ (lateralOffset * -plane.facing().getModZ())
				+ (secondaryOffset * plane.facing().getModX()),
			center.y(),
			center.z()
				+ (lateralOffset * plane.facing().getModX())
				+ (secondaryOffset * plane.facing().getModZ()));
		return crossingThrough(plane, point);
	}

	private static DoorwayCrossing crossingThrough(DoorwayPlane plane, DoorVec3 point)
	{
		DoorVec3 from = new DoorVec3(
			point.x() + plane.normalX(),
			point.y() + plane.normalY(),
			point.z() + plane.normalZ());
		DoorVec3 to = new DoorVec3(
			point.x() - plane.normalX(),
			point.y() - plane.normalY(),
			point.z() - plane.normalZ());
		return plane.crossing(from, to).orElseThrow();
	}

	private static double lateralOffset(DoorwayPlane plane, DoorVec3 point)
	{
		DoorVec3 center = plane.center();
		return ((point.x() - center.x()) * -plane.facing().getModZ())
			+ ((point.z() - center.z()) * plane.facing().getModX());
	}

	private static double thirdAxisOffset(DoorwayPlane plane, DoorVec3 point)
	{
		DoorVec3 center = plane.center();
		return ((point.x() - center.x()) * -plane.facing().getModX())
			+ ((point.z() - center.z()) * -plane.facing().getModZ());
	}
}
