package art.arcane.wormholes.network.view;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewEntityCaptureBudgetTest {
    @Test
    void oneThousandSessionsRotateWithinTheGlobalAdmissionLimit() {
        int admissionLimit = ViewServer.MAX_ENTITY_CAPTURE_ADMISSIONS_PER_TICK;
        int inFlightLimit = ViewServer.MAX_ENTITY_CAPTURES_IN_FLIGHT;
        ViewEntityCaptureBudget<UUID> budget = new ViewEntityCaptureBudget<UUID>(admissionLimit, inFlightLimit);
        List<UUID> sessions = sessionIds(1_000);
        Set<UUID> captured = new HashSet<UUID>();
        for (UUID session : sessions) {
            budget.request(session);
        }

        int passes = 0;
        while (captured.size() < sessions.size()) {
            List<ViewEntityCaptureBudget.Admission<UUID>> admissions = budget.acquire();
            assertFalse(admissions.isEmpty());
            assertTrue(admissions.size() <= admissionLimit);
            assertTrue(budget.inFlightCount() <= inFlightLimit);
            for (ViewEntityCaptureBudget.Admission<UUID> admission : admissions) {
                assertTrue(captured.add(admission.key()));
                budget.complete(admission);
            }
            passes++;
        }

        assertEquals((sessions.size() + admissionLimit - 1) / admissionLimit, passes);
        assertEquals(0, budget.pendingCount());
        assertEquals(0, budget.inFlightCount());
    }

    @Test
    void continuouslyDueSessionsCompleteRepeatedFairRotations() {
        int admissionLimit = ViewServer.MAX_ENTITY_CAPTURE_ADMISSIONS_PER_TICK;
        ViewEntityCaptureBudget<UUID> budget = new ViewEntityCaptureBudget<UUID>(
            admissionLimit, ViewServer.MAX_ENTITY_CAPTURES_IN_FLIGHT);
        List<UUID> sessions = sessionIds(1_000);
        int rotations = 2;
        int passesPerRotation = (sessions.size() + admissionLimit - 1) / admissionLimit;
        int[] admissionsBySession = new int[sessions.size()];
        for (UUID session : sessions) {
            budget.request(session);
        }

        for (int pass = 0; pass < passesPerRotation * rotations; pass++) {
            List<ViewEntityCaptureBudget.Admission<UUID>> admissions = budget.acquire();
            assertEquals(admissionLimit, admissions.size());
            for (ViewEntityCaptureBudget.Admission<UUID> admission : admissions) {
                admissionsBySession[(int) admission.key().getLeastSignificantBits() - 1]++;
                budget.complete(admission);
            }
            for (UUID session : sessions) {
                budget.request(session);
            }
        }

        for (int admissions : admissionsBySession) {
            assertEquals(rotations, admissions);
        }
    }

    @Test
    void inFlightCapturesReserveCapacityAcrossPasses() {
        ViewEntityCaptureBudget<UUID> budget = new ViewEntityCaptureBudget<UUID>(8, 12);
        List<UUID> sessions = sessionIds(1_000);
        for (UUID session : sessions) {
            budget.request(session);
        }

        List<ViewEntityCaptureBudget.Admission<UUID>> first = budget.acquire();
        List<ViewEntityCaptureBudget.Admission<UUID>> second = budget.acquire();
        assertEquals(8, first.size());
        assertEquals(4, second.size());
        assertEquals(12, budget.inFlightCount());
        assertTrue(budget.acquire().isEmpty());

        budget.complete(first.get(0));
        budget.complete(first.get(1));
        budget.complete(first.get(2));
        List<ViewEntityCaptureBudget.Admission<UUID>> replacement = budget.acquire();
        assertEquals(3, replacement.size());
        assertEquals(12, budget.inFlightCount());
    }

    @Test
    void rejectedCaptureRetriesAfterOtherWaitingSessions() {
        ViewEntityCaptureBudget<UUID> budget = new ViewEntityCaptureBudget<UUID>(1, 1);
        List<UUID> sessions = sessionIds(3);
        for (UUID session : sessions) {
            budget.request(session);
        }

        ViewEntityCaptureBudget.Admission<UUID> rejected = budget.acquire().getFirst();
        budget.reject(rejected);
        ViewEntityCaptureBudget.Admission<UUID> second = budget.acquire().getFirst();
        budget.complete(second);
        ViewEntityCaptureBudget.Admission<UUID> third = budget.acquire().getFirst();
        budget.complete(third);
        ViewEntityCaptureBudget.Admission<UUID> retried = budget.acquire().getFirst();

        assertEquals(sessions.get(0), rejected.key());
        assertEquals(sessions.get(1), second.key());
        assertEquals(sessions.get(2), third.key());
        assertEquals(rejected.key(), retried.key());
    }

    @Test
    void retirementAndShutdownDoNotAdmitMoreWork() {
        ViewEntityCaptureBudget<UUID> budget = new ViewEntityCaptureBudget<UUID>(2, 2);
        List<UUID> sessions = sessionIds(3);
        for (UUID session : sessions) {
            budget.request(session);
        }
        List<ViewEntityCaptureBudget.Admission<UUID>> active = budget.acquire();
        budget.retire(active.getFirst().key());
        budget.close();

        assertTrue(budget.acquire().isEmpty());
        assertEquals(2, budget.inFlightCount());
        for (ViewEntityCaptureBudget.Admission<UUID> admission : active) {
            budget.complete(admission);
        }
        assertEquals(0, budget.inFlightCount());
        assertEquals(0, budget.pendingCount());
    }

    private static List<UUID> sessionIds(int count) {
        List<UUID> sessions = new ArrayList<UUID>(count);
        for (int index = 0; index < count; index++) {
            sessions.add(new UUID(0L, index + 1L));
        }
        return sessions;
    }
}
