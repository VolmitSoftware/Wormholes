package art.arcane.wormholes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.entity.Player;
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

    @Test
    void thousandTrackedObserversStayGloballyBoundedAndCompleteOneFairRotation() {
        ProjectionBudgetLedger ledger = new ProjectionBudgetLedger();
        List<Player> observers = observers(1_000);
        Set<UUID> tracked = observerIds(observers);
        Set<UUID> seen = new HashSet<UUID>();

        for (long frameTick = 1L; frameTick <= 16L; frameTick++) {
            List<Player> admitted = ledger.selectObserverCandidates(observers, tracked, Set.of(), frameTick,
                64, true);
            assertEquals(64, admitted.size());
            for (Player observer : admitted) {
                seen.add(observer.getUniqueId());
            }
        }

        assertEquals(1_000, seen.size());
    }

    @Test
    void trackedTeardownHasPriorityWithoutStarvingDiscovery() {
        ProjectionBudgetLedger ledger = new ProjectionBudgetLedger();
        List<Player> observers = observers(1_000);
        Set<UUID> tracked = new HashSet<UUID>();
        for (int index = 0; index < 800; index++) {
            tracked.add(observers.get(index).getUniqueId());
        }
        Set<UUID> trackedSeen = new HashSet<UUID>();
        Set<UUID> discoverySeen = new HashSet<UUID>();

        for (long frameTick = 1L; frameTick <= 17L; frameTick++) {
            List<Player> admitted = ledger.selectObserverCandidates(observers, tracked, Set.of(), frameTick,
                64, true);
            assertEquals(64, admitted.size());
            int trackedInFrame = 0;
            int discoveryInFrame = 0;
            for (Player observer : admitted) {
                UUID observerId = observer.getUniqueId();
                if (tracked.contains(observerId)) {
                    trackedSeen.add(observerId);
                    trackedInFrame++;
                } else {
                    discoverySeen.add(observerId);
                    discoveryInFrame++;
                }
            }
            assertEquals(48, trackedInFrame);
            assertEquals(16, discoveryInFrame);
        }

        assertEquals(800, trackedSeen.size());
        assertEquals(200, discoverySeen.size());
    }

    @Test
    void inFlightObserversDoNotConsumeTheAdmissionPass() {
        ProjectionBudgetLedger ledger = new ProjectionBudgetLedger();
        List<Player> observers = observers(1_000);
        Set<UUID> tracked = observerIds(observers);
        Set<UUID> inFlight = new HashSet<UUID>();
        for (int index = 0; index < 936; index++) {
            inFlight.add(observers.get(index).getUniqueId());
        }

        List<Player> admitted = ledger.selectObserverCandidates(observers, tracked, inFlight, 1L, 64, true);

        assertEquals(64, admitted.size());
        for (Player observer : admitted) {
            assertFalse(inFlight.contains(observer.getUniqueId()));
        }
    }

    @Test
    void oneSlotStillGivesDiscoveryARegularTurn() {
        assertEquals(1, ProjectionBudgetLedger.priorityAdmissionTarget(1, true, true, 1L));
        assertEquals(1, ProjectionBudgetLedger.priorityAdmissionTarget(1, true, true, 2L));
        assertEquals(1, ProjectionBudgetLedger.priorityAdmissionTarget(1, true, true, 3L));
        assertEquals(0, ProjectionBudgetLedger.priorityAdmissionTarget(1, true, true, 4L));
        assertTrue(ProjectionBudgetLedger.priorityAdmissionTarget(64, true, true, 1L) > 32);
    }

    private static List<Player> observers(int count) {
        List<Player> observers = new ArrayList<Player>(count);
        for (int index = 0; index < count; index++) {
            UUID observerId = new UUID(0L, index + 1L);
            observers.add((Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getUniqueId" -> observerId;
                    case "hashCode" -> Integer.valueOf(observerId.hashCode());
                    case "equals" -> Boolean.valueOf(proxy == arguments[0]);
                    case "toString" -> "observer(" + observerId + ")";
                    default -> throw new UnsupportedOperationException(method.getName());
                }));
        }
        return observers;
    }

    private static Set<UUID> observerIds(List<Player> observers) {
        Set<UUID> observerIds = new HashSet<UUID>(observers.size());
        for (Player observer : observers) {
            observerIds.add(observer.getUniqueId());
        }
        return observerIds;
    }
}
