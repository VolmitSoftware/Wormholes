package art.arcane.wormholes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.service.WormholesTelemetry;

public final class BlockOpsGuardedDispatchTest
{
	private static final Supplier<String> CONTEXT = () -> "guarded dispatch under test";

	@BeforeEach
	public void resetTelemetry()
	{
		WormholesTelemetry.clear();
	}

	@AfterEach
	public void clearTelemetry()
	{
		WormholesTelemetry.clear();
	}

	@Test
	public void inlineRunFollowedByARejectedReturnDoesNotRunTheLocalFallback()
	{
		AtomicInteger scheduledRuns = new AtomicInteger();
		AtomicInteger localRuns = new AtomicInteger();

		boolean handled = BlockOpsGuardedDispatch.dispatchGuarded(task ->
		{
			task.run();
			return false;
		}, () -> true, "BODY", "REJECTED", CONTEXT, () -> scheduledRuns.incrementAndGet(), () -> localRuns.incrementAndGet());

		assertTrue(handled);
		assertEquals(1, scheduledRuns.get());
		assertEquals(0, localRuns.get());
		assertEquals(0L, WormholesTelemetry.failures());
	}

	@Test
	public void inlineRunFollowedByAThrowingScheduleDoesNotRunTheLocalFallback()
	{
		AtomicInteger scheduledRuns = new AtomicInteger();
		AtomicInteger localRuns = new AtomicInteger();

		boolean handled = BlockOpsGuardedDispatch.dispatchGuarded(task ->
		{
			task.run();
			throw new IllegalStateException("scheduler blew up after running the task inline");
		}, () -> true, "BODY", "REJECTED", CONTEXT, () -> scheduledRuns.incrementAndGet(), () -> localRuns.incrementAndGet());

		assertTrue(handled);
		assertEquals(1, scheduledRuns.get());
		assertEquals(0, localRuns.get());
	}

	@Test
	public void aSchedulerThatRetriesTheSameTaskOnlyRunsTheBodyOnce()
	{
		AtomicInteger scheduledRuns = new AtomicInteger();
		AtomicInteger localRuns = new AtomicInteger();

		boolean handled = BlockOpsGuardedDispatch.dispatchGuarded(task ->
		{
			task.run();
			task.run();
			task.run();
			return false;
		}, () -> true, "BODY", "REJECTED", CONTEXT, () -> scheduledRuns.incrementAndGet(), () -> localRuns.incrementAndGet());

		assertTrue(handled);
		assertEquals(1, scheduledRuns.get());
		assertEquals(0, localRuns.get());
	}

	@Test
	public void aBodyThatThrowsAfterDoingObservableWorkStillBlocksTheLocalFallback()
	{
		AtomicInteger scheduledRuns = new AtomicInteger();
		AtomicInteger localRuns = new AtomicInteger();

		boolean handled = BlockOpsGuardedDispatch.dispatchGuarded(task ->
		{
			try
			{
				task.run();
			}

			catch(Throwable ignored)
			{
				return false;
			}

			return false;
		}, () -> true, "BODY", "REJECTED", CONTEXT, () ->
		{
			scheduledRuns.incrementAndGet();
			throw new IllegalStateException("body failed after dropping the item");
		}, () -> localRuns.incrementAndGet());

		assertTrue(handled);
		assertEquals(1, scheduledRuns.get());
		assertEquals(0, localRuns.get());
		assertEquals(1L, WormholesTelemetry.failures());
		assertEquals(Long.valueOf(1L), WormholesTelemetry.failureBreakdown().get("BODY"));
	}

	@Test
	public void aRejectionThatNeverStartedTheBodyRunsTheLocalFallbackOnce()
	{
		AtomicInteger scheduledRuns = new AtomicInteger();
		AtomicInteger localRuns = new AtomicInteger();

		boolean handled = BlockOpsGuardedDispatch.dispatchGuarded(task -> false, () -> true, "BODY", "REJECTED", CONTEXT,
				() -> scheduledRuns.incrementAndGet(), () -> localRuns.incrementAndGet());

		assertTrue(handled);
		assertEquals(0, scheduledRuns.get());
		assertEquals(1, localRuns.get());
		assertEquals(0L, WormholesTelemetry.failures());
	}

	@Test
	public void aRejectionOutsideTheOwningRegionCountsTheRejectionReason()
	{
		AtomicInteger scheduledRuns = new AtomicInteger();
		AtomicInteger localRuns = new AtomicInteger();

		boolean handled = BlockOpsGuardedDispatch.dispatchGuarded(task -> false, () -> false, "BODY", "REJECTED", CONTEXT,
				() -> scheduledRuns.incrementAndGet(), () -> localRuns.incrementAndGet());

		assertFalse(handled);
		assertEquals(0, scheduledRuns.get());
		assertEquals(0, localRuns.get());
		assertEquals(1L, WormholesTelemetry.failures());
		assertEquals(Long.valueOf(1L), WormholesTelemetry.failureBreakdown().get("REJECTED"));
	}

	@Test
	public void theFallbackFreeOverloadNeverRunsTheBodyLocally()
	{
		AtomicInteger runs = new AtomicInteger();

		boolean handled = BlockOpsGuardedDispatch.dispatchGuarded(task -> false, "BODY", "REJECTED", CONTEXT, () -> runs.incrementAndGet());

		assertFalse(handled);
		assertEquals(0, runs.get());
		assertEquals(Long.valueOf(1L), WormholesTelemetry.failureBreakdown().get("REJECTED"));
	}

	@Test
	public void aSuccessfulScheduleIsNotCountedAsAFailure()
	{
		AtomicInteger runs = new AtomicInteger();

		boolean handled = BlockOpsGuardedDispatch.dispatchGuarded(task ->
		{
			task.run();
			return true;
		}, () -> true, "BODY", "REJECTED", CONTEXT, () -> runs.incrementAndGet());

		assertTrue(handled);
		assertEquals(1, runs.get());
		assertEquals(0L, WormholesTelemetry.failures());
	}

	@Test
	public void aThrowingOwnershipProbeCountsTheBodyReasonAndReportsRejection()
	{
		AtomicInteger runs = new AtomicInteger();

		boolean handled = BlockOpsGuardedDispatch.dispatchGuarded(task -> false, () ->
		{
			throw new IllegalStateException("ownership probe failed");
		}, "BODY", "REJECTED", CONTEXT, () -> runs.incrementAndGet());

		assertFalse(handled);
		assertEquals(0, runs.get());
		assertEquals(2L, WormholesTelemetry.failures());
		assertEquals(Long.valueOf(1L), WormholesTelemetry.failureBreakdown().get("BODY"));
		assertEquals(Long.valueOf(1L), WormholesTelemetry.failureBreakdown().get("REJECTED"));
	}
}
