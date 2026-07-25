package art.arcane.wormholes;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

final class ProjectionBudgetLedgerTest {
    @Test
    void claimTakesTheFullRequestWhenBudgetAllows() {
        AtomicInteger remaining = new AtomicInteger(5);

        assertEquals(3, ProjectionBudgetLedger.claim(remaining, 3));
        assertEquals(2, remaining.get());
    }

    @Test
    void claimTakesWhatIsLeftWhenRequestExceedsBudget() {
        AtomicInteger remaining = new AtomicInteger(2);

        assertEquals(2, ProjectionBudgetLedger.claim(remaining, 5));
        assertEquals(0, remaining.get());
    }

    @Test
    void claimNeverOverdrawsAnExhaustedBudget() {
        AtomicInteger remaining = new AtomicInteger(0);

        assertEquals(0, ProjectionBudgetLedger.claim(remaining, 3));
        assertEquals(0, remaining.get());
    }

    @Test
    void claimLeavesBudgetUntouchedWhenNothingIsRequested() {
        AtomicInteger remaining = new AtomicInteger(4);

        assertEquals(0, ProjectionBudgetLedger.claim(remaining, 0));
        assertEquals(0, ProjectionBudgetLedger.claim(remaining, -1));
        assertEquals(4, remaining.get());
    }
}
