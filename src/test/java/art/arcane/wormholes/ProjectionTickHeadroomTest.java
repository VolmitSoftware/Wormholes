package art.arcane.wormholes;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public final class ProjectionTickHeadroomTest {
    private static final int MIN_FRAME = 5_000;
    private static final int MAX_FRAME = 30_000;

    @Test
    public void targetZeroPassesTheConfiguredFrameBudgetThrough() {
        ProjectionTickHeadroom headroom = new ProjectionTickHeadroom();

        headroom.recordTickEnd(-40_000_000L);

        assertEquals(MAX_FRAME, headroom.frameMicros(0, MIN_FRAME, MAX_FRAME));
        assertEquals(-1, headroom.governedFrameMicros());
    }

    @Test
    public void unlimitedFrameBudgetIsNeverGoverned() {
        ProjectionTickHeadroom headroom = new ProjectionTickHeadroom();

        headroom.recordTickEnd(-40_000_000L);

        assertEquals(0, headroom.frameMicros(10, MIN_FRAME, 0));
        assertEquals(-1, headroom.governedFrameMicros());
    }

    @Test
    public void budgetStartsAtTheCeilingUntilATickEndIsSeen() {
        ProjectionTickHeadroom headroom = new ProjectionTickHeadroom();

        assertEquals(MAX_FRAME, headroom.frameMicros(10, MIN_FRAME, MAX_FRAME));
        assertEquals(MAX_FRAME, headroom.governedFrameMicros());
    }

    @Test
    public void budgetShrinksByTheHeadroomShortfall() {
        ProjectionTickHeadroom headroom = new ProjectionTickHeadroom();

        headroom.recordTickEnd(4_000_000L);

        assertEquals(24_000, headroom.frameMicros(10, MIN_FRAME, MAX_FRAME));
    }

    @Test
    public void budgetRecoversAdditivelyWhenHeadroomReturns() {
        ProjectionTickHeadroom headroom = new ProjectionTickHeadroom();
        headroom.recordTickEnd(-5_000_000L);
        assertEquals(15_000, headroom.frameMicros(10, MIN_FRAME, MAX_FRAME));

        headroom.recordTickEnd(12_000_000L);
        assertEquals(17_000, headroom.frameMicros(10, MIN_FRAME, MAX_FRAME));

        headroom.recordTickEnd(40_000_000L);
        assertEquals(MAX_FRAME, headroom.frameMicros(10, MIN_FRAME, MAX_FRAME));
    }

    @Test
    public void budgetNeverLeavesTheFloorAndCeiling() {
        ProjectionTickHeadroom headroom = new ProjectionTickHeadroom();

        headroom.recordTickEnd(-500_000_000L);
        assertEquals(MIN_FRAME, headroom.frameMicros(10, MIN_FRAME, MAX_FRAME));

        headroom.recordTickEnd(500_000_000L);
        assertEquals(MAX_FRAME, headroom.frameMicros(10, MIN_FRAME, MAX_FRAME));

        headroom.recordTickEnd(-500_000_000L);
        assertEquals(MAX_FRAME, headroom.frameMicros(10, 90_000, MAX_FRAME));
    }

    @Test
    public void eachTickEndSampleIsConsumedOnce() {
        ProjectionTickHeadroom headroom = new ProjectionTickHeadroom();
        headroom.recordTickEnd(4_000_000L);

        assertEquals(24_000, headroom.frameMicros(10, MIN_FRAME, MAX_FRAME));
        assertEquals(24_000, headroom.frameMicros(10, MIN_FRAME, MAX_FRAME));
    }

    @Test
    public void turningTheGovernorOffAndOnRestartsFromTheCeiling() {
        ProjectionTickHeadroom headroom = new ProjectionTickHeadroom();
        headroom.recordTickEnd(-20_000_000L);
        assertEquals(MIN_FRAME, headroom.frameMicros(10, MIN_FRAME, MAX_FRAME));

        assertEquals(MAX_FRAME, headroom.frameMicros(0, MIN_FRAME, MAX_FRAME));
        assertEquals(MAX_FRAME, headroom.frameMicros(10, MIN_FRAME, MAX_FRAME));
    }
}
