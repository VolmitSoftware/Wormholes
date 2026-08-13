package art.arcane.wormholes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.service.WormholesTelemetry;

public final class BlockOpsRuneConstructionDispatchTest
{
	private static final Supplier<String> CONTEXT = () -> "Could not refund a portal rune at world 4,5,6";

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
	public void aRefundThatDropsInlineThenReportsRejectionDropsExactlyOneRune()
	{
		AtomicInteger drops = new AtomicInteger();

		BlockOpsRuneConstruction.refundRune(task ->
		{
			task.run();
			return false;
		}, () -> true, CONTEXT, () -> drops.incrementAndGet());

		assertEquals(1, drops.get());
		assertEquals(0L, WormholesTelemetry.failures());
	}

	@Test
	public void aRefundThatDropsInlineThenThrowsDropsExactlyOneRune()
	{
		AtomicInteger drops = new AtomicInteger();

		BlockOpsRuneConstruction.refundRune(task ->
		{
			task.run();
			throw new IllegalStateException("region scheduler cascade failed after the inline refund");
		}, () -> true, CONTEXT, () -> drops.incrementAndGet());

		assertEquals(1, drops.get());
	}

	@Test
	public void aRefundRejectionOutsideTheOwningRegionCountsARefundDropFailure()
	{
		AtomicInteger drops = new AtomicInteger();

		BlockOpsRuneConstruction.refundRune(task -> false, () -> false, CONTEXT, () -> drops.incrementAndGet());

		assertEquals(0, drops.get());
		assertEquals(Long.valueOf(1L), WormholesTelemetry.failureBreakdown().get(BlockOpsRuneConstruction.RUNE_REFUND_DROP_SCHEDULE_REJECTED));
	}

	@Test
	public void aRefundTickThatRunsInlineThenReportsRejectionIsNotDrainedAgain()
	{
		AtomicInteger ticks = new AtomicInteger();

		boolean scheduled = BlockOpsRuneConstruction.scheduleRefundTick(task ->
		{
			task.run();
			return false;
		}, CONTEXT, () -> ticks.incrementAndGet());

		assertTrue(scheduled);
		assertEquals(1, ticks.get());
		assertEquals(0L, WormholesTelemetry.failures());
	}

	@Test
	public void aRefundTickThatNeverRanReportsRejectionAndCountsIt()
	{
		AtomicInteger ticks = new AtomicInteger();

		boolean scheduled = BlockOpsRuneConstruction.scheduleRefundTick(task -> false, CONTEXT, () -> ticks.incrementAndGet());

		assertFalse(scheduled);
		assertEquals(0, ticks.get());
		assertEquals(Long.valueOf(1L), WormholesTelemetry.failureBreakdown().get(BlockOpsRuneConstruction.RUNE_REFUND_TICK_SCHEDULE_REJECTED));
	}

	@Test
	public void aRollbackThatRunsInlineThenReportsRejectionIsNotRunTwice()
	{
		AtomicInteger rollbacks = new AtomicInteger();

		boolean restored = BlockOpsRuneConstruction.restoreRunesInChunk(task ->
		{
			task.run();
			return false;
		}, () -> true, CONTEXT, () -> rollbacks.incrementAndGet());

		assertTrue(restored);
		assertEquals(1, rollbacks.get());
		assertEquals(0L, WormholesTelemetry.failures());
	}

	@Test
	public void aRollbackRejectionInsideTheOwningRegionRunsTheRollbackOnce()
	{
		AtomicInteger rollbacks = new AtomicInteger();

		boolean restored = BlockOpsRuneConstruction.restoreRunesInChunk(task -> false, () -> true, CONTEXT, () -> rollbacks.incrementAndGet());

		assertTrue(restored);
		assertEquals(1, rollbacks.get());
		assertNull(WormholesTelemetry.failureBreakdown().get(BlockOpsRuneConstruction.RUNE_ROLLBACK_SCHEDULE_REJECTED));
	}

	@Test
	public void aRollbackRejectionOutsideTheOwningRegionCountsARollbackFailure()
	{
		AtomicInteger rollbacks = new AtomicInteger();

		boolean restored = BlockOpsRuneConstruction.restoreRunesInChunk(task -> false, () -> false, CONTEXT, () -> rollbacks.incrementAndGet());

		assertFalse(restored);
		assertEquals(0, rollbacks.get());
		assertEquals(Long.valueOf(1L), WormholesTelemetry.failureBreakdown().get(BlockOpsRuneConstruction.RUNE_ROLLBACK_SCHEDULE_REJECTED));
	}

	@Test
	public void reservationsStayUntilConstructOpens()
	{
		AtomicInteger cleared = new AtomicInteger();
		AtomicInteger rolledBack = new AtomicInteger();

		BlockOpsRuneConstruction.settleRuneConstruct(false, () -> cleared.incrementAndGet(), () -> rolledBack.incrementAndGet());

		assertEquals(0, cleared.get());
		assertEquals(1, rolledBack.get());
	}

	@Test
	public void reservationsClearOnlyAfterConstructOpens()
	{
		AtomicInteger cleared = new AtomicInteger();
		AtomicInteger rolledBack = new AtomicInteger();

		BlockOpsRuneConstruction.settleRuneConstruct(true, () -> cleared.incrementAndGet(), () -> rolledBack.incrementAndGet());

		assertEquals(1, cleared.get());
		assertEquals(0, rolledBack.get());
	}
}
