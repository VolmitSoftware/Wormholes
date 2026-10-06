package art.arcane.wormholes.door;

import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.crossing.PlaneCrossing;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DoorTransitTravelerClassTest
{
	private static final DoorwayPlane PLANE = new DoorwayPlane(0, 64, 0, Face.N);

	@Test
	void theShorthandConstructorsProduceALivingTransitWithoutMomentum()
	{
		DoorTransit defaults = new DoorTransit(PLANE, true, 0.0F, 0.0F);
		DoorTransit sized = new DoorTransit(
			PLANE, true, 0.0F, 0.0F, 0.25D, 0.5D);

		assertEquals(DoorTravelerClass.LIVING, defaults.travelerClass());
		assertEquals(DoorTravelerClass.LIVING, sized.travelerClass());
		assertNull(defaults.velocity());
		assertNull(sized.velocity());
		assertFalse(defaults.carriesMomentum());
		assertTrue(defaults.crossing().frontSide());
		assertEquals(PLANE.center(), defaults.crossing().point());
		assertEquals(1.0D, PLANE.secondaryOffset(defaults.crossing().point()));
	}

	@Test
	void anObjectTransitCarriesItsMomentum()
	{
		PlaneCrossing crossing = PLANE.crossingAt(PLANE.center(), new Vec3d(0.0D, 0.0D, -3.0D), false);
		DoorTransit transit = new DoorTransit(
			PLANE,
			crossing,
			12.0F,
			-3.0F,
			0.25D,
			0.5D,
			DoorTravelerClass.OBJECT,
			new Vec3d(0.0D, 0.0D, -3.0D));

		assertTrue(transit.carriesMomentum());
		assertEquals(-3.0D, transit.velocity().z());
		assertSame(crossing, transit.crossing());
		assertEquals(crossing.frontSide(), transit.crossing().frontSide());
	}

	@Test
	void anObjectTransitMayStillArriveWithoutAKnownVelocity()
	{
		DoorTransit transit = new DoorTransit(
			PLANE,
			false,
			0.0F,
			0.0F,
			0.25D,
			0.5D,
			DoorTravelerClass.OBJECT,
			null);

		assertTrue(transit.carriesMomentum());
		assertNull(transit.velocity());
	}

	@Test
	void aPreparedLivingTransitCarriesValidatedMomentum()
	{
		Vec3d velocity = new Vec3d(1.0D, -0.2D, 0.5D);
		DoorTransit transit = new DoorTransit(PLANE, true,
			0.0F, 0.0F, 0.3D, 1.8D, DoorTravelerClass.LIVING, velocity);
		assertTrue(transit.carriesMomentum());
		assertSame(velocity, transit.velocity());
		Vec3d mapped = DoorPlanePairing.mapVector(PLANE, new DoorwayPlane(0, 64, 0, Face.E), velocity);
		assertEquals(velocity.y(), mapped.y());
		assertEquals(velocity.x() * velocity.x() + velocity.z() * velocity.z(),
			mapped.x() * mapped.x() + mapped.z() * mapped.z(), 0.000001D);
	}

	@Test
	void aTransitAlwaysNeedsATravelerClass()
	{
		assertThrows(NullPointerException.class, () -> new DoorTransit(
			PLANE, true, 0.0F, 0.0F, 0.3D, 1.8D, null, null));
	}

	@Test
	void aTransitAlwaysNeedsACrossing()
	{
		assertThrows(NullPointerException.class, () -> new DoorTransit(
			PLANE,
			(PlaneCrossing) null,
			0.0F,
			0.0F,
			0.3D,
			1.8D,
			DoorTravelerClass.LIVING,
			null));
	}
}
