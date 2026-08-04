package art.arcane.wormholes.door;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.UUID;

import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Door;
import org.junit.jupiter.api.Test;

public final class DoorwayPlaneTest
{
	private static final double TOLERANCE = 1.0E-9D;
	private static final BlockFace[] CARDINALS =
		{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};

	@Test
	public void northFacingDoorDetectsFastDiagonalCrossingInsideAperture()
	{
		DoorwayPlane plane = new DoorwayPlane(10, 64, -4, BlockFace.NORTH);

		DoorwayCrossing crossing = plane.crossing(
			new DoorVec3(10.2D, 64.25D, -4.5D),
			new DoorVec3(10.8D, 65.75D, -2.5D)).orElseThrow();

		assertEquals(0.0D, plane.signedDistance(crossing.point()), 1.0E-9D);
		assertEquals(plane.center().z(), crossing.point().z(), 1.0E-9D);
		assertEquals(DoorwayCrossing.Direction.FRONT_TO_BACK, crossing.direction());
	}

	@Test
	public void eastFacingDoorDetectsCrossingInEitherDirection()
	{
		DoorwayPlane plane = new DoorwayPlane(-2, 20, 7, BlockFace.EAST);

		DoorwayCrossing crossing = plane.crossing(
			new DoorVec3(-0.5D, 20.0D, 7.5D),
			new DoorVec3(-3.5D, 20.0D, 7.5D)).orElseThrow();

		assertEquals(DoorwayCrossing.Direction.FRONT_TO_BACK, crossing.direction());
		assertEquals(plane.center().x(), crossing.point().x(), 1.0E-9D);
		assertEquals(7.5D, crossing.point().z(), 1.0E-9D);
	}

	@Test
	public void apertureIncludesPhysicalEdgesButRejectsOutsideAndCoplanarMotion()
	{
		DoorwayPlane plane = new DoorwayPlane(0, 64, 0, BlockFace.SOUTH);

		assertTrue(plane.crossing(
			new DoorVec3(0.0D, 66.0D, 0.0D),
			new DoorVec3(0.0D, 66.0D, 1.0D)).isPresent());
		assertFalse(plane.crossing(
			new DoorVec3(-0.01D, 65.0D, 0.0D),
			new DoorVec3(-0.01D, 65.0D, 1.0D)).isPresent());
		assertFalse(plane.crossing(
			new DoorVec3(0.25D, 66.01D, 0.0D),
			new DoorVec3(0.25D, 66.01D, 1.0D)).isPresent());
		assertFalse(plane.crossing(
			new DoorVec3(0.0D, 65.0D, 0.5D),
			new DoorVec3(1.0D, 65.0D, 0.5D)).isPresent());
	}

	@Test
	public void movementStartingOnPlaneDoesNotPullPlayerThrough()
	{
		DoorwayPlane plane = new DoorwayPlane(0, 64, 0, BlockFace.NORTH);
		DoorVec3 center = plane.center();

		assertFalse(plane.crossing(
			new DoorVec3(center.x(), 64.0D, center.z()),
			new DoorVec3(center.x(), 64.0D, center.z() + 1.0D)).isPresent());
	}

	@Test
	public void recessedThresholdAcceptsNormalStepHeightApproachAtTheSecondEndpoint()
	{
		DoorwayPlane plane = new DoorwayPlane(-284, 69, 166, BlockFace.SOUTH);

		DoorwayCrossing crossing = plane.crossing(
			new DoorVec3(-283.79D, 68.875D, 164.36D),
			new DoorVec3(-283.79D, 68.875D, 166.20D)).orElseThrow();

		assertEquals(plane.center().z(), crossing.point().z(), 1.0E-9D);
		assertEquals(DoorwayCrossing.Direction.BACK_TO_FRONT, crossing.direction());
		assertFalse(plane.crossing(
			new DoorVec3(-283.79D, 68.39D, 164.36D),
			new DoorVec3(-283.79D, 68.39D, 166.20D)).isPresent());
	}

	@Test
	public void vanillaTopHalfNormalizesToLowerDoorAndOpenableStateIsAuthoritative()
	{
		UUID worldId = UUID.randomUUID();
		Door door = doorData(BlockFace.WEST, Bisected.Half.TOP, Door.Hinge.RIGHT, true, false);

		VanillaDoorSnapshot snapshot = VanillaDoorSnapshot.fromBlockData(worldId, 3, 81, 9, door);

		assertEquals(worldId, snapshot.worldId());
		assertEquals(new DoorwayPlane(3, 80, 9, BlockFace.WEST), snapshot.plane());
		assertEquals(Door.Hinge.RIGHT, snapshot.hinge());
		assertTrue(snapshot.open());
		assertFalse(snapshot.powered());
	}

	@Test
	public void invalidFacingAndNonFiniteCoordinatesAreRejected()
	{
		assertThrows(IllegalArgumentException.class,
			() -> new DoorwayPlane(0, 0, 0, BlockFace.UP));
		assertThrows(IllegalArgumentException.class,
			() -> new DoorVec3(Double.NaN, 0.0D, 0.0D));
	}

	@Test
	public void arrivalSidesStaySymmetricAroundPhysicalDoorForEveryFacing()
	{
		for(BlockFace facing : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST})
		{
			DoorwayPlane plane = new DoorwayPlane(10, 64, -4, facing);
			for(DoorwayCrossing.Direction direction : DoorwayCrossing.Direction.values())
			{
				DoorVec3 entry = plane.entrySidePoint(direction, 1.0D);
				DoorVec3 exit = plane.exitSidePoint(direction, 1.0D);

				assertEquals(direction.entrySideSign(), physicalNormalOffset(plane, entry), 1.0E-9D);
				assertEquals(direction.exitSideSign(), physicalNormalOffset(plane, exit), 1.0E-9D);
				assertTrue(plane.signedDistance(entry) * direction.entrySideSign() > 0.0D);
				assertTrue(plane.signedDistance(exit) * direction.exitSideSign() > 0.0D);
				assertEquals(0.5D, entry.x() - Math.floor(entry.x()), 1.0E-9D);
				assertEquals(0.5D, entry.z() - Math.floor(entry.z()), 1.0E-9D);
				assertEquals(0.5D, exit.x() - Math.floor(exit.x()), 1.0E-9D);
				assertEquals(0.5D, exit.z() - Math.floor(exit.z()), 1.0E-9D);
				assertEquals(64.0D, entry.y(), 0.0D);
				assertEquals(64.0D, exit.y(), 0.0D);
			}
		}
	}

	private static double physicalNormalOffset(DoorwayPlane plane, DoorVec3 point)
	{
		return ((point.x() - (plane.blockX() + 0.5D)) * plane.facing().getModX())
			+ ((point.z() - (plane.blockZ() + 0.5D)) * plane.facing().getModZ());
	}

	@Test
	public void enteredClosedDoorFaceMapsToMatchingDestinationFaceAcrossFacingsAndHinges()
	{
		for(BlockFace sourceFacing : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST})
		{
			for(Door.Hinge sourceHinge : new Door.Hinge[]{Door.Hinge.LEFT, Door.Hinge.RIGHT})
			{
				DoorwayPlane source = DoorwayPlane.fromBlockData(
					0, 64, 0, doorData(sourceFacing, Bisected.Half.BOTTOM, sourceHinge, true, false));
				for(BlockFace destinationFacing : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST})
				{
					for(Door.Hinge destinationHinge : new Door.Hinge[]{Door.Hinge.LEFT, Door.Hinge.RIGHT})
					{
						DoorwayPlane destination = DoorwayPlane.fromBlockData(
							100,
							70,
							100,
							doorData(destinationFacing, Bisected.Half.BOTTOM, destinationHinge, true, false));
						for(DoorwayCrossing.Direction direction : DoorwayCrossing.Direction.values())
						{
							float sourceYaw = vectorYaw(
								sourceFacing.getModX() * direction.exitSideSign(),
								sourceFacing.getModZ() * direction.exitSideSign());
							float expectedYaw = vectorYaw(
								destinationFacing.getModX() * direction.entrySideSign(),
								destinationFacing.getModZ() * direction.entrySideSign());
							DoorTransit transit = new DoorTransit(source, direction, sourceYaw, 0.0F);
							DoorVec3 arrival = DimensionalDoorManager.arrivalPoint(destination, transit);
							String scenario = sourceFacing + " " + sourceHinge + " -> "
								+ destinationFacing + " " + destinationHinge + " " + direction;

							assertEquals(destination.entrySidePoint(direction, 1.0D), arrival, scenario);
							assertEquals(direction.entrySideSign(), physicalNormalOffset(destination, arrival), 1.0E-9D, scenario);
							assertTrue(destination.signedDistance(arrival) * direction.entrySideSign() > 0.0D, scenario);
							assertEquals(expectedYaw, DimensionalDoorManager.arrivalYaw(source, destination, transit), 1.0E-6F, scenario);
						}
					}
				}
			}
		}
	}

	@Test
	public void destinationArrivalCanStepDownWithoutChangingDoorSide()
	{
		DoorwayPlane plane = new DoorwayPlane(-132, 68, 56, BlockFace.EAST);
		DoorVec3 nominal = plane.entrySidePoint(DoorwayCrossing.Direction.BACK_TO_FRONT, 1.0D);
		DoorVec3 selected = DimensionalDoorManager.findSafeVerticalDoorStanding(
			nominal,
			candidate -> candidate.y() == 67.0D).orElseThrow();

		assertEquals(nominal.x(), selected.x(), 0.0D);
		assertEquals(67.0D, selected.y(), 0.0D);
		assertEquals(nominal.z(), selected.z(), 0.0D);
		assertEquals(plane.signedDistance(nominal), plane.signedDistance(selected), 0.0D);
	}

	@Test
	public void destinationArrivalPrefersTheDoorBaseHeight()
	{
		DoorVec3 nominal = new DoorVec3(4.5D, 70.0D, -2.5D);

		DoorVec3 selected = DimensionalDoorManager.findSafeVerticalDoorStanding(
			nominal,
			candidate -> candidate.y() == 70.0D || candidate.y() == 69.0D).orElseThrow();

		assertEquals(nominal, selected);
	}

	@Test
	public void yawRotationPreservesTravelDirectionAcrossEveryFacingPair()
	{
		for(BlockFace sourceFacing : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST})
		{
			DoorwayPlane source = new DoorwayPlane(0, 64, 0, sourceFacing);
			for(BlockFace targetFacing : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST})
			{
				DoorwayPlane target = new DoorwayPlane(100, 70, 100, targetFacing);
				for(DoorwayCrossing.Direction direction : DoorwayCrossing.Direction.values())
				{
					int sourceSign = direction.exitSideSign();
					int targetSign = direction.exitSideSign();
					float sourceYaw = vectorYaw(
						sourceFacing.getModX() * sourceSign,
						sourceFacing.getModZ() * sourceSign);
					float expectedYaw = vectorYaw(
						targetFacing.getModX() * targetSign,
						targetFacing.getModZ() * targetSign);

					assertEquals(expectedYaw, source.rotateYawTo(target, sourceYaw), 1.0E-6F,
						sourceFacing + " -> " + targetFacing + " " + direction);
				}
			}
		}
	}

	@Test
	public void arrivalGeometryRejectsInvalidOffsetsAndYaw()
	{
		DoorwayPlane plane = new DoorwayPlane(0, 64, 0, BlockFace.SOUTH);
		assertThrows(IllegalArgumentException.class,
			() -> plane.entrySidePoint(DoorwayCrossing.Direction.FRONT_TO_BACK, 0.0D));
		assertThrows(IllegalArgumentException.class,
			() -> plane.exitSidePoint(DoorwayCrossing.Direction.FRONT_TO_BACK, Double.NaN));
		assertThrows(IllegalArgumentException.class,
			() -> plane.rotateYawTo(plane, Float.NaN));
		assertThrows(NullPointerException.class,
			() -> plane.rotateYawToMatchingSide(null, 0.0F));
		assertThrows(IllegalArgumentException.class,
			() -> new DoorTransit(plane, DoorwayCrossing.Direction.FRONT_TO_BACK, Float.NaN, 0.0F));
		assertThrows(IllegalArgumentException.class,
			() -> new DoorTransit(
				plane, DoorwayCrossing.Direction.FRONT_TO_BACK, 0.0F, 0.0F, 0.0D, 1.8D));
	}

	private static float vectorYaw(int x, int z)
	{
		float yaw = (float) Math.toDegrees(Math.atan2(-x, z));
		return yaw >= 180.0F ? yaw - 360.0F : yaw;
	}

	// ---- trapdoor planes -------------------------------------------------

	@Test
	public void aTrapdoorPlaneLiesFlatAtItsPlateHeightForBothHalves()
	{
		for(BlockFace facing : CARDINALS)
		{
			DoorwayPlane bottom = DoorwayPlane.trapdoor(
				3, 70, -9, facing, Bisected.Half.BOTTOM, DoorOpenState.OPEN);
			DoorwayPlane top = DoorwayPlane.trapdoor(
				3, 70, -9, facing, Bisected.Half.TOP, DoorOpenState.OPEN);

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
				0, 64, 0, BlockFace.NORTH, DoorForm.DOOR, Bisected.Half.TOP, DoorOpenState.OPEN));
		assertTrue(new DoorwayPlane(
			0, 64, 0, BlockFace.NORTH, DoorForm.DOOR, Bisected.Half.BOTTOM, DoorOpenState.CLOSED)
			.contactSurface());
		assertThrows(IllegalArgumentException.class,
			() -> DoorwayPlane.trapdoor(
				0, 64, 0, BlockFace.UP, Bisected.Half.BOTTOM, DoorOpenState.OPEN));
	}

	@Test
	public void aTrapdoorIsCapturedWhereItStandsWithNoHalfNormalization()
	{
		for(Bisected.Half half : Bisected.Half.values())
		{
			DoorwayPlane plane = DoorwayPlane.fromBlockData(
				6, 91, 2, trapDoorData(BlockFace.WEST, half, false), DoorOpenState.CLOSED);

			assertEquals(6, plane.blockX());
			assertEquals(91, plane.blockY());
			assertEquals(2, plane.blockZ());
			assertEquals(half, plane.half());
			assertEquals(BlockFace.WEST, plane.facing());
			assertEquals(DoorForm.TRAPDOOR, plane.form());
			assertTrue(plane.contactSurface());
		}
	}

	@Test
	public void fallingThroughATrapdoorCrossesFrontToBackAndClimbingCrossesBackToFront()
	{
		for(BlockFace facing : CARDINALS)
		{
			for(Bisected.Half half : Bisected.Half.values())
			{
				DoorwayPlane plane = DoorwayPlane.trapdoor(
					0, 64, 0, facing, half, DoorOpenState.OPEN);
				double planeY = plane.planeY();

				DoorwayCrossing falling = plane.crossing(
					new DoorVec3(0.5D, planeY + 0.9D, 0.5D),
					new DoorVec3(0.5D, planeY - 0.9D, 0.5D)).orElseThrow();
				DoorwayCrossing climbing = plane.crossing(
					new DoorVec3(0.5D, planeY - 0.9D, 0.5D),
					new DoorVec3(0.5D, planeY + 0.9D, 0.5D)).orElseThrow();

				assertEquals(DoorwayCrossing.Direction.FRONT_TO_BACK, falling.direction());
				assertEquals(1, falling.direction().entrySideSign());
				assertEquals(-1, falling.direction().exitSideSign());
				assertEquals(DoorwayCrossing.Direction.BACK_TO_FRONT, climbing.direction());
				assertEquals(planeY, falling.point().y(), TOLERANCE);
				assertEquals(planeY, climbing.point().y(), TOLERANCE);
			}
		}
	}

	@Test
	public void aTrapdoorApertureIsOneBlockWideOnBothInPlaneAxes()
	{
		for(BlockFace facing : CARDINALS)
		{
			DoorwayPlane plane = DoorwayPlane.trapdoor(
				0, 64, 0, facing, Bisected.Half.BOTTOM, DoorOpenState.OPEN);
			double planeY = plane.planeY();

			assertTrue(plane.crossing(
				new DoorVec3(0.95D, planeY + 0.5D, 0.95D),
				new DoorVec3(0.95D, planeY - 0.5D, 0.95D)).isPresent(), "inside the plate");
			assertTrue(plane.crossing(
				new DoorVec3(2.5D, planeY + 0.5D, 0.5D),
				new DoorVec3(2.5D, planeY - 0.5D, 0.5D)).isEmpty(), "two blocks east of the plate");
			assertTrue(plane.crossing(
				new DoorVec3(0.5D, planeY + 0.5D, -1.5D),
				new DoorVec3(0.5D, planeY - 0.5D, -1.5D)).isEmpty(), "two blocks north of the plate");
		}
	}

	@Test
	public void slidingAlongATrapdoorPlaneIsNeverACrossing()
	{
		DoorwayPlane plane = DoorwayPlane.trapdoor(
			0, 64, 0, BlockFace.SOUTH, Bisected.Half.TOP, DoorOpenState.OPEN);

		assertTrue(plane.crossing(
			new DoorVec3(0.2D, plane.planeY(), 0.2D),
			new DoorVec3(0.8D, plane.planeY(), 0.8D)).isEmpty());
	}

	@Test
	public void sidePointsOfATrapdoorSitDirectlyAboveAndBelowThePlate()
	{
		DoorwayPlane plane = DoorwayPlane.trapdoor(
			-4, 12, 8, BlockFace.EAST, Bisected.Half.BOTTOM, DoorOpenState.OPEN);

		DoorVec3 above = plane.sidePoint(1, 1.0D);
		DoorVec3 below = plane.sidePoint(-1, 1.0D);

		assertEquals(-3.5D, above.x(), TOLERANCE);
		assertEquals(8.5D, above.z(), TOLERANCE);
		assertEquals(plane.planeY() + 1.0D, above.y(), TOLERANCE);
		assertEquals(plane.planeY() - 1.0D, below.y(), TOLERANCE);
		assertEquals(
			below,
			plane.exitSidePoint(DoorwayCrossing.Direction.FRONT_TO_BACK, 1.0D),
			"falling in exits underneath");
		assertEquals(
			above,
			plane.exitSidePoint(DoorwayCrossing.Direction.BACK_TO_FRONT, 1.0D),
			"climbing in exits on top");
		assertThrows(IllegalArgumentException.class, () -> plane.sidePoint(1, 0.0D));
	}

	// ---- contact pads ----------------------------------------------------

	@Test
	public void anInvertedTrapdoorFiresWhenATravelerLandsOnTheClosedPlate()
	{
		for(Bisected.Half half : Bisected.Half.values())
		{
			DoorwayPlane pad = DoorwayPlane.trapdoor(
				0, 64, 0, BlockFace.NORTH, half, DoorOpenState.CLOSED);
			double planeY = pad.planeY();

			DoorwayCrossing landing = pad.contact(
				new DoorVec3(0.5D, planeY + 0.6D, 0.5D),
				new DoorVec3(0.5D, planeY + 0.02D, 0.5D)).orElseThrow();

			assertTrue(pad.contactSurface());
			assertEquals(DoorwayCrossing.Direction.FRONT_TO_BACK, landing.direction());
			assertEquals(planeY + 0.02D, landing.point().y(), TOLERANCE);
		}
	}

	@Test
	public void aPadAlsoFiresForATravelerRisingIntoItFromUnderneath()
	{
		DoorwayPlane pad = DoorwayPlane.trapdoor(
			0, 64, 0, BlockFace.SOUTH, Bisected.Half.TOP, DoorOpenState.CLOSED);
		double planeY = pad.planeY();

		DoorwayCrossing contact = pad.contact(
			new DoorVec3(0.5D, planeY - 0.6D, 0.5D),
			new DoorVec3(0.5D, planeY - 0.02D, 0.5D)).orElseThrow();

		assertEquals(DoorwayCrossing.Direction.BACK_TO_FRONT, contact.direction());
	}

	@Test
	public void aClosedHingedDoorUsesTravelerWidthToDetectContactOnEitherFace()
	{
		DoorwayPlane plane = new DoorwayPlane(
			0, 64, 0, BlockFace.NORTH, DoorForm.DOOR, Bisected.Half.BOTTOM, DoorOpenState.CLOSED);
		DoorVec3 center = plane.center();
		DoorVec3 positiveFrom = offsetNormal(plane, center, 0.8D, 65.0D);
		DoorVec3 positiveTo = offsetNormal(plane, center, 0.42D, 65.0D);
		DoorVec3 negativeFrom = offsetNormal(plane, center, -0.8D, 65.0D);
		DoorVec3 negativeTo = offsetNormal(plane, center, -0.42D, 65.0D);

		assertTrue(plane.intersect(positiveFrom, positiveTo).isEmpty());
		assertEquals(
			DoorwayCrossing.Direction.FRONT_TO_BACK,
			plane.intersect(positiveFrom, positiveTo, 0.3D, 1.8D).orElseThrow().direction());
		assertEquals(
			DoorwayCrossing.Direction.BACK_TO_FRONT,
			plane.intersect(negativeFrom, negativeTo, 0.3D, 1.8D).orElseThrow().direction());
	}

	@Test
	public void aClosedTrapdoorUsesTravelerHeightForContactFromBelow()
	{
		DoorwayPlane plane = DoorwayPlane.trapdoor(
			0, 64, 0, BlockFace.SOUTH, Bisected.Half.TOP, DoorOpenState.CLOSED);
		DoorVec3 from = new DoorVec3(0.5D, plane.planeY() - 2.2D, 0.5D);
		DoorVec3 to = new DoorVec3(0.5D, plane.planeY() - 1.9D, 0.5D);

		assertTrue(plane.intersect(from, to).isEmpty());
		assertEquals(
			DoorwayCrossing.Direction.BACK_TO_FRONT,
			plane.intersect(from, to, 0.3D, 1.8D).orElseThrow().direction());
	}

	@Test
	public void standingStillOnAPadNeverFiresItAgain()
	{
		DoorwayPlane pad = DoorwayPlane.trapdoor(
			0, 64, 0, BlockFace.WEST, Bisected.Half.BOTTOM, DoorOpenState.CLOSED);
		double planeY = pad.planeY();

		assertTrue(pad.contact(
			new DoorVec3(0.5D, planeY + 0.02D, 0.5D),
			new DoorVec3(0.55D, planeY + 0.01D, 0.55D)).isEmpty(), "already on the pad");
		assertTrue(pad.contact(
			new DoorVec3(0.5D, planeY + 0.02D, 0.5D),
			new DoorVec3(0.5D, planeY + 0.9D, 0.5D)).isEmpty(), "leaving the pad");
	}

	@Test
	public void aPadIgnoresContactOutsideItsOwnBlock()
	{
		DoorwayPlane pad = DoorwayPlane.trapdoor(
			0, 64, 0, BlockFace.EAST, Bisected.Half.BOTTOM, DoorOpenState.CLOSED);
		double planeY = pad.planeY();

		assertTrue(pad.contact(
			new DoorVec3(2.5D, planeY + 0.6D, 0.5D),
			new DoorVec3(2.5D, planeY + 0.02D, 0.5D)).isEmpty());
	}

	@Test
	public void polarityDecidesWhichActivationRuleIntersectUses()
	{
		DoorwayPlane swing = DoorwayPlane.trapdoor(
			0, 64, 0, BlockFace.NORTH, Bisected.Half.BOTTOM, DoorOpenState.OPEN);
		DoorwayPlane pad = DoorwayPlane.trapdoor(
			0, 64, 0, BlockFace.NORTH, Bisected.Half.BOTTOM, DoorOpenState.CLOSED);
		DoorVec3 from = new DoorVec3(0.5D, swing.planeY() + 0.6D, 0.5D);
		DoorVec3 landing = new DoorVec3(0.5D, swing.planeY() + 0.02D, 0.5D);
		DoorVec3 through = new DoorVec3(0.5D, swing.planeY() - 0.6D, 0.5D);

		assertTrue(swing.intersect(from, landing).isEmpty(), "a hole is only crossed, never touched");
		assertTrue(swing.intersect(from, through).isPresent());
		assertTrue(pad.intersect(from, landing).isPresent());
		assertTrue(pad.intersect(from, through).isEmpty(), "nothing passes through a solid plate");
	}

	private static org.bukkit.block.data.type.TrapDoor trapDoorData(
		BlockFace facing,
		Bisected.Half half,
		boolean open)
	{
		return (org.bukkit.block.data.type.TrapDoor) Proxy.newProxyInstance(
			org.bukkit.block.data.type.TrapDoor.class.getClassLoader(),
			new Class<?>[]{org.bukkit.block.data.type.TrapDoor.class},
			(proxy, method, arguments) -> switch(method.getName())
			{
				case "getFacing" -> facing;
				case "getHalf" -> half;
				case "isOpen" -> open;
				case "isPowered" -> false;
				case "toString" -> "TestTrapDoorData";
				case "hashCode" -> System.identityHashCode(proxy);
				case "equals" -> proxy == arguments[0];
				default -> throw new UnsupportedOperationException(method.getName());
			});
	}

	private static DoorVec3 offsetNormal(DoorwayPlane plane, DoorVec3 center, double offset, double y)
	{
		return new DoorVec3(
			center.x() + (plane.normalX() * offset),
			y,
			center.z() + (plane.normalZ() * offset));
	}

	private static Door doorData(
		BlockFace facing,
		Bisected.Half half,
		Door.Hinge hinge,
		boolean open,
		boolean powered)
	{
		return (Door) Proxy.newProxyInstance(
			Door.class.getClassLoader(),
			new Class<?>[]{Door.class},
			(proxy, method, arguments) -> switch(method.getName())
			{
				case "getFacing" -> facing;
				case "getHalf" -> half;
				case "getHinge" -> hinge;
				case "isOpen" -> open;
				case "isPowered" -> powered;
				case "toString" -> "TestDoorData";
				case "hashCode" -> System.identityHashCode(proxy);
				case "equals" -> proxy == arguments[0];
				default -> throw new UnsupportedOperationException(method.getName());
			});
	}
}
