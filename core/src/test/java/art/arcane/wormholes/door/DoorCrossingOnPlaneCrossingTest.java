package art.arcane.wormholes.door;

import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.crossing.PlaneCrossing;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.math.Angles;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class DoorCrossingOnPlaneCrossingTest
{
	private static final Face[] CARDINALS =
		{Face.N, Face.S, Face.E, Face.W};
	private static final double TOLERANCE = 1.0E-9D;

	/** Minecraft heading for a yaw: 0 looks toward +Z, 90 toward -X. */
	private static Vec3d heading(float yaw, double speed, double vertical)
	{
		double radians = Math.toRadians(yaw);
		return new Vec3d(-Math.sin(radians) * speed, vertical, Math.cos(radians) * speed);
	}

	@Test
	void rotatedMomentumMatchesTheArrivalYawForEveryCardinalPairing()
	{
		for(Face sourceFacing : CARDINALS)
		{
			DoorwayPlane source = new DoorwayPlane(0, 64, 0, sourceFacing);
			for(Face destinationFacing : CARDINALS)
			{
				DoorwayPlane destination = new DoorwayPlane(40, 64, 40, destinationFacing);
				for(float yaw : new float[] {0.0F, 45.0F, 90.0F, 179.0F, -90.0F, -135.0F})
				{
					float arrivalYaw = source.rotateYawToMatchingSide(destination, yaw);
					Vec3d rotated = Angles.rotateYaw(
						heading(yaw, 2.5D, 0.4D), arrivalYaw - yaw);
					Vec3d expected = heading(arrivalYaw, 2.5D, 0.4D);
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
		Vec3d velocity = new Vec3d(1.5D, -0.75D, -0.5D);
		Vec3d rotated = Angles.rotateYaw(velocity, 137.0F);

		assertEquals(-0.75D, rotated.y(), TOLERANCE);
		assertEquals(
			Math.hypot(velocity.x(), velocity.z()),
			Math.hypot(rotated.x(), rotated.z()),
			TOLERANCE);
	}

	@Test
	void aZeroDeltaLeavesMomentumUntouched()
	{
		Vec3d velocity = new Vec3d(0.25D, 1.0D, -3.0D);
		Vec3d rotated = Angles.rotateYaw(velocity, 0.0F);

		assertEquals(velocity.x(), rotated.x(), TOLERANCE);
		assertEquals(velocity.y(), rotated.y(), TOLERANCE);
		assertEquals(velocity.z(), rotated.z(), TOLERANCE);
	}

	@Test
	void aQuarterTurnSwingsSouthwardMomentumWestward()
	{
		Vec3d rotated = Angles.rotateYaw(new Vec3d(0.0D, 0.0D, 1.0D), 90.0F);

		assertEquals(-1.0D, rotated.x(), TOLERANCE);
		assertEquals(0.0D, rotated.z(), TOLERANCE);
	}

	@Test
	void absentMomentumStaysAbsent()
	{
		assertNull(DoorPlanePairing.mapVector(
			new DoorwayPlane(0, 64, 0, Face.N),
			new DoorwayPlane(9, 64, 9, Face.E),
			null));
	}

	@Test
	void everyPairingPreservesSpeedExactly()
	{
		Vec3d velocity = new Vec3d(0.8D, -1.4D, 0.3D);
		for(DoorwayPlane source : everyPlane(0, 64, 0))
		{
			for(DoorwayPlane destination : everyPlane(48, 64, -32))
			{
				Vec3d mapped = DoorPlanePairing.mapVector(source, destination, velocity);

				assertEquals(length(velocity), length(mapped), TOLERANCE,
					label(source) + "->" + label(destination));
			}
		}
	}

	@Test
	void twoHingedDoorsKeepTheEstablishedFrontToFrontRule()
	{
		for(Face sourceFacing : CARDINALS)
		{
			DoorwayPlane source = new DoorwayPlane(0, 64, 0, sourceFacing);
			for(Face destinationFacing : CARDINALS)
			{
				DoorwayPlane destination = new DoorwayPlane(30, 64, 30, destinationFacing);
				Vec3d velocity = new Vec3d(
					sourceFacing.x() * 2.0D, 0.6D, sourceFacing.z() * 2.0D);
				Vec3d mapped = DoorPlanePairing.mapVector(source, destination, velocity);

				// entering along the source normal leaves against the destination normal
				assertEquals(-2.0D, normalComponent(destination, mapped), TOLERANCE);
				assertEquals(0.6D, mapped.y(), TOLERANCE, "gravity is the same on both sides");
			}
		}
	}

	@Test
	void aFallThroughATrapdoorKeepsFalling()
	{
		Vec3d falling = new Vec3d(0.0D, -1.6D, 0.0D);
		for(Face sourceFacing : CARDINALS)
		{
			DoorwayPlane source = trapdoor(0, 64, 0, sourceFacing);
			for(Face destinationFacing : CARDINALS)
			{
				DoorwayPlane destination = trapdoor(20, 30, 20, destinationFacing);
				Vec3d mapped = DoorPlanePairing.mapVector(source, destination, falling);

				assertEquals(-1.6D, mapped.y(), TOLERANCE);
			}
		}
	}

	@Test
	void aShotFiredUpThroughATrapdoorKeepsClimbing()
	{
		Vec3d rising = new Vec3d(0.0D, 2.4D, 0.0D);
		DoorwayPlane source = trapdoor(0, 64, 0, Face.N);
		DoorwayPlane destination = trapdoor(80, 12, -40, Face.W);

		assertEquals(2.4D, DoorPlanePairing.mapVector(source, destination, rising).y(), TOLERANCE);
	}

	@Test
	void aDoorwayHandsAHorizontalShotToATrapdoorAsAVerticalOne()
	{
		DoorwayPlane source = new DoorwayPlane(0, 64, 0, Face.N);
		DoorwayPlane destination = trapdoor(30, 64, 30, Face.S);
		// travelling along the north-facing door's own normal, so straight out of it
		Vec3d velocity = new Vec3d(0.0D, 0.0D, -3.0D);

		Vec3d mapped = DoorPlanePairing.mapVector(source, destination, velocity);

		assertEquals(3.0D, mapped.y(), TOLERANCE, "out of the doorway becomes up through the plate");
		assertEquals(0.0D, mapped.x(), TOLERANCE);
		assertEquals(0.0D, mapped.z(), TOLERANCE);
	}

	@Test
	void aTrapdoorHandsAFallToADoorwayAsAHorizontalShot()
	{
		DoorwayPlane source = trapdoor(0, 64, 0, Face.E);
		DoorwayPlane destination = new DoorwayPlane(-20, 64, 5, Face.W);
		Vec3d velocity = new Vec3d(0.0D, -2.0D, 0.0D);

		Vec3d mapped = DoorPlanePairing.mapVector(source, destination, velocity);

		assertEquals(0.0D, mapped.y(), TOLERANCE);
		assertEquals(-2.0D, normalComponent(destination, mapped), TOLERANCE,
			"falling in leaves straight out the far side");
	}

	@Test
	void doorwayCrossingsArePlaneCrossingsOnTheDoorwayFrame()
	{
		for(DoorwayPlane plane : everyPlane(3, 64, -7))
		{
			Vec3d center = plane.center();
			Vec3d normal = new Vec3d(plane.normalX(), plane.normalY(), plane.normalZ());
			for(boolean frontSide : new boolean[] {true, false})
			{
				Vec3d from = center.add(normal.multiply(frontSide ? 0.5D : -0.5D));
				Vec3d to = center.add(normal.multiply(frontSide ? -0.5D : 0.5D));
				PlaneCrossing crossing = plane.crossing(from, to).orElseThrow();

				assertEquals(frontSide, crossing.frontSide(), label(plane));
				assertEquals(plane.frame().view(frontSide), crossing.frame(), label(plane));
				assertEquals(center, crossing.origin(), label(plane));
				assertEquals(normal, new Vec3d(plane.frame().getNormal().x(), plane.frame().getNormal().y(), plane.frame().getNormal().z()));
				assertEquals(0.0D, plane.signedDistance(crossing.point()), TOLERANCE);
			}
		}
	}

	@Test
	void momentumFollowsTheDecompositionInEachPlanesOwnFrame()
	{
		Vec3d velocity = new Vec3d(0.8D, -1.4D, 0.3D);
		for(DoorwayPlane source : everyPlane(0, 64, 0))
		{
			for(DoorwayPlane destination : everyPlane(48, 64, -32))
			{
				int sign = DoorPlanePairing.mirrored(source, destination) ? -1 : 1;
				Vec3d expected = decomposed(source, destination, velocity, sign);
				Vec3d mapped = DoorPlanePairing.mapVector(source, destination, velocity);
				String label = label(source) + "->" + label(destination);

				assertEquals(expected.x(), mapped.x(), TOLERANCE, label);
				assertEquals(expected.y(), mapped.y(), TOLERANCE, label);
				assertEquals(expected.z(), mapped.z(), TOLERANCE, label);
				for(int side : new int[] {-1, 1})
				{
					for(boolean frontSide : new boolean[] {true, false})
					{
						DoorTransit transit = new DoorTransit(source, frontSide, 0.0F, 0.0F);
						Vec3d sided = DoorPlanePairing.mapVectorToSide(destination, transit, velocity, side);
						Vec3d reference = decomposed(source, destination, velocity, side * transit.exitSideSign());
						assertEquals(reference.x(), sided.x(), TOLERANCE, label);
						assertEquals(reference.y(), sided.y(), TOLERANCE, label);
						assertEquals(reference.z(), sided.z(), TOLERANCE, label);
					}
				}
			}
		}
	}

	@Test
	void sameFormAperturePointsFollowTheRigidCrossingTransform()
	{
		for(DoorwayPlane source : everyPlane(0, 64, 0))
		{
			for(DoorwayPlane destination : everyPlane(48, 20, -32))
			{
				if(source.horizontal() != destination.horizontal())
				{
					continue;
				}
				Vec3d point = source.center().add(new Vec3d(-source.facing().z() * 0.3D, source.horizontal() ? 0.0D : 0.4D,
					source.facing().x() * 0.3D));
				PlaneCrossing crossing = source.crossingAt(point, new Vec3d(0.0D, 0.0D, 0.0D), true);
				Frame from = DoorPlanePairing.mirrored(source, destination) ? source.frame().flipNormal() : source.frame();
				Vec3d expected = OpticTransform.between(from, source.center(), destination.frame(), destination.center()).point(point);
				Vec3d mapped = DoorPlanePairing.mapAperturePoint(source, destination, crossing);
				String label = label(source) + "->" + label(destination);

				assertEquals(expected.x(), mapped.x(), TOLERANCE, label);
				assertEquals(expected.y(), mapped.y(), TOLERANCE, label);
				assertEquals(expected.z(), mapped.z(), TOLERANCE, label);
			}
		}
	}

	@Test
	void nonFiniteDoorCoordinatesAreRejected()
	{
		DoorwayPlane plane = new DoorwayPlane(0, 64, 0, Face.N);
		Vec3d center = plane.center();

		assertThrows(IllegalArgumentException.class, () -> plane.crossing(new Vec3d(Double.NaN, 64.0D, 0.0D), center));
		assertThrows(IllegalArgumentException.class, () -> plane.contact(center, new Vec3d(0.0D, Double.POSITIVE_INFINITY, 0.0D)));
	}

	@Test
	void mappingRequiresBothPlanes()
	{
		DoorwayPlane plane = new DoorwayPlane(0, 64, 0, Face.N);
		Vec3d velocity = new Vec3d(1.0D, 0.0D, 0.0D);

		assertThrows(NullPointerException.class, () -> DoorPlanePairing.mapVector(null, plane, velocity));
		assertThrows(NullPointerException.class, () -> DoorPlanePairing.mapVector(plane, null, velocity));
	}

	private static DoorwayPlane trapdoor(int x, int y, int z, Face facing)
	{
		return DoorwayPlane.trapdoor(
			x, y, z, facing, DoorHalf.BOTTOM, DoorOpenState.OPEN);
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

	private static Vec3d decomposed(DoorwayPlane source, DoorwayPlane destination, Vec3d velocity, int sign)
	{
		double normal = dot(velocity, source.normalX(), source.normalY(), source.normalZ()) * sign;
		double lateral = dot(velocity, -source.facing().z(), 0.0D, source.facing().x()) * sign;
		double third = dot(velocity, thirdX(source), thirdY(source), thirdZ(source));
		return new Vec3d(
			(normal * destination.normalX()) + (lateral * -destination.facing().z()) + (third * thirdX(destination)),
			(normal * destination.normalY()) + (third * thirdY(destination)),
			(normal * destination.normalZ()) + (lateral * destination.facing().x()) + (third * thirdZ(destination)));
	}

	private static double thirdX(DoorwayPlane plane)
	{
		return plane.horizontal() ? -plane.facing().x() : 0.0D;
	}

	private static double thirdY(DoorwayPlane plane)
	{
		return plane.horizontal() ? 0.0D : 1.0D;
	}

	private static double thirdZ(DoorwayPlane plane)
	{
		return plane.horizontal() ? -plane.facing().z() : 0.0D;
	}

	private static double dot(Vec3d velocity, double x, double y, double z)
	{
		return (velocity.x() * x) + (velocity.y() * y) + (velocity.z() * z);
	}

	private static double normalComponent(DoorwayPlane plane, Vec3d velocity)
	{
		return (velocity.x() * plane.normalX())
			+ (velocity.y() * plane.normalY())
			+ (velocity.z() * plane.normalZ());
	}

	private static double length(Vec3d velocity)
	{
		return Math.sqrt((velocity.x() * velocity.x())
			+ (velocity.y() * velocity.y())
			+ (velocity.z() * velocity.z()));
	}

	@Test
	void nonFiniteDeltasAreRejected()
	{
		Vec3d velocity = new Vec3d(1.0D, 0.0D, 0.0D);

		assertThrows(IllegalArgumentException.class, () -> Angles.rotateYaw(velocity, Float.NaN));
		assertThrows(
			IllegalArgumentException.class,
			() -> Angles.rotateYaw(velocity, Float.POSITIVE_INFINITY));
	}
}
