package art.arcane.wormholes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

final class ProjectedEntityInterestIndexTest {
    @Test
    void thousandUnrelatedProjectorsRouteOnlyTheInterestedTarget() {
        ProjectedEntityInterestIndex<String> index = new ProjectedEntityInterestIndex<String>();
        UUID selectedEntity = new UUID(0L, 734L);
        for (int projectorIndex = 0; projectorIndex < 1_000; projectorIndex++) {
            String projector = "projector-" + projectorIndex;
            index.activate(projector);
            index.replace(projector, Set.of(new UUID(0L, projectorIndex + 1L)));
        }

        AtomicInteger scheduled = new AtomicInteger();
        List<String> targets = index.targets(selectedEntity);
        for (String ignored : targets) {
            scheduled.incrementAndGet();
        }

        assertEquals(List.of("projector-733"), targets);
        assertEquals(1, scheduled.get());
        assertEquals(1_000, index.targetCount());
        assertEquals(1_000, index.entityCount());
    }

    @Test
    void replacementAndDeactivationRemoveEveryStaleReverseRoute() {
        ProjectedEntityInterestIndex<String> index = new ProjectedEntityInterestIndex<String>();
        String projector = new String("projector");
        UUID formerEntity = new UUID(0L, 1L);
        UUID currentEntity = new UUID(0L, 2L);

        index.activate(projector);
        assertTrue(index.replace(projector, Set.of(formerEntity)));
        assertEquals(List.of(projector), index.targets(formerEntity));

        assertTrue(index.replace(projector, Set.of(currentEntity)));
        assertTrue(index.targets(formerEntity).isEmpty());
        assertEquals(List.of(projector), index.targets(currentEntity));

        index.deactivate(projector);
        assertTrue(index.targets(currentEntity).isEmpty());
        assertFalse(index.replace(projector, Set.of(formerEntity)),
            "a late frame must not reactivate a retired projector");
        assertTrue(index.targets(formerEntity).isEmpty());
    }

    @Test
    void closeRejectsLateActivationAndClearsBothDirections() {
        ProjectedEntityInterestIndex<String> index = new ProjectedEntityInterestIndex<String>();
        String first = new String("first");
        String late = new String("late");
        UUID entityId = new UUID(0L, 3L);

        index.activate(first);
        index.replace(first, Set.of(entityId));
        index.close();
        index.activate(late);

        assertFalse(index.replace(late, Set.of(entityId)));
        assertTrue(index.targets(entityId).isEmpty());
        assertEquals(0, index.targetCount());
        assertEquals(0, index.entityCount());
    }
}
