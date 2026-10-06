package art.arcane.wormholes.door;

import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.crossing.PlaneCrossing;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class DoorPlanePairingApertureTest
{
	private static final Face[] CARDINALS =
		{Face.N, Face.S, Face.E, Face.W};
	private static final double TOLERANCE = 1.0E-9D;

	@Test
	void hingedPairsPreserveHeightAndMirrorLateralPosition()
	{
		for(Face sourceFacing : CARDINALS)
		{
			DoorwayPlane source = new DoorwayPlane(0, 64, 0, sourceFacing);
			PlaneCrossing crossing = doorCrossing(source, 0.3D, 1.75D);
			for(Face destinationFacing : CARDINALS)
			{
				DoorwayPlane destination = new DoorwayPlane(40, 20, -30, destinationFacing);
				Vec3d mapped = DoorPlanePairing.mapAperturePoint(source, destination, crossing);

				assertEquals(0.0D, destination.signedDistance(mapped), TOLERANCE);
				assertEquals(destination.blockY() + 1.75D, mapped.y(), TOLERANCE);
				assertEquals(-0.3D, lateralOffset(destination, mapped), TOLERANCE);
			}
		}
	}

	@Test
	void fastDiagonalCrossingUsesTheIntersectionInsteadOfTheSampleEndpoint()
	{
		DoorwayPlane source = new DoorwayPlane(0, 64, 0, Face.S);
		DoorwayPlane destination = new DoorwayPlane(40, 20, -30, Face.E);
		Vec3d center = source.center();
		Vec3d from = new Vec3d(center.x() - 0.4D, 66.4D, center.z() + 1.0D);
		Vec3d to = new Vec3d(center.x() + 0.4D, 64.4D, center.z() - 3.0D);
		PlaneCrossing crossing = source.crossing(from, to).orElseThrow();

		Vec3d mapped = DoorPlanePairing.mapAperturePoint(source, destination, crossing);

		Vec3d quarter = from.add(to.subtract(from).multiply(0.25D));
		assertEquals(quarter.x(), crossing.point().x(), TOLERANCE);
		assertEquals(quarter.y(), crossing.point().y(), TOLERANCE);
		assertEquals(quarter.z(), crossing.point().z(), TOLERANCE);
		assertEquals(1.9D, source.secondaryOffset(crossing.point()), TOLERANCE);
		assertEquals(0.2D, source.lateralOffset(crossing.point()), TOLERANCE);
		assertEquals(destination.blockY() + 1.9D, mapped.y(), TOLERANCE);
		assertEquals(-0.2D, lateralOffset(destination, mapped), TOLERANCE);
	}

	@Test
	void doorHeightScalesOntoTheTrapdoorThirdAxis()
	{
		DoorwayPlane source = new DoorwayPlane(0, 64, 0, Face.N);
		DoorwayPlane destination = DoorwayPlane.trapdoor(
			20, 30, -8, Face.E, DoorHalf.BOTTOM, DoorOpenState.OPEN);
		PlaneCrossing crossing = doorCrossing(source, 0.2D, 1.75D);

		Vec3d mapped = DoorPlanePairing.mapAperturePoint(source, destination, crossing);

		assertEquals(0.0D, destination.signedDistance(mapped), TOLERANCE);
		assertEquals(0.2D, lateralOffset(destination, mapped), TOLERANCE);
		assertEquals(0.375D, thirdAxisOffset(destination, mapped), TOLERANCE);
	}

	@Test
	void trapdoorDepthScalesOntoTheDoorHeight()
	{
		DoorwayPlane source = DoorwayPlane.trapdoor(
			0, 64, 0, Face.S, DoorHalf.TOP, DoorOpenState.OPEN);
		DoorwayPlane destination = new DoorwayPlane(20, 30, -8, Face.W);
		PlaneCrossing crossing = trapdoorCrossing(source, -0.2D, 0.25D);

		Vec3d mapped = DoorPlanePairing.mapAperturePoint(source, destination, crossing);

		assertEquals(0.0D, destination.signedDistance(mapped), TOLERANCE);
		assertEquals(-0.2D, lateralOffset(destination, mapped), TOLERANCE);
		assertEquals(destination.blockY() + 0.5D, mapped.y(), TOLERANCE);
	}

	@Test
	void trapdoorPairsPreserveBothNormalizedInPlaneCoordinates()
	{
		DoorwayPlane source = DoorwayPlane.trapdoor(
			0, 64, 0, Face.E, DoorHalf.BOTTOM, DoorOpenState.OPEN);
		DoorwayPlane destination = DoorwayPlane.trapdoor(
			20, 30, -8, Face.N, DoorHalf.TOP, DoorOpenState.OPEN);
		PlaneCrossing crossing = trapdoorCrossing(source, 0.35D, -0.4D);

		Vec3d mapped = DoorPlanePairing.mapAperturePoint(source, destination, crossing);

		assertEquals(0.0D, destination.signedDistance(mapped), TOLERANCE);
		assertEquals(0.35D, lateralOffset(destination, mapped), TOLERANCE);
		assertEquals(0.4D, thirdAxisOffset(destination, mapped), TOLERANCE);
	}

	private static PlaneCrossing doorCrossing(
		DoorwayPlane plane,
		double lateralOffset,
		double verticalOffset)
	{
		Vec3d center = plane.center();
		Vec3d point = new Vec3d(
			center.x() + (lateralOffset * -plane.facing().z()),
			plane.blockY() + verticalOffset,
			center.z() + (lateralOffset * plane.facing().x()));
		return crossingThrough(plane, point);
	}

	private static PlaneCrossing trapdoorCrossing(
		DoorwayPlane plane,
		double lateralOffset,
		double secondaryOffset)
	{
		Vec3d center = plane.center();
		Vec3d point = new Vec3d(
			center.x()
				+ (lateralOffset * -plane.facing().z())
				+ (secondaryOffset * plane.facing().x()),
			center.y(),
			center.z()
				+ (lateralOffset * plane.facing().x())
				+ (secondaryOffset * plane.facing().z()));
		return crossingThrough(plane, point);
	}

	private static PlaneCrossing crossingThrough(DoorwayPlane plane, Vec3d point)
	{
		Vec3d from = new Vec3d(
			point.x() + plane.normalX(),
			point.y() + plane.normalY(),
			point.z() + plane.normalZ());
		Vec3d to = new Vec3d(
			point.x() - plane.normalX(),
			point.y() - plane.normalY(),
			point.z() - plane.normalZ());
		return plane.crossing(from, to).orElseThrow();
	}

	private static double lateralOffset(DoorwayPlane plane, Vec3d point)
	{
		Vec3d center = plane.center();
		return ((point.x() - center.x()) * -plane.facing().z())
			+ ((point.z() - center.z()) * plane.facing().x());
	}

	private static double thirdAxisOffset(DoorwayPlane plane, Vec3d point)
	{
		Vec3d center = plane.center();
		return ((point.x() - center.x()) * -plane.facing().x())
			+ ((point.z() - center.z()) * -plane.facing().z());
	}
}
