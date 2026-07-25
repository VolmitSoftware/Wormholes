package art.arcane.wormholes.chunk;

import art.arcane.wormholes.service.WormholesTelemetry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkSendRateTunerTest {
    private static final double PAPER_DEFAULT_SEND_RATE = 75.0D;
    private static final double PAPER_DEFAULT_LOAD_RATE = 100.0D;

    @Test
    void raisesBothLimitsFromPaperDefaultsAndReportsOldAndNewValues() {
        FakeAccessor accessor = paperDefaults();

        ChunkSendRateTuner.Outcome outcome = ChunkSendRateTuner.apply(accessor, true, targets(1000.0D, 1000.0D));

        assertEquals(ChunkSendRateTuner.Status.APPLIED, outcome.status());
        assertEquals(List.of("SEND=1000.0", "LOAD=1000.0"), accessor.writes);
        assertEquals(1000.0D, accessor.values.get(ChunkSendRateLimit.SEND));
        assertEquals(1000.0D, accessor.values.get(ChunkSendRateLimit.LOAD));
        assertEquals(2, outcome.adjustments().size());
        assertEquals(PAPER_DEFAULT_SEND_RATE, outcome.adjustments().get(0).previous());
        assertEquals(1000.0D, outcome.adjustments().get(0).applied());
        assertEquals(PAPER_DEFAULT_LOAD_RATE, outcome.adjustments().get(1).previous());
        assertEquals(1000.0D, outcome.adjustments().get(1).applied());
        assertTrue(outcome.unavailable().isEmpty());

        String line = ChunkSendRateTuner.describe(outcome, "Paper GlobalConfiguration");
        assertTrue(line.contains("send 75.0 -> 1000.0"), line);
        assertTrue(line.contains("load 100.0 -> 1000.0"), line);
    }

    @Test
    void neverLowersARateTheOperatorAlreadySetHigher() {
        FakeAccessor accessor = new FakeAccessor(2500.0D, 4000.0D);

        ChunkSendRateTuner.Outcome outcome = ChunkSendRateTuner.apply(accessor, true, targets(1000.0D, 1000.0D));

        assertEquals(ChunkSendRateTuner.Status.UNCHANGED, outcome.status());
        assertEquals(List.of(), accessor.writes);
        assertEquals(2500.0D, accessor.values.get(ChunkSendRateLimit.SEND));
        assertEquals(4000.0D, accessor.values.get(ChunkSendRateLimit.LOAD));
        assertFalse(outcome.adjustments().get(0).changed());
        assertFalse(outcome.adjustments().get(1).changed());
        assertTrue(ChunkSendRateTuner.describe(outcome, "Paper GlobalConfiguration").contains("send 2500.0 unchanged"));
    }

    @Test
    void raisesExactlyTheLimitThatIsBelowTargetAndLeavesTheOtherAlone() {
        FakeAccessor accessor = new FakeAccessor(PAPER_DEFAULT_SEND_RATE, 6000.0D);

        ChunkSendRateTuner.Outcome outcome = ChunkSendRateTuner.apply(accessor, true, targets(1000.0D, 1000.0D));

        assertEquals(ChunkSendRateTuner.Status.APPLIED, outcome.status());
        assertEquals(List.of("SEND=1000.0"), accessor.writes);
        assertEquals(6000.0D, accessor.values.get(ChunkSendRateLimit.LOAD));
    }

    @Test
    void treatsNonPositiveConfiguredRateAsUnlimitedAndLeavesItAlone() {
        FakeAccessor accessor = new FakeAccessor(-1.0D, 0.0D);

        ChunkSendRateTuner.Outcome outcome = ChunkSendRateTuner.apply(accessor, true, targets(1000.0D, 1000.0D));

        assertEquals(ChunkSendRateTuner.Status.UNCHANGED, outcome.status());
        assertEquals(List.of(), accessor.writes);
        assertEquals(-1.0D, accessor.values.get(ChunkSendRateLimit.SEND));
        assertEquals(0.0D, accessor.values.get(ChunkSendRateLimit.LOAD));
    }

    @Test
    void treatsAboveCeilingConfiguredRateAsUnlimitedAndLeavesItAlone() {
        FakeAccessor accessor = new FakeAccessor(20000.0D, 10000.0D);

        ChunkSendRateTuner.Outcome outcome = ChunkSendRateTuner.apply(accessor, true, targets(9999.0D, 9999.0D));

        assertEquals(ChunkSendRateTuner.Status.UNCHANGED, outcome.status());
        assertEquals(List.of(), accessor.writes);
    }

    @Test
    void configuredRateBelowTheFloorIsComparedAtTheFloor() {
        FakeAccessor atFloor = new FakeAccessor(0.5D, 0.5D);
        ChunkSendRateTuner.Outcome unchanged = ChunkSendRateTuner.apply(atFloor, true, targets(0.75D, 1.0D));
        assertEquals(ChunkSendRateTuner.Status.UNCHANGED, unchanged.status());
        assertEquals(List.of(), atFloor.writes);

        FakeAccessor raised = new FakeAccessor(0.5D, 0.5D);
        ChunkSendRateTuner.Outcome applied = ChunkSendRateTuner.apply(raised, true, targets(2.0D, 2.0D));
        assertEquals(ChunkSendRateTuner.Status.APPLIED, applied.status());
        assertEquals(List.of("SEND=2.0", "LOAD=2.0"), raised.writes);
    }

    @Test
    void nonPositiveTargetIsWrittenAsTheUnlimitedCeiling() {
        FakeAccessor accessor = paperDefaults();

        ChunkSendRateTuner.Outcome outcome = ChunkSendRateTuner.apply(accessor, true, targets(0.0D, -5.0D));

        assertEquals(ChunkSendRateTuner.Status.APPLIED, outcome.status());
        assertEquals(List.of("SEND=10000.0", "LOAD=10000.0"), accessor.writes);
    }

    @Test
    void aboveCeilingTargetIsWrittenAsTheUnlimitedCeiling() {
        FakeAccessor accessor = paperDefaults();

        ChunkSendRateTuner.Outcome outcome = ChunkSendRateTuner.apply(accessor, true, targets(50000.0D, 50000.0D));

        assertEquals(ChunkSendRateTuner.Status.APPLIED, outcome.status());
        assertEquals(List.of("SEND=10000.0", "LOAD=10000.0"), accessor.writes);
    }

    @Test
    void applyingTwiceWritesOnlyOnceBecauseTheSecondPassIsAlreadyAtTarget() {
        FakeAccessor accessor = paperDefaults();

        ChunkSendRateTuner.Outcome first = ChunkSendRateTuner.apply(accessor, true, targets(1000.0D, 1000.0D));
        ChunkSendRateTuner.Outcome second = ChunkSendRateTuner.apply(accessor, true, targets(1000.0D, 1000.0D));

        assertEquals(ChunkSendRateTuner.Status.APPLIED, first.status());
        assertEquals(ChunkSendRateTuner.Status.UNCHANGED, second.status());
        assertEquals(List.of("SEND=1000.0", "LOAD=1000.0"), accessor.writes);
    }

    @Test
    void disabledTunerNeverTouchesTheAccessor() {
        FakeAccessor accessor = paperDefaults();

        ChunkSendRateTuner.Outcome outcome = ChunkSendRateTuner.apply(accessor, false, targets(1000.0D, 1000.0D));

        assertEquals(ChunkSendRateTuner.Status.DISABLED, outcome.status());
        assertEquals(0, accessor.reads);
        assertEquals(List.of(), accessor.writes);
        assertEquals(PAPER_DEFAULT_SEND_RATE, accessor.values.get(ChunkSendRateLimit.SEND));
    }

    @Test
    void absentPaperConfigurationIsANoOpNotAnException() {
        FakeAccessor accessor = paperDefaults();
        accessor.available = false;

        ChunkSendRateTuner.Outcome outcome = ChunkSendRateTuner.apply(accessor, true, targets(1000.0D, 1000.0D));

        assertEquals(ChunkSendRateTuner.Status.UNSUPPORTED, outcome.status());
        assertEquals(0, accessor.reads);
        assertEquals(List.of(), accessor.writes);
        assertTrue(ChunkSendRateTuner.describe(outcome, "absent").contains("no-op"));
    }

    @Test
    void renamedFieldIsSkippedWithoutBlockingTheOtherLimit() {
        FakeAccessor accessor = paperDefaults();
        accessor.unreadable.add(ChunkSendRateLimit.SEND);

        ChunkSendRateTuner.Outcome outcome = ChunkSendRateTuner.apply(accessor, true, targets(1000.0D, 1000.0D));

        assertEquals(ChunkSendRateTuner.Status.APPLIED, outcome.status());
        assertEquals(List.of(ChunkSendRateLimit.SEND), outcome.unavailable());
        assertEquals(List.of("LOAD=1000.0"), accessor.writes);
        assertTrue(ChunkSendRateTuner.describe(outcome, "Paper GlobalConfiguration")
            .contains("unreadable fields playerMaxChunkSendRate"));
    }

    @Test
    void everyFieldRenamedYieldsAnUnavailableNoOpAndCountsTelemetry() {
        FakeAccessor accessor = paperDefaults();
        accessor.unreadable.addAll(Set.of(ChunkSendRateLimit.SEND, ChunkSendRateLimit.LOAD));
        long before = failureCount();

        ChunkSendRateTuner.Outcome outcome = ChunkSendRateTuner.apply(accessor, true, targets(1000.0D, 1000.0D));

        assertEquals(ChunkSendRateTuner.Status.UNAVAILABLE, outcome.status());
        assertEquals(List.of(), accessor.writes);
        assertEquals(before + 1L, failureCount());
        assertTrue(ChunkSendRateTuner.describe(outcome, "Paper GlobalConfiguration")
            .contains("playerMaxChunkSendRate, playerMaxChunkLoadRate"));
    }

    @Test
    void aFailedWriteIsReportedAsUnavailableRatherThanApplied() {
        FakeAccessor accessor = paperDefaults();
        accessor.unwritable.add(ChunkSendRateLimit.SEND);
        long before = failureCount();

        ChunkSendRateTuner.Outcome outcome = ChunkSendRateTuner.apply(accessor, true, targets(1000.0D, 1000.0D));

        assertEquals(List.of(ChunkSendRateLimit.SEND), outcome.unavailable());
        assertEquals(1, outcome.adjustments().size());
        assertEquals(ChunkSendRateLimit.LOAD, outcome.adjustments().get(0).limit());
        assertEquals(before + 1L, failureCount());
        assertEquals(PAPER_DEFAULT_SEND_RATE, accessor.values.get(ChunkSendRateLimit.SEND));
    }

    @Test
    void effectiveRateModelsThePaperClamp() {
        assertEquals(10000.0D, ChunkSendRateTuner.effectiveRate(0.0D));
        assertEquals(10000.0D, ChunkSendRateTuner.effectiveRate(-1.0D));
        assertEquals(10000.0D, ChunkSendRateTuner.effectiveRate(10000.0001D));
        assertEquals(10000.0D, ChunkSendRateTuner.effectiveRate(10000.0D));
        assertEquals(1.0D, ChunkSendRateTuner.effectiveRate(0.0001D));
        assertEquals(1.0D, ChunkSendRateTuner.effectiveRate(1.0D));
        assertEquals(75.0D, ChunkSendRateTuner.effectiveRate(75.0D));
        assertEquals(9999.9D, ChunkSendRateTuner.effectiveRate(9999.9D));
    }

    private static ChunkSendRateTuner.Targets targets(double send, double load) {
        return new ChunkSendRateTuner.Targets(send, load);
    }

    private static FakeAccessor paperDefaults() {
        return new FakeAccessor(PAPER_DEFAULT_SEND_RATE, PAPER_DEFAULT_LOAD_RATE);
    }

    private static long failureCount() {
        Map<String, Long> breakdown = WormholesTelemetry.failureBreakdown();
        Long value = breakdown.get(ChunkSendRateTuner.FAILURE_FIELD_UNAVAILABLE);
        return value == null ? 0L : value;
    }

    private static final class FakeAccessor implements ChunkSendRateAccessor {
        private final EnumMap<ChunkSendRateLimit, Double> values = new EnumMap<>(ChunkSendRateLimit.class);
        private final EnumSet<ChunkSendRateLimit> unreadable = EnumSet.noneOf(ChunkSendRateLimit.class);
        private final EnumSet<ChunkSendRateLimit> unwritable = EnumSet.noneOf(ChunkSendRateLimit.class);
        private final List<String> writes = new ArrayList<>();
        private boolean available = true;
        private int reads;

        private FakeAccessor(double sendRate, double loadRate) {
            values.put(ChunkSendRateLimit.SEND, sendRate);
            values.put(ChunkSendRateLimit.LOAD, loadRate);
        }

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public String describe() {
            return "fake";
        }

        @Override
        public OptionalDouble read(ChunkSendRateLimit limit) {
            reads++;
            if (unreadable.contains(limit)) {
                return OptionalDouble.empty();
            }
            return OptionalDouble.of(values.get(limit));
        }

        @Override
        public boolean write(ChunkSendRateLimit limit, double value) {
            if (unwritable.contains(limit)) {
                return false;
            }
            values.put(limit, value);
            writes.add(limit.name() + "=" + value);
            return true;
        }
    }
}
