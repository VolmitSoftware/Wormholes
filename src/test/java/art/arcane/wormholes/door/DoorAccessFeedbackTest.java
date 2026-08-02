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
}
