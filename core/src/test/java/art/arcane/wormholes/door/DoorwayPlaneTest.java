package art.arcane.wormholes.door;

import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.crossing.PlaneCrossing;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class DoorwayPlaneTest {
    private static final double TOLERANCE = 1.0E-9D;
    private static final Face[] CARDINALS = {Face.N, Face.S, Face.E, Face.W};

	@Test
	public void northFacingDoorDetectsFastDiagonalCrossingInsideAperture()
	{
		DoorwayPlane plane = new DoorwayPlane(10, 64, -4, Face.N);

		PlaneCrossing crossing = plane.crossing(
			new Vec3d(10.2D, 64.25D, -4.5D),
			new Vec3d(10.8D, 65.75D, -2.5D)).orElseThrow();

		assertEquals(0.0D, plane.signedDistance(crossing.point()), 1.0E-9D);
		assertEquals(plane.center().z(), crossing.point().z(), 1.0E-9D);
		assertTrue(crossing.frontSide());
	}

	@Test
	public void eastFacingDoorDetectsCrossingInEitherDirection()
	{
		DoorwayPlane plane = new DoorwayPlane(-2, 20, 7, Face.E);

		PlaneCrossing crossing = plane.crossing(
			new Vec3d(-0.5D, 20.0D, 7.5D),
			new Vec3d(-3.5D, 20.0D, 7.5D)).orElseThrow();

		assertTrue(crossing.frontSide());
		assertEquals(plane.center().x(), crossing.point().x(), 1.0E-9D);
		assertEquals(7.5D, crossing.point().z(), 1.0E-9D);
	}

	@Test
	public void apertureIncludesPhysicalEdgesButRejectsOutsideAndCoplanarMotion()
	{
		DoorwayPlane plane = new DoorwayPlane(0, 64, 0, Face.S);

		assertTrue(plane.crossing(
			new Vec3d(0.0D, 66.0D, 0.0D),
			new Vec3d(0.0D, 66.0D, 1.0D)).isPresent());
		assertFalse(plane.crossing(
			new Vec3d(-0.01D, 65.0D, 0.0D),
			new Vec3d(-0.01D, 65.0D, 1.0D)).isPresent());
		assertFalse(plane.crossing(
			new Vec3d(0.25D, 66.01D, 0.0D),
			new Vec3d(0.25D, 66.01D, 1.0D)).isPresent());
		assertFalse(plane.crossing(
			new Vec3d(0.0D, 65.0D, 0.5D),
			new Vec3d(1.0D, 65.0D, 0.5D)).isPresent());
	}

	@Test
	public void movementStartingOnPlaneDoesNotPullPlayerThrough()
	{
		DoorwayPlane plane = new DoorwayPlane(0, 64, 0, Face.N);
		Vec3d center = plane.center();

		assertFalse(plane.crossing(
			new Vec3d(center.x(), 64.0D, center.z()),
			new Vec3d(center.x(), 64.0D, center.z() + 1.0D)).isPresent());
	}

	@Test
	public void recessedThresholdAcceptsNormalStepHeightApproachAtTheSecondEndpoint()
	{
		DoorwayPlane plane = new DoorwayPlane(-284, 69, 166, Face.S);

		PlaneCrossing crossing = plane.crossing(
			new Vec3d(-283.79D, 68.875D, 164.36D),
			new Vec3d(-283.79D, 68.875D, 166.20D)).orElseThrow();

		assertEquals(plane.center().z(), crossing.point().z(), 1.0E-9D);
		assertFalse(crossing.frontSide());
		assertFalse(plane.crossing(
			new Vec3d(-283.79D, 68.39D, 164.36D),
			new Vec3d(-283.79D, 68.39D, 166.20D)).isPresent());
	}

	@Test
	public void invalidFacingAndNonFiniteCoordinatesAreRejected()
	{
		assertThrows(IllegalArgumentException.class,
			() -> new DoorwayPlane(0, 0, 0, Face.U));
		DoorwayPlane plane = new DoorwayPlane(0, 64, 0, Face.N);
		assertThrows(IllegalArgumentException.class,
			() -> plane.crossing(new Vec3d(Double.NaN, 0.0D, 0.0D), plane.center()));
	}

	@Test
	public void arrivalSidesStaySymmetricAroundPhysicalDoorForEveryFacing()
	{
		for(Face facing : new Face[]{Face.N, Face.S, Face.E, Face.W})
		{
			DoorwayPlane plane = new DoorwayPlane(10, 64, -4, facing);
			for (boolean frontSide : new boolean[] {true, false})
			{
				Vec3d entry = plane.sidePoint((frontSide ? 1 : -1), 1.0D);
				Vec3d exit = plane.sidePoint((frontSide ? -1 : 1), 1.0D);

				assertEquals((frontSide ? 1 : -1), physicalNormalOffset(plane, entry), 1.0E-9D);
				assertEquals((frontSide ? -1 : 1), physicalNormalOffset(plane, exit), 1.0E-9D);
				assertTrue(plane.signedDistance(entry) * (frontSide ? 1 : -1) > 0.0D);
				assertTrue(plane.signedDistance(exit) * (frontSide ? -1 : 1) > 0.0D);
				assertEquals(0.5D, entry.x() - Math.floor(entry.x()), 1.0E-9D);
				assertEquals(0.5D, entry.z() - Math.floor(entry.z()), 1.0E-9D);
				assertEquals(0.5D, exit.x() - Math.floor(exit.x()), 1.0E-9D);
				assertEquals(0.5D, exit.z() - Math.floor(exit.z()), 1.0E-9D);
				assertEquals(64.0D, entry.y(), 0.0D);
				assertEquals(64.0D, exit.y(), 0.0D);
			}
		}
	}

	@Test
	public void yawRotationPreservesTravelDirectionAcrossEveryFacingPair()
	{
		for(Face sourceFacing : new Face[]{Face.N, Face.S, Face.E, Face.W})
		{
			DoorwayPlane source = new DoorwayPlane(0, 64, 0, sourceFacing);
			for(Face targetFacing : new Face[]{Face.N, Face.S, Face.E, Face.W})
			{
				DoorwayPlane target = new DoorwayPlane(100, 70, 100, targetFacing);
				for (boolean frontSide : new boolean[] {true, false})
				{
					int sourceSign = (frontSide ? -1 : 1);
					int targetSign = (frontSide ? -1 : 1);
					float sourceYaw = vectorYaw(
						sourceFacing.x() * sourceSign,
						sourceFacing.z() * sourceSign);
					float expectedYaw = vectorYaw(
						targetFacing.x() * targetSign,
						targetFacing.z() * targetSign);

					assertEquals(expectedYaw, source.rotateYawTo(target, sourceYaw), 1.0E-6F,
						sourceFacing + " -> " + targetFacing + " " + frontSide);
				}
			}
		}
	}

	@Test
	public void arrivalGeometryRejectsInvalidOffsetsAndYaw()
	{
		DoorwayPlane plane = new DoorwayPlane(0, 64, 0, Face.S);
		assertThrows(IllegalArgumentException.class,
			() -> plane.sidePoint(1, 0.0D));
		assertThrows(IllegalArgumentException.class,
			() -> plane.sidePoint(-1, Double.NaN));
		assertThrows(IllegalArgumentException.class,
			() -> plane.rotateYawTo(plane, Float.NaN));
		assertThrows(NullPointerException.class,
			() -> plane.rotateYawToMatchingSide(null, 0.0F));
		assertThrows(IllegalArgumentException.class,
			() -> new DoorTransit(plane, true, Float.NaN, 0.0F));
		assertThrows(IllegalArgumentException.class,
			() -> new DoorTransit(
				plane, true, 0.0F, 0.0F, 0.0D, 1.8D));
	}

	@Test
	public void aTrapdoorPlaneLiesFlatAtItsPlateHeightForBothHalves()
	{
		for(Face facing : CARDINALS)
		{
			DoorwayPlane bottom = DoorwayPlane.trapdoor(
				3, 70, -9, facing, DoorHalf.BOTTOM, DoorOpenState.OPEN);
			DoorwayPlane top = DoorwayPlane.trapdoor(
				3, 70, -9, facing, DoorHalf.TOP, DoorOpenState.OPEN);

			assertTrue(bottom.isTrapdoor());
			assertTrue(bottom.horizontal());
			assertFalse(bottom.contactSurface());
			assertEquals(0.0D, bottom.normalX(), TOLERANCE);
			assertEquals(1.0D, bottom.normalY(), TOLERANCE);
			assertEquals(0.0D, bottom.normalZ(), TOLERANCE);
			// the crossing plane is the middle of the plate slab, where the veil is drawn
			assertEquals(70.0D + (3.0D / 32.0D), bottom.planeY(), TOLERANCE);
			assertEquals(71.0D - (3.0D / 32.0D), top.planeY(), TOLERANCE);
			assertEquals(3.5D, bottom.center().x(), TOLERANCE);
			assertEquals(-8.5D, bottom.center().z(), TOLERANCE);
			assertEquals(bottom.planeY(), bottom.center().y(), TOLERANCE);
			assertEquals(top.planeY(), top.center().y(), TOLERANCE);
		}
	}

	@Test
	public void aHingedPlaneRejectsATopAnchorButAllowsEitherOpenState()
	{
		assertThrows(IllegalArgumentException.class,
			() -> new DoorwayPlane(
				0, 64, 0, Face.N, DoorForm.DOOR, DoorHalf.TOP, DoorOpenState.OPEN));
		assertTrue(new DoorwayPlane(
			0, 64, 0, Face.N, DoorForm.DOOR, DoorHalf.BOTTOM, DoorOpenState.CLOSED)
			.contactSurface());
		assertThrows(IllegalArgumentException.class,
			() -> DoorwayPlane.trapdoor(
				0, 64, 0, Face.U, DoorHalf.BOTTOM, DoorOpenState.OPEN));
	}

	@Test
	public void fallingThroughATrapdoorCrossesFrontToBackAndClimbingCrossesBackToFront()
	{
		for(Face facing : CARDINALS)
		{
			for(DoorHalf half : DoorHalf.values())
			{
				DoorwayPlane plane = DoorwayPlane.trapdoor(
					0, 64, 0, facing, half, DoorOpenState.OPEN);
				double planeY = plane.planeY();

				PlaneCrossing falling = plane.crossing(
					new Vec3d(0.5D, planeY + 0.9D, 0.5D),
					new Vec3d(0.5D, planeY - 0.9D, 0.5D)).orElseThrow();
				PlaneCrossing climbing = plane.crossing(
					new Vec3d(0.5D, planeY - 0.9D, 0.5D),
					new Vec3d(0.5D, planeY + 0.9D, 0.5D)).orElseThrow();

				assertTrue(falling.frontSide());
				assertEquals(1, (falling.frontSide() ? 1 : -1));
				assertEquals(-1, (falling.frontSide() ? -1 : 1));
				assertFalse(climbing.frontSide());
				assertEquals(planeY, falling.point().y(), TOLERANCE);
				assertEquals(planeY, climbing.point().y(), TOLERANCE);
			}
		}
	}

	@Test
	public void aTrapdoorApertureIsOneBlockWideOnBothInPlaneAxes()
	{
		for(Face facing : CARDINALS)
		{
			DoorwayPlane plane = DoorwayPlane.trapdoor(
				0, 64, 0, facing, DoorHalf.BOTTOM, DoorOpenState.OPEN);
			double planeY = plane.planeY();

			assertTrue(plane.crossing(
				new Vec3d(0.95D, planeY + 0.5D, 0.95D),
				new Vec3d(0.95D, planeY - 0.5D, 0.95D)).isPresent(), "inside the plate");
			assertTrue(plane.crossing(
				new Vec3d(2.5D, planeY + 0.5D, 0.5D),
				new Vec3d(2.5D, planeY - 0.5D, 0.5D)).isEmpty(), "two blocks east of the plate");
			assertTrue(plane.crossing(
				new Vec3d(0.5D, planeY + 0.5D, -1.5D),
				new Vec3d(0.5D, planeY - 0.5D, -1.5D)).isEmpty(), "two blocks north of the plate");
		}
	}

	@Test
	public void slidingAlongATrapdoorPlaneIsNeverACrossing()
	{
		DoorwayPlane plane = DoorwayPlane.trapdoor(
			0, 64, 0, Face.S, DoorHalf.TOP, DoorOpenState.OPEN);

		assertTrue(plane.crossing(
			new Vec3d(0.2D, plane.planeY(), 0.2D),
			new Vec3d(0.8D, plane.planeY(), 0.8D)).isEmpty());
	}

	@Test
	public void sidePointsOfATrapdoorSitDirectlyAboveAndBelowThePlate()
	{
		DoorwayPlane plane = DoorwayPlane.trapdoor(
			-4, 12, 8, Face.E, DoorHalf.BOTTOM, DoorOpenState.OPEN);

		Vec3d above = plane.sidePoint(1, 1.0D);
		Vec3d below = plane.sidePoint(-1, 1.0D);

		assertEquals(-3.5D, above.x(), TOLERANCE);
		assertEquals(8.5D, above.z(), TOLERANCE);
		assertEquals(plane.planeY() + 1.0D, above.y(), TOLERANCE);
		assertEquals(plane.planeY() - 1.0D, below.y(), TOLERANCE);
		assertEquals(
			below,
			plane.sidePoint(-1, 1.0D),
			"falling in exits underneath");
		assertEquals(
			above,
			plane.sidePoint(1, 1.0D),
			"climbing in exits on top");
		assertThrows(IllegalArgumentException.class, () -> plane.sidePoint(1, 0.0D));
	}

	@Test
	public void anInvertedTrapdoorFiresWhenATravelerLandsOnTheClosedPlate()
	{
		for(DoorHalf half : DoorHalf.values())
		{
			DoorwayPlane pad = DoorwayPlane.trapdoor(
				0, 64, 0, Face.N, half, DoorOpenState.CLOSED);
			double planeY = pad.planeY();

			PlaneCrossing landing = pad.contact(
				new Vec3d(0.5D, planeY + 0.6D, 0.5D),
				new Vec3d(0.5D, planeY + 0.02D, 0.5D)).orElseThrow();

			assertTrue(pad.contactSurface());
			assertTrue(landing.frontSide());
			assertEquals(planeY + 0.02D, landing.point().y(), TOLERANCE);
		}
	}

	@Test
	public void aPadAlsoFiresForATravelerRisingIntoItFromUnderneath()
	{
		DoorwayPlane pad = DoorwayPlane.trapdoor(
			0, 64, 0, Face.S, DoorHalf.TOP, DoorOpenState.CLOSED);
		double planeY = pad.planeY();

		PlaneCrossing contact = pad.contact(
			new Vec3d(0.5D, planeY - 0.6D, 0.5D),
			new Vec3d(0.5D, planeY - 0.02D, 0.5D)).orElseThrow();

		assertFalse(contact.frontSide());
	}

	@Test
	public void aClosedHingedDoorUsesTravelerWidthToDetectContactOnEitherFace()
	{
		DoorwayPlane plane = new DoorwayPlane(
			0, 64, 0, Face.N, DoorForm.DOOR, DoorHalf.BOTTOM, DoorOpenState.CLOSED);
		Vec3d center = plane.center();
		Vec3d positiveFrom = offsetNormal(plane, center, 0.8D, 65.0D);
		Vec3d positiveTo = offsetNormal(plane, center, 0.42D, 65.0D);
		Vec3d negativeFrom = offsetNormal(plane, center, -0.8D, 65.0D);
		Vec3d negativeTo = offsetNormal(plane, center, -0.42D, 65.0D);

		assertTrue(plane.intersect(positiveFrom, positiveTo).isEmpty());
		assertEquals(
			true,
			plane.intersect(positiveFrom, positiveTo, 0.3D, 1.8D).orElseThrow().frontSide());
		assertEquals(
			false,
			plane.intersect(negativeFrom, negativeTo, 0.3D, 1.8D).orElseThrow().frontSide());
	}

	@Test
	public void aClosedTrapdoorUsesTravelerHeightForContactFromBelow()
	{
		DoorwayPlane plane = DoorwayPlane.trapdoor(
			0, 64, 0, Face.S, DoorHalf.TOP, DoorOpenState.CLOSED);
		Vec3d from = new Vec3d(0.5D, plane.planeY() - 2.2D, 0.5D);
		Vec3d to = new Vec3d(0.5D, plane.planeY() - 1.9D, 0.5D);

		assertTrue(plane.intersect(from, to).isEmpty());
		assertEquals(
			false,
			plane.intersect(from, to, 0.3D, 1.8D).orElseThrow().frontSide());
	}

	@Test
	public void standingStillOnAPadNeverFiresItAgain()
	{
		DoorwayPlane pad = DoorwayPlane.trapdoor(
			0, 64, 0, Face.W, DoorHalf.BOTTOM, DoorOpenState.CLOSED);
		double planeY = pad.planeY();

		assertTrue(pad.contact(
			new Vec3d(0.5D, planeY + 0.02D, 0.5D),
			new Vec3d(0.55D, planeY + 0.01D, 0.55D)).isEmpty(), "already on the pad");
		assertTrue(pad.contact(
			new Vec3d(0.5D, planeY + 0.02D, 0.5D),
			new Vec3d(0.5D, planeY + 0.9D, 0.5D)).isEmpty(), "leaving the pad");
	}

	@Test
	public void aPadIgnoresContactOutsideItsOwnBlock()
	{
		DoorwayPlane pad = DoorwayPlane.trapdoor(
			0, 64, 0, Face.E, DoorHalf.BOTTOM, DoorOpenState.CLOSED);
		double planeY = pad.planeY();

		assertTrue(pad.contact(
			new Vec3d(2.5D, planeY + 0.6D, 0.5D),
			new Vec3d(2.5D, planeY + 0.02D, 0.5D)).isEmpty());
	}

	@Test
	public void polarityDecidesWhichActivationRuleIntersectUses()
	{
		DoorwayPlane swing = DoorwayPlane.trapdoor(
			0, 64, 0, Face.N, DoorHalf.BOTTOM, DoorOpenState.OPEN);
		DoorwayPlane pad = DoorwayPlane.trapdoor(
			0, 64, 0, Face.N, DoorHalf.BOTTOM, DoorOpenState.CLOSED);
		Vec3d from = new Vec3d(0.5D, swing.planeY() + 0.6D, 0.5D);
		Vec3d landing = new Vec3d(0.5D, swing.planeY() + 0.02D, 0.5D);
		Vec3d through = new Vec3d(0.5D, swing.planeY() - 0.6D, 0.5D);

		assertTrue(swing.intersect(from, landing).isEmpty(), "a hole is only crossed, never touched");
		assertTrue(swing.intersect(from, through).isPresent());
		assertTrue(pad.intersect(from, landing).isPresent());
		assertTrue(pad.intersect(from, through).isEmpty(), "nothing passes through a solid plate");
	}

	private static double physicalNormalOffset(DoorwayPlane plane, Vec3d point)
	{
		return ((point.x() - (plane.blockX() + 0.5D)) * plane.facing().x())
			+ ((point.z() - (plane.blockZ() + 0.5D)) * plane.facing().z());
	}

	private static float vectorYaw(int x, int z)
	{
		float yaw = (float) Math.toDegrees(Math.atan2(-x, z));
		return yaw >= 180.0F ? yaw - 360.0F : yaw;
	}

	private static Vec3d offsetNormal(DoorwayPlane plane, Vec3d center, double offset, double y)
	{
		return new Vec3d(
			center.x() + (plane.normalX() * offset),
			y,
			center.z() + (plane.normalZ() * offset));
	}
}
