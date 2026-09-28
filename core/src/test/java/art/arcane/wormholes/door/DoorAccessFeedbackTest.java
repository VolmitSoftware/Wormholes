package art.arcane.wormholes.door;

import org.junit.jupiter.api.Test;
import art.arcane.wormholes.util.Direction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DoorAccessFeedbackTest
{
	@Test
	void aFirstDenialIsNeverThrottled()
	{
		assertFalse(DoorAccessFeedbackPolicy.isCoolingDown(null, 1_000L));
	}

	@Test
	void repeatedDenialsWithinTheWindowAreThrottled()
	{
		long now = 1_000L;
		long nextAllowed = DoorAccessFeedbackPolicy.nextAllowedMillis(now);

		assertTrue(DoorAccessFeedbackPolicy.isCoolingDown(Long.valueOf(nextAllowed), now));
		assertTrue(DoorAccessFeedbackPolicy.isCoolingDown(Long.valueOf(nextAllowed), nextAllowed - 1L));
	}

	@Test
	void theCooldownExpiresExactlyAtItsDeadline()
	{
		long now = 1_000L;
		long nextAllowed = DoorAccessFeedbackPolicy.nextAllowedMillis(now);

		assertFalse(DoorAccessFeedbackPolicy.isCoolingDown(Long.valueOf(nextAllowed), nextAllowed));
		assertFalse(DoorAccessFeedbackPolicy.isCoolingDown(Long.valueOf(nextAllowed), nextAllowed + 1L));
	}

	@Test
	void theCooldownWindowIsMeasuredFromTheDenialInstant()
	{
		assertEquals(
			DoorAccessFeedbackPolicy.DENY_COOLDOWN_MILLIS,
			DoorAccessFeedbackPolicy.nextAllowedMillis(5_000L) - 5_000L);
		assertEquals(1500L, DoorAccessFeedbackPolicy.DENY_COOLDOWN_MILLIS);
	}

	/** The deny burst is scattered over the panel, so a flat panel must not throw. */
	@Test
	void denyParticlesScatterOverATrapdoorPanelWithoutThrowing()
	{
		for(DoorHalf half : DoorHalf.values())
		{
			DoorOpenState openState = half == DoorHalf.TOP
				? DoorOpenState.OPEN
				: DoorOpenState.CLOSED;
			DoorwayPlane plane = DoorwayPlane.trapdoor(
				2, 64, 3, Direction.N, half, openState);
			Direction panelFace = DoorPortalGeometry.panelFace(plane);
			PortalPlaneGeometry geometry =
				DoorPortalGeometry.planeGeometry(plane, DoorHinge.LEFT);

			assertEquals(Direction.U, panelFace);
			assertEquals(3, DoorPortalAnimation.scatterPoint(geometry, panelFace, 0.0D, 0.0D).length);
			assertEquals(3, DoorPortalAnimation.scatterPoint(geometry, panelFace, 0.99D, 0.99D).length);
		}
	}
}
