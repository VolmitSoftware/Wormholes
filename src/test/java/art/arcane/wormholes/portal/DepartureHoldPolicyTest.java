package art.arcane.wormholes.portal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public final class DepartureHoldPolicyTest
{
	@Test
	public void clearedFlagStopsBeforeAnyOtherCheck()
	{
		assertEquals(DepartureHoldPolicy.Decision.STOP,
				DepartureHoldPolicy.decide(false, true, 5.0D, 100.0D, 0L));
		assertEquals(DepartureHoldPolicy.Decision.STOP,
				DepartureHoldPolicy.decide(false, false, -0.5D, 0.1D,
						DepartureHoldPolicy.TIMEOUT_MILLIS + 1_000L));
	}

	@Test
	public void worldChangeStopsTheHold()
	{
		assertEquals(DepartureHoldPolicy.Decision.STOP,
				DepartureHoldPolicy.decide(true, false, 5.0D, Double.MAX_VALUE, 100L));
	}

	@Test
	public void largeDisplacementStopsInsteadOfCancelling()
	{
		assertEquals(DepartureHoldPolicy.Decision.STOP,
				DepartureHoldPolicy.decide(true, true, 5.0D,
						DepartureHoldPolicy.FAR_DRIFT_SQUARED + 1.0D, 100L));
	}

	@Test
	public void exhaustedHoldStopsAtTimeout()
	{
		assertEquals(DepartureHoldPolicy.Decision.STOP,
				DepartureHoldPolicy.decide(true, true, -0.5D, 0.2D,
						DepartureHoldPolicy.TIMEOUT_MILLIS));
	}

	@Test
	public void deliberateRetreatBeyondTheCancelMarginReleasesTheHandoff()
	{
		assertEquals(DepartureHoldPolicy.Decision.CANCEL_RETREAT,
				DepartureHoldPolicy.decide(true, true, 1.0D,
						DepartureHoldPolicy.RETREAT_CANCEL_DRIFT_SQUARED + 0.5D, 100L));
	}

	@Test
	public void gentleForwardDriftIsPinnedNotCancelled()
	{
		assertEquals(DepartureHoldPolicy.Decision.HOLD_PIN,
				DepartureHoldPolicy.decide(true, true, 1.0D, 1.0D, 100L));
	}

	@Test
	public void committedTravelerIsPinned()
	{
		assertEquals(DepartureHoldPolicy.Decision.HOLD_PIN,
				DepartureHoldPolicy.decide(true, true, -0.5D, 0.4D, 100L));
		assertEquals(DepartureHoldPolicy.Decision.HOLD_PIN,
				DepartureHoldPolicy.decide(true, true,
						DepartureHoldPolicy.RETREAT_FREE_DISTANCE, 0.4D, 100L));
	}
}
