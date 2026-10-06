package art.arcane.wormholes.door;

import art.arcane.optics.math.Face;
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
		DoorTransit defaults = new DoorTransit(PLANE, DoorwayCrossing.Direction.FRONT_TO_BACK, 0.0F, 0.0F);
		DoorTransit sized = new DoorTransit(
			PLANE, DoorwayCrossing.Direction.FRONT_TO_BACK, 0.0F, 0.0F, 0.25D, 0.5D);

		assertEquals(DoorTravelerClass.LIVING, defaults.travelerClass());
		assertEquals(DoorTravelerClass.LIVING, sized.travelerClass());
		assertNull(defaults.velocity());
		assertNull(sized.velocity());
		assertFalse(defaults.carriesMomentum());
		assertEquals(DoorwayCrossing.Direction.FRONT_TO_BACK, defaults.direction());
		assertEquals(PLANE.center(), defaults.crossing().point());
		assertEquals(1.0D, defaults.crossing().verticalOffset());
	}

	@Test
	void anObjectTransitCarriesItsMomentum()
	{
		DoorwayCrossing crossing = new DoorwayCrossing(
			PLANE.center(),
			0.5D,
			0.2D,
			1.75D,
			DoorwayCrossing.Direction.BACK_TO_FRONT);
		DoorTransit transit = new DoorTransit(
			PLANE,
			crossing,
			12.0F,
			-3.0F,
			0.25D,
			0.5D,
			DoorTravelerClass.OBJECT,
			new DoorVec3(0.0D, 0.0D, -3.0D));

		assertTrue(transit.carriesMomentum());
		assertEquals(-3.0D, transit.velocity().z());
		assertSame(crossing, transit.crossing());
		assertEquals(crossing.direction(), transit.direction());
	}

	@Test
	void anObjectTransitMayStillArriveWithoutAKnownVelocity()
	{
		DoorTransit transit = new DoorTransit(
			PLANE,
			DoorwayCrossing.Direction.BACK_TO_FRONT,
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
		DoorVec3 velocity = new DoorVec3(1.0D, -0.2D, 0.5D);
		DoorTransit transit = new DoorTransit(PLANE, DoorwayCrossing.Direction.FRONT_TO_BACK,
			0.0F, 0.0F, 0.3D, 1.8D, DoorTravelerClass.LIVING, velocity);
		assertTrue(transit.carriesMomentum());
		assertSame(velocity, transit.velocity());
		DoorVec3 mapped = DoorVelocityTransform.map(PLANE, new DoorwayPlane(0, 64, 0, Face.E), velocity);
		assertEquals(velocity.y(), mapped.y());
		assertEquals(velocity.x() * velocity.x() + velocity.z() * velocity.z(),
			mapped.x() * mapped.x() + mapped.z() * mapped.z(), 0.000001D);
	}

	@Test
	void aTransitAlwaysNeedsATravelerClass()
	{
		assertThrows(NullPointerException.class, () -> new DoorTransit(
			PLANE, DoorwayCrossing.Direction.FRONT_TO_BACK, 0.0F, 0.0F, 0.3D, 1.8D, null, null));
	}

	@Test
	void aTransitAlwaysNeedsACrossing()
	{
		assertThrows(NullPointerException.class, () -> new DoorTransit(
			PLANE,
			(DoorwayCrossing) null,
			0.0F,
			0.0F,
			0.3D,
			1.8D,
			DoorTravelerClass.LIVING,
			null));
	}
}
