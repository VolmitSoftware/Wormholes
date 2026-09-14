package art.arcane.wormholes;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

final class ProjectionFrameBudgetTest {
    private final AtomicLong now = new AtomicLong();
    private final ProjectionBudgetLedger ledger = new ProjectionBudgetLedger(now::get);

    @Test
    void twoTwentyFourMillisecondObserversDoNotShareAThirtyMillisecondFrame() {
        UUID first = seed(24_000_000L);
        UUID second = seed(24_000_000L);
        ProjectionBudgetLedger.FrameBudget frame = ledger.beginFrame(30_000);

        render(frame, first, 24_000_000L);

        try (ProjectionBudgetLedger.ObserverFrame observer = frame.beginObserver(second)) {
            assertFalse(observer.admitsBlocks());
        }
    }

    @Test
    void predictedWorkFitsExactlyAtTheBoundary() {
        UUID first = seed(24_000_000L);
        UUID exact = seed(6_000_000L);
        UUID over = seed(6_000_001L);
        ProjectionBudgetLedger.FrameBudget frame = ledger.beginFrame(30_000);
        render(frame, first, 24_000_000L);

        try (ProjectionBudgetLedger.ObserverFrame observer = frame.beginObserver(over)) {
            assertFalse(observer.admitsBlocks());
        }
        render(frame, exact, 6_000_000L);
        try (ProjectionBudgetLedger.ObserverFrame observer = frame.beginObserver(exact)) {
            assertFalse(observer.admitsBlocks());
        }
    }

    @Test
    void cheapObserversShareTheFrameAndUnknownObserversWaitTheirTurn() {
        UUID cheap = seed(3_000_000L);
        ProjectionBudgetLedger.FrameBudget frame = ledger.beginFrame(30_000);
        for (int index = 0; index < 10; index++) {
            render(frame, cheap, 3_000_000L);
        }
        try (ProjectionBudgetLedger.ObserverFrame observer = frame.beginObserver(UUID.randomUUID())) {
            assertFalse(observer.admitsBlocks());
        }
        render(ledger.beginFrame(30_000), UUID.randomUUID(), 40_000_000L);
    }

    @Test
    void firstOversizedObserverStillMakesProgress() {
        UUID expensive = seed(50_000_000L);
        UUID cheap = seed(1_000_000L);
        ProjectionBudgetLedger.FrameBudget frame = ledger.beginFrame(30_000);

        render(frame, expensive, 50_000_000L);

        try (ProjectionBudgetLedger.ObserverFrame observer = frame.beginObserver(cheap)) {
            assertFalse(observer.admitsBlocks());
        }
    }

    @Test
    void executionThreadsHaveIndependentBudgets() throws InterruptedException {
        ProjectionBudgetLedger.FrameBudget frame = ledger.beginFrame(30_000);
        render(frame, UUID.randomUUID(), 40_000_000L);
        AtomicBoolean admitted = new AtomicBoolean();
        Thread thread = Thread.ofPlatform().start(() -> {
            try (ProjectionBudgetLedger.ObserverFrame observer = frame.beginObserver(UUID.randomUUID())) {
                admitted.set(observer.admitsBlocks());
                observer.recordBlockWork();
            }
        });
        thread.join();

        assertTrue(admitted.get());
        try (ProjectionBudgetLedger.ObserverFrame observer = frame.beginObserver(UUID.randomUUID())) {
            assertFalse(observer.admitsBlocks());
        }
    }

    @Test
    void interleavedManagerFramesNeverResetEachOthersUsage() {
        UUID cheap = seed(6_000_000L);
        ProjectionBudgetLedger.FrameBudget first = ledger.beginFrame(30_000);
        render(first, UUID.randomUUID(), 25_000_000L);
        ProjectionBudgetLedger.FrameBudget second = ledger.beginFrame(30_000);
        render(second, UUID.randomUUID(), 20_000_000L);

        try (ProjectionBudgetLedger.ObserverFrame observer = first.beginObserver(cheap)) {
            assertFalse(observer.admitsBlocks());
        }
        render(second, cheap, 6_000_000L);
    }

    @Test
    void emptyAndEntityOnlyFramesDoNotConsumeFirstBlockAdmission() {
        ProjectionBudgetLedger.FrameBudget frame = ledger.beginFrame(30_000);
        try (ProjectionBudgetLedger.ObserverFrame observer = frame.beginObserver(UUID.randomUUID())) {
            now.addAndGet(40_000_000L);
        }
        render(frame, UUID.randomUUID(), 20_000_000L);
        try (ProjectionBudgetLedger.ObserverFrame observer = frame.beginObserver(UUID.randomUUID())) {
            assertFalse(observer.admitsBlocks());
        }
    }

    @Test
    void mandatoryWorkChargesTheFrameWithoutReplacingObservedBlockCost() {
        UUID expensive = seed(24_000_000L);
        UUID cheap = seed(6_000_000L);
        ProjectionBudgetLedger.FrameBudget frame = ledger.beginFrame(30_000);
        render(frame, expensive, 24_000_000L);
        try (ProjectionBudgetLedger.ObserverFrame observer = frame.beginObserver(expensive)) {
            assertFalse(observer.admitsBlocks());
            now.addAndGet(1_000_000L);
        }
        try (ProjectionBudgetLedger.ObserverFrame observer = frame.beginObserver(cheap)) {
            assertFalse(observer.admitsBlocks());
        }
        ProjectionBudgetLedger.FrameBudget next = ledger.beginFrame(30_000);
        render(next, UUID.randomUUID(), 7_000_000L);
        try (ProjectionBudgetLedger.ObserverFrame observer = next.beginObserver(expensive)) {
            assertFalse(observer.admitsBlocks());
        }
    }

    @Test
    void cheapReuseDecaysThePeakGradually() {
        UUID expensive = seed(24_000_000L);
        render(ledger.beginFrame(30_000), expensive, 1_000_000L);
        ProjectionBudgetLedger.FrameBudget frame = ledger.beginFrame(30_000);
        render(frame, UUID.randomUUID(), 24_000_000L);
        try (ProjectionBudgetLedger.ObserverFrame observer = frame.beginObserver(expensive)) {
            assertFalse(observer.admitsBlocks());
        }
        for (int index = 0; index < 128; index++) {
            render(ledger.beginFrame(30_000), expensive, 1_000_000L);
        }
        ProjectionBudgetLedger.FrameBudget later = ledger.beginFrame(30_000);
        render(later, UUID.randomUUID(), 24_000_000L);
        try (ProjectionBudgetLedger.ObserverFrame observer = later.beginObserver(expensive)) {
            assertTrue(observer.admitsBlocks());
        }
    }

    @Test
    void discardedObserverCostCannotBeReintroducedByAnOldFrame() {
        UUID observerId = seed(1_000_000L);
        ProjectionBudgetLedger.ObserverFrame old = ledger.beginFrame(30_000).beginObserver(observerId);
        old.recordBlockWork();
        ledger.forgetObserver(observerId);
        now.addAndGet(1_000_000L);
        old.close();
        ProjectionBudgetLedger.FrameBudget frame = ledger.beginFrame(30_000);
        render(frame, UUID.randomUUID(), 1_000_000L);
        try (ProjectionBudgetLedger.ObserverFrame observer = frame.beginObserver(observerId)) {
            assertFalse(observer.admitsBlocks());
        }
    }

    @Test
    void clearCostsResetsAllObserversToUnknown() {
        UUID cheap = seed(1_000_000L);
        ledger.clearObserverCosts();
        ProjectionBudgetLedger.FrameBudget frame = ledger.beginFrame(30_000);
        render(frame, UUID.randomUUID(), 1_000_000L);
        try (ProjectionBudgetLedger.ObserverFrame observer = frame.beginObserver(cheap)) {
            assertFalse(observer.admitsBlocks());
        }
    }

    @Test
    void zeroBudgetAllowsAllObservers() {
        ProjectionBudgetLedger.FrameBudget frame = ledger.beginFrame(0);
        render(frame, UUID.randomUUID(), 200_000_000L);
        render(frame, UUID.randomUUID(), 200_000_000L);
    }

    @Test
    void deadlineIncludesPreviouslyChargedWorkAndDoesNotMoveWhileRendering() {
        UUID cheap = seed(6_000_000L);
        ProjectionBudgetLedger.FrameBudget frame = ledger.beginFrame(30_000);
        render(frame, UUID.randomUUID(), 24_000_000L);
        long started = now.get();
        try (ProjectionBudgetLedger.ObserverFrame observer = frame.beginObserver(cheap)) {
            assertEquals(started + 6_000_000L, observer.deadlineNanos());
            now.addAndGet(5_000_000L);
            assertEquals(started + 6_000_000L, observer.deadlineNanos());
        }
    }

    @Test
    void unlimitedDeadlineAndExhaustedDeadlineAreExplicit() {
        try (ProjectionBudgetLedger.ObserverFrame observer = ledger.beginFrame(0).beginObserver(UUID.randomUUID())) {
            assertEquals(Long.MAX_VALUE, observer.deadlineNanos());
        }
        ProjectionBudgetLedger.FrameBudget frame = ledger.beginFrame(30_000);
        render(frame, UUID.randomUUID(), 40_000_000L);
        try (ProjectionBudgetLedger.ObserverFrame observer = frame.beginObserver(UUID.randomUUID())) {
            assertEquals(now.get(), observer.deadlineNanos());
        }
    }

    @Test
    void throwingFramesAreChargedAndClosingTwiceDoesNotDoubleCharge() {
        UUID cheap = seed(6_000_000L);
        ProjectionBudgetLedger.FrameBudget frame = ledger.beginFrame(30_000);
        ProjectionBudgetLedger.ObserverFrame failed = frame.beginObserver(UUID.randomUUID());
        assertThrows(IllegalStateException.class, () -> {
            try (failed) {
                failed.recordBlockWork();
                now.addAndGet(24_000_000L);
                throw new IllegalStateException("projection failure");
            }
        });
        failed.close();
        render(frame, cheap, 6_000_000L);
    }

    private UUID seed(long elapsedNanos) {
        UUID observerId = UUID.randomUUID();
        render(ledger.beginFrame(0), observerId, elapsedNanos);
        return observerId;
    }

    private void render(ProjectionBudgetLedger.FrameBudget frame, UUID observerId, long elapsedNanos) {
        try (ProjectionBudgetLedger.ObserverFrame observer = frame.beginObserver(observerId)) {
            assertTrue(observer.admitsBlocks());
            observer.recordBlockWork();
            now.addAndGet(elapsedNanos);
        }
    }
}
