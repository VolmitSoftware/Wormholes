package art.arcane.wormholes.modded;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AppliedColumnChangesTest {
    @Test
    public void repeatedAppliedWritesCoalesceButANotificationForAnotherCellDoesNotClearThem() {
        AppliedColumnChanges<Object> changes = new AppliedColumnChanges<>();
        Object world = new Object();
        changes.block(world, 3L, 40L);
        changes.block(world, 3L, 40L);
        changes.block(world, 3L, 41L);
        changes.notified(world, 3L, 40L);
        changes.notified(world, 3L, 42L);
        List<Long> delivered = new ArrayList<>();

        changes.drain((level, column) -> delivered.add(column));
        changes.drain((level, column) -> delivered.add(column));

        assertEquals(List.of(3L), delivered);
    }

    @Test
    public void everyAppliedCellMustHaveAVanillaNotificationBeforeResendIsOmitted() {
        AppliedColumnChanges<Object> changes = new AppliedColumnChanges<>();
        Object world = new Object();
        changes.block(world, 3L, 40L);
        changes.block(world, 3L, 41L);
        changes.notified(world, 3L, 40L);
        changes.notified(world, 3L, 41L);
        List<Long> delivered = new ArrayList<>();

        changes.drain((level, column) -> delivered.add(column));

        assertTrue(delivered.isEmpty());
    }

    @Test
    public void biomeAndMetadataDirtSurviveOrdinaryBlockNotifications() {
        AppliedColumnChanges<Object> changes = new AppliedColumnChanges<>();
        Object world = new Object();
        changes.column(world, 3L);
        changes.column(world, 3L);
        changes.block(world, 3L, 40L);
        changes.notified(world, 3L, 40L);
        List<Long> delivered = new ArrayList<>();

        changes.drain((level, column) -> delivered.add(column));

        assertEquals(List.of(3L), delivered);
    }

    @Test
    public void lifecycleIdentitySeparatesWorldInstancesAndClearRetiresPendingWork() {
        AppliedColumnChanges<String> changes = new AppliedColumnChanges<>();
        String previous = new String("world");
        String replacement = new String("world");
        changes.block(previous, 3L, 40L);
        changes.notified(replacement, 3L, 40L);
        List<String> delivered = new ArrayList<>();
        changes.drain((level, column) -> delivered.add(level));
        assertEquals(1, delivered.size());
        assertTrue(delivered.getFirst() == previous);
        changes.column(previous, 3L);
        changes.clear();
        changes.drain((level, column) -> delivered.add(level));
        assertEquals(1, delivered.size());
    }
}
