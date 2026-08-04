package art.arcane.wormholes.door;

import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class DoorVelocityTransformTest
{
	private static final BlockFace[] CARDINALS =
		{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};
	private static final double TOLERANCE = 1.0E-9D;

	/** Minecraft heading for a yaw: 0 looks toward +Z, 90 toward -X. */
	private static DoorVec3 heading(float yaw, double speed, double vertical)
	{
		double radians = Math.toRadians(yaw);
		return new DoorVec3(-Math.sin(radians) * speed, vertical, Math.cos(radians) * speed);
	}

	@Test
	void rotatedMomentumMatchesTheArrivalYawForEveryCardinalPairing()
	{
		for(BlockFace sourceFacing : CARDINALS)
		{
			DoorwayPlane source = new DoorwayPlane(0, 64, 0, sourceFacing);
			for(BlockFace destinationFacing : CARDINALS)
			{
				DoorwayPlane destination = new DoorwayPlane(40, 64, 40, destinationFacing);
				for(float yaw : new float[] {0.0F, 45.0F, 90.0F, 179.0F, -90.0F, -135.0F})
				{
					float arrivalYaw = source.rotateYawToMatchingSide(destination, yaw);
					DoorVec3 rotated = DoorVelocityTransform.rotateYaw(
						heading(yaw, 2.5D, 0.4D), arrivalYaw - yaw);
					DoorVec3 expected = heading(arrivalYaw, 2.5D, 0.4D);
					String label = sourceFacing + "->" + destinationFacing + "@" + yaw;

					assertEquals(expected.x(), rotated.x(), TOLERANCE, label);
					assertEquals(expected.y(), rotated.y(), TOLERANCE, label);
					assertEquals(expected.z(), rotated.z(), TOLERANCE, label);
				}
			}
		}
	}

	@Test
	void verticalMomentumAndSpeedSurviveTheRotation()
	{
		DoorVec3 velocity = new DoorVec3(1.5D, -0.75D, -0.5D);
		DoorVec3 rotated = DoorVelocityTransform.rotateYaw(velocity, 137.0F);

		assertEquals(-0.75D, rotated.y(), TOLERANCE);
		assertEquals(
			Math.hypot(velocity.x(), velocity.z()),
			Math.hypot(rotated.x(), rotated.z()),
			TOLERANCE);
	}

	@Test
	void aZeroDeltaLeavesMomentumUntouched()
	{
		DoorVec3 velocity = new DoorVec3(0.25D, 1.0D, -3.0D);
		DoorVec3 rotated = DoorVelocityTransform.rotateYaw(velocity, 0.0F);

		assertEquals(velocity.x(), rotated.x(), TOLERANCE);
		assertEquals(velocity.y(), rotated.y(), TOLERANCE);
		assertEquals(velocity.z(), rotated.z(), TOLERANCE);
	}

	@Test
	void aQuarterTurnSwingsSouthwardMomentumWestward()
	{
		DoorVec3 rotated = DoorVelocityTransform.rotateYaw(new DoorVec3(0.0D, 0.0D, 1.0D), 90.0F);

		assertEquals(-1.0D, rotated.x(), TOLERANCE);
		assertEquals(0.0D, rotated.z(), TOLERANCE);
	}

	@Test
	void absentMomentumStaysAbsent()
	{
		assertNull(DoorVelocityTransform.rotateYaw(null, 42.0F));
		assertNull(DoorVelocityTransform.map(
			new DoorwayPlane(0, 64, 0, BlockFace.NORTH),
			new DoorwayPlane(9, 64, 9, BlockFace.EAST),
			null));
	}

	// ---- plane-to-plane mapping matrix -----------------------------------

	@Test
	void everyPairingPreservesSpeedExactly()
	{
		DoorVec3 velocity = new DoorVec3(0.8D, -1.4D, 0.3D);
		for(DoorwayPlane source : everyPlane(0, 64, 0))
		{
			for(DoorwayPlane destination : everyPlane(48, 64, -32))
			{
				DoorVec3 mapped = DoorVelocityTransform.map(source, destination, velocity);

				assertEquals(length(velocity), length(mapped), TOLERANCE,
					label(source) + "->" + label(destination));
			}
		}
	}

	@Test
	void twoHingedDoorsKeepTheEstablishedFrontToFrontRule()
	{
		for(BlockFace sourceFacing : CARDINALS)
		{
			DoorwayPlane source = new DoorwayPlane(0, 64, 0, sourceFacing);
			for(BlockFace destinationFacing : CARDINALS)
			{
				DoorwayPlane destination = new DoorwayPlane(30, 64, 30, destinationFacing);
				DoorVec3 velocity = new DoorVec3(
					sourceFacing.getModX() * 2.0D, 0.6D, sourceFacing.getModZ() * 2.0D);
				DoorVec3 mapped = DoorVelocityTransform.map(source, destination, velocity);

				// entering along the source normal leaves against the destination normal
				assertEquals(-2.0D, normalComponent(destination, mapped), TOLERANCE);
				assertEquals(0.6D, mapped.y(), TOLERANCE, "gravity is the same on both sides");
			}
		}
	}

	@Test
	void aFallThroughATrapdoorKeepsFalling()
	{
		DoorVec3 falling = new DoorVec3(0.0D, -1.6D, 0.0D);
		for(BlockFace sourceFacing : CARDINALS)
		{
			DoorwayPlane source = trapdoor(0, 64, 0, sourceFacing);
			for(BlockFace destinationFacing : CARDINALS)
			{
				DoorwayPlane destination = trapdoor(20, 30, 20, destinationFacing);
				DoorVec3 mapped = DoorVelocityTransform.map(source, destination, falling);

				assertEquals(-1.6D, mapped.y(), TOLERANCE);
			}
		}
	}

	@Test
	void aShotFiredUpThroughATrapdoorKeepsClimbing()
	{
		DoorVec3 rising = new DoorVec3(0.0D, 2.4D, 0.0D);
		DoorwayPlane source = trapdoor(0, 64, 0, BlockFace.NORTH);
		DoorwayPlane destination = trapdoor(80, 12, -40, BlockFace.WEST);

		assertEquals(2.4D, DoorVelocityTransform.map(source, destination, rising).y(), TOLERANCE);
	}

	@Test
	void aDoorwayHandsAHorizontalShotToATrapdoorAsAVerticalOne()
	{
		DoorwayPlane source = new DoorwayPlane(0, 64, 0, BlockFace.NORTH);
		DoorwayPlane destination = trapdoor(30, 64, 30, BlockFace.SOUTH);
		// travelling along the north-facing door's own normal, so straight out of it
		DoorVec3 velocity = new DoorVec3(0.0D, 0.0D, -3.0D);

		DoorVec3 mapped = DoorVelocityTransform.map(source, destination, velocity);

		assertEquals(3.0D, mapped.y(), TOLERANCE, "out of the doorway becomes up through the plate");
		assertEquals(0.0D, mapped.x(), TOLERANCE);
		assertEquals(0.0D, mapped.z(), TOLERANCE);
	}

	@Test
	void aTrapdoorHandsAFallToADoorwayAsAHorizontalShot()
	{
		DoorwayPlane source = trapdoor(0, 64, 0, BlockFace.EAST);
		DoorwayPlane destination = new DoorwayPlane(-20, 64, 5, BlockFace.WEST);
		DoorVec3 velocity = new DoorVec3(0.0D, -2.0D, 0.0D);

		DoorVec3 mapped = DoorVelocityTransform.map(source, destination, velocity);

		assertEquals(0.0D, mapped.y(), TOLERANCE);
		assertEquals(-2.0D, normalComponent(destination, mapped), TOLERANCE,
			"falling in leaves straight out the far side");
	}

	@Test
	void mappingRequiresBothPlanes()
	{
		DoorwayPlane plane = new DoorwayPlane(0, 64, 0, BlockFace.NORTH);
		DoorVec3 velocity = new DoorVec3(1.0D, 0.0D, 0.0D);

		assertThrows(NullPointerException.class, () -> DoorVelocityTransform.map(null, plane, velocity));
		assertThrows(NullPointerException.class, () -> DoorVelocityTransform.map(plane, null, velocity));
	}

	private static DoorwayPlane trapdoor(int x, int y, int z, BlockFace facing)
	{
		return DoorwayPlane.trapdoor(
			x, y, z, facing, org.bukkit.block.data.Bisected.Half.BOTTOM, DoorOpenState.OPEN);
	}

	private static DoorwayPlane[] everyPlane(int x, int y, int z)
	{
		DoorwayPlane[] planes = new DoorwayPlane[CARDINALS.length * 2];
		for(int index = 0; index < CARDINALS.length; index++)
		{
			planes[index] = new DoorwayPlane(x, y, z, CARDINALS[index]);
			planes[CARDINALS.length + index] = trapdoor(x, y, z, CARDINALS[index]);
		}
		return planes;
	}

	private static String label(DoorwayPlane plane)
	{
		return plane.form() + ":" + plane.facing();
	}

	private static double normalComponent(DoorwayPlane plane, DoorVec3 velocity)
	{
		return (velocity.x() * plane.normalX())
			+ (velocity.y() * plane.normalY())
			+ (velocity.z() * plane.normalZ());
	}

	private static double length(DoorVec3 velocity)
	{
		return Math.sqrt((velocity.x() * velocity.x())
			+ (velocity.y() * velocity.y())
			+ (velocity.z() * velocity.z()));
	}

	@Test
	void nonFiniteDeltasAreRejected()
	{
		DoorVec3 velocity = new DoorVec3(1.0D, 0.0D, 0.0D);

		assertThrows(IllegalArgumentException.class, () -> DoorVelocityTransform.rotateYaw(velocity, Float.NaN));
		assertThrows(
			IllegalArgumentException.class,
			() -> DoorVelocityTransform.rotateYaw(velocity, Float.POSITIVE_INFINITY));
	}
}
