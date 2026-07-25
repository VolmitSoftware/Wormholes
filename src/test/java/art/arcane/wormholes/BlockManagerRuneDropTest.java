package art.arcane.wormholes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.service.WormholesTelemetry;

public final class BlockManagerRuneDropTest
{
	private static final Supplier<String> CONTEXT = () -> "Could not return a broken portal rune to Steve at world 1,2,3";

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
	public void aSchedulerThatDropsInlineThenReportsRejectionDropsExactlyOneRune()
	{
		AtomicInteger drops = new AtomicInteger();

		BlockManager.dropBrokenRune(task ->
		{
			task.run();
			return false;
		}, () -> true, CONTEXT, () -> drops.incrementAndGet(), () -> drops.incrementAndGet());

		assertEquals(1, drops.get());
		assertEquals(0L, WormholesTelemetry.failures());
	}

	@Test
	public void aSchedulerThatDropsInlineThenThrowsDropsExactlyOneRune()
	{
		AtomicInteger drops = new AtomicInteger();

		BlockManager.dropBrokenRune(task ->
		{
			task.run();
			throw new IllegalStateException("region scheduler cascade failed after the inline drop");
		}, () -> true, CONTEXT, () -> drops.incrementAndGet(), () -> drops.incrementAndGet());

		assertEquals(1, drops.get());
	}

	@Test
	public void aRejectionInsideTheOwningRegionDropsTheRuneLocally()
	{
		AtomicInteger scheduledDrops = new AtomicInteger();
		AtomicInteger localDrops = new AtomicInteger();

		BlockManager.dropBrokenRune(task -> false, () -> true, CONTEXT, () -> scheduledDrops.incrementAndGet(), () -> localDrops.incrementAndGet());

		assertEquals(0, scheduledDrops.get());
		assertEquals(1, localDrops.get());
		assertEquals(0L, WormholesTelemetry.failures());
	}

	@Test
	public void aRejectionOutsideTheOwningRegionCountsARuneBreakDropFailure()
	{
		AtomicInteger drops = new AtomicInteger();

		BlockManager.dropBrokenRune(task -> false, () -> false, CONTEXT, () -> drops.incrementAndGet(), () -> drops.incrementAndGet());

		assertEquals(0, drops.get());
		assertEquals(1L, WormholesTelemetry.failures());
		assertEquals(Long.valueOf(1L), WormholesTelemetry.failureBreakdown().get(BlockManager.RUNE_BREAK_DROP_SCHEDULE_REJECTED));
		assertNull(WormholesTelemetry.failureBreakdown().get(BlockManager.RUNE_BREAK_DROP_FAILED));
	}

	@Test
	public void aDropThatThrowsCountsARuneBreakDropFailureWithoutRetryingTheDrop()
	{
		AtomicInteger drops = new AtomicInteger();

		BlockManager.dropBrokenRune(task ->
		{
			task.run();
			return false;
		}, () -> true, CONTEXT, () ->
		{
			drops.incrementAndGet();
			throw new IllegalStateException("the world rejected the item drop");
		}, () -> drops.incrementAndGet());

		assertEquals(1, drops.get());
		assertEquals(1L, WormholesTelemetry.failures());
		assertEquals(Long.valueOf(1L), WormholesTelemetry.failureBreakdown().get(BlockManager.RUNE_BREAK_DROP_FAILED));
	}
}
