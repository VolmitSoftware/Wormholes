package art.arcane.wormholes.door;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DoorAccessFeedbackTest
{
	@Test
	void aFirstDenialIsNeverThrottled()
	{
		assertFalse(DoorAccessFeedback.isCoolingDown(null, 1_000L));
	}

	@Test
	void repeatedDenialsWithinTheWindowAreThrottled()
	{
		long now = 1_000L;
		long nextAllowed = DoorAccessFeedback.nextAllowedMillis(now);

		assertTrue(DoorAccessFeedback.isCoolingDown(Long.valueOf(nextAllowed), now));
		assertTrue(DoorAccessFeedback.isCoolingDown(Long.valueOf(nextAllowed), nextAllowed - 1L));
	}

	@Test
	void theCooldownExpiresExactlyAtItsDeadline()
	{
		long now = 1_000L;
		long nextAllowed = DoorAccessFeedback.nextAllowedMillis(now);

		assertFalse(DoorAccessFeedback.isCoolingDown(Long.valueOf(nextAllowed), nextAllowed));
		assertFalse(DoorAccessFeedback.isCoolingDown(Long.valueOf(nextAllowed), nextAllowed + 1L));
	}

	@Test
	void theCooldownWindowIsMeasuredFromTheDenialInstant()
	{
		assertEquals(
			DoorAccessFeedback.DENY_COOLDOWN_MILLIS,
			DoorAccessFeedback.nextAllowedMillis(5_000L) - 5_000L);
		assertEquals(1500L, DoorAccessFeedback.DENY_COOLDOWN_MILLIS);
	}

	/** The deny burst is scattered over the panel, so a flat panel must not throw. */
	@Test
	void denyParticlesScatterOverATrapdoorPanelWithoutThrowing()
	{
		for(org.bukkit.block.data.Bisected.Half half : org.bukkit.block.data.Bisected.Half.values())
		{
			DoorOpenState openState = half == org.bukkit.block.data.Bisected.Half.TOP
				? DoorOpenState.OPEN
				: DoorOpenState.CLOSED;
			DoorwayPlane plane = DoorwayPlane.trapdoor(
				2, 64, 3, org.bukkit.block.BlockFace.NORTH, half, openState);
			org.bukkit.block.BlockFace panelFace = DoorPortalVisualService.panelFace(plane);
			DoorPortalVisualService.PortalPlaneGeometry geometry =
				DoorPortalVisualService.planeGeometry(plane, org.bukkit.block.data.type.Door.Hinge.LEFT);

			assertEquals(org.bukkit.block.BlockFace.UP, panelFace);
			assertEquals(3, DoorPortalAnimation.scatterPoint(geometry, panelFace, 0.0D, 0.0D).length);
			assertEquals(3, DoorPortalAnimation.scatterPoint(geometry, panelFace, 0.99D, 0.99D).length);
		}
	}
}
