package art.arcane.wormholes;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import art.arcane.wormholes.service.WormholesTelemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class ProjectionManagerFailureTelemetryTest {
    private static final String OBSERVER_FRAME_DROPPED = "PROJECTION_OBSERVER_FRAME_DROPPED";
    private static final String ENTITY_UPDATE_DROPPED = "PROJECTION_ENTITY_UPDATE_DROPPED";

    @Test
    void aRefusedObserverFrameIsCountedOnThePluginWideFailureSurface() {
        Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
        UUID observerId = UUID.randomUUID();
        AtomicInteger remaining = new AtomicInteger();
        long reasonBefore = failureCount(OBSERVER_FRAME_DROPPED);
        long totalBefore = WormholesTelemetry.failures();

        assertFalse(ProjectionManager.dispatchObserverFrame(inFlight, null, observerId, remaining, 4,
            () -> fail("the frame must not run when scheduling is refused"),
            (observer, frame, retired) -> false));

        assertEquals(reasonBefore + 1L, failureCount(OBSERVER_FRAME_DROPPED),
            "a refused observer frame is a terminal failure and must show up in the failure breakdown");
        assertEquals(totalBefore + 1L, WormholesTelemetry.failures());
    }

    @Test
    void aRetiredThenRefusedObserverFrameIsCountedExactlyOnce() {
        Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
        UUID observerId = UUID.randomUUID();
        AtomicInteger remaining = new AtomicInteger();
        long reasonBefore = failureCount(OBSERVER_FRAME_DROPPED);

        assertFalse(ProjectionManager.dispatchObserverFrame(inFlight, null, observerId, remaining, 5,
            () -> fail("the frame must not run when scheduling is refused"),
            (observer, frame, retired) -> {
                retired.run();
                return false;
            }));

        assertEquals(reasonBefore + 1L, failureCount(OBSERVER_FRAME_DROPPED),
            "one dropped frame is one failure; the retirement callback and the refused return are two levels "
                + "of the same drop, not two drops");
    }

    @Test
    void aScheduledObserverFrameIsNotCountedAsAFailure() {
        Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
        UUID observerId = UUID.randomUUID();
        AtomicInteger remaining = new AtomicInteger();
        long reasonBefore = failureCount(OBSERVER_FRAME_DROPPED);
        long totalBefore = WormholesTelemetry.failures();

        assertTrue(ProjectionManager.dispatchObserverFrame(inFlight, null, observerId, remaining, 2,
            () -> {
            },
            (observer, frame, retired) -> true));

        assertEquals(reasonBefore, failureCount(OBSERVER_FRAME_DROPPED));
        assertEquals(totalBefore, WormholesTelemetry.failures());
    }

    @Test
    void anInvalidObserverSkipsOneFrameAndIsEligibleAgainOnTheNextTick() {
        Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
        UUID observerId = UUID.randomUUID();
        AtomicInteger remaining = new AtomicInteger();
        AtomicInteger framesRun = new AtomicInteger();
        long reasonBefore = failureCount(OBSERVER_FRAME_DROPPED);

        boolean accepted = ProjectionManager.dispatchObserverFrame(inFlight, observer(observerId, false),
            observerId, remaining, 3,
            frame(framesRun, inFlight, observerId), inlineEntityScheduler());

        assertTrue(accepted, "the inline entity scheduler accepts the task and then retires it");
        assertEquals(0, framesRun.get(),
            "a projection frame must not be rendered to an observer the server no longer considers valid");
        assertTrue(inFlight.isEmpty(), "the retired frame must release the in-flight observer id");
        assertEquals(3, remaining.get(), "the retired frame must hand back the projector budget it reserved");
        assertEquals(reasonBefore + 1L, failureCount(OBSERVER_FRAME_DROPPED),
            "the skipped frame must be counted, so a permanently invalid observer is visible to operators "
                + "instead of silently dark");

        remaining.set(0);
        assertTrue(ProjectionManager.dispatchObserverFrame(inFlight, observer(observerId, true),
            observerId, remaining, 3,
            frame(framesRun, inFlight, observerId), inlineEntityScheduler()));

        assertEquals(1, framesRun.get(),
            "the skip is a one tick deferral; the very next tick must render the observer again");
        assertTrue(inFlight.isEmpty());
    }

    @Test
    void aRefusedProjectedEntityUpdateIsCountedOnThePluginWideFailureSurface() {
        UUID entityId = UUID.randomUUID();
        long reasonBefore = failureCount(ENTITY_UPDATE_DROPPED);
        long totalBefore = WormholesTelemetry.failures();

        assertFalse(ProjectionManager.dispatchProjectedEntityUpdate(observer(UUID.randomUUID(), true), entityId,
            "animation", () -> fail("the update must not run when scheduling is refused"),
            () -> {
            }, (observer, update, retired) -> false));

        assertEquals(reasonBefore + 1L, failureCount(ENTITY_UPDATE_DROPPED),
            "a refused projected entity update is a terminal failure and must show up in the failure breakdown");
        assertEquals(totalBefore + 1L, WormholesTelemetry.failures());
    }

    @Test
    void anAcceptedProjectedEntityUpdateIsNotCountedAsAFailure() {
        UUID entityId = UUID.randomUUID();
        AtomicInteger updatesRun = new AtomicInteger();
        long reasonBefore = failureCount(ENTITY_UPDATE_DROPPED);
        long totalBefore = WormholesTelemetry.failures();

        assertTrue(ProjectionManager.dispatchProjectedEntityUpdate(observer(UUID.randomUUID(), true), entityId,
            "hurt", () -> updatesRun.incrementAndGet(),
            () -> {
            }, (observer, update, retired) -> {
                update.run();
                return true;
            }));

        assertEquals(1, updatesRun.get());
        assertEquals(reasonBefore, failureCount(ENTITY_UPDATE_DROPPED));
        assertEquals(totalBefore, WormholesTelemetry.failures());
    }

    private static Runnable frame(AtomicInteger framesRun, Set<UUID> inFlight, UUID observerId) {
        return () -> {
            framesRun.incrementAndGet();
            inFlight.remove(observerId);
        };
    }

    private static ProjectionManager.ObserverFrameScheduler inlineEntityScheduler() {
        return (observer, frame, retired) -> {
            if (retired != null && !observer.isValid()) {
                retired.run();
                return true;
            }
            frame.run();
            return true;
        };
    }

    private static Player observer(UUID observerId, boolean valid) {
        return (Player) Proxy.newProxyInstance(
            Player.class.getClassLoader(),
            new Class<?>[] {Player.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> observerId;
                case "isValid" -> valid;
                case "hashCode" -> observerId.hashCode();
                case "equals" -> proxy == args[0];
                case "toString" -> "observer(" + observerId + ")";
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }

    private static long failureCount(String reason) {
        return WormholesTelemetry.failureBreakdown().getOrDefault(reason, 0L);
    }
}
