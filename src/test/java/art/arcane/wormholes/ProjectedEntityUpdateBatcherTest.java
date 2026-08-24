package art.arcane.wormholes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityAnimation.EntityAnimationType;

final class ProjectedEntityUpdateBatcherTest {
    @Test
    void tenThousandDuplicateAnimationsNeedOneScheduleAndOnePacketAction() {
        ProjectedEntityUpdateBatcher batcher = new ProjectedEntityUpdateBatcher();
        UUID observerId = new UUID(0L, 1L);
        UUID entityId = new UUID(0L, 2L);
        int scheduleRequests = 0;
        ProjectedEntityUpdateBatcher.ScheduleLease lease = null;

        for (int eventIndex = 0; eventIndex < 10_000; eventIndex++) {
            ProjectedEntityUpdateBatcher.ScheduleLease offered =
                batcher.offerAnimation(observerId, entityId, EntityAnimationType.SWING_MAIN_ARM);
            if (offered != null) {
                scheduleRequests++;
                lease = offered;
            }
        }

        ProjectedEntityUpdateBatcher.Batch batch = batcher.drain(lease);
        List<ProjectedEntityUpdateBatcher.Action> actions = batch.updates().get(entityId);
        assertEquals(1, scheduleRequests);
        assertEquals(1, batch.updates().size());
        assertEquals(1, actions.size());
        assertEquals(EntityAnimationType.SWING_MAIN_ARM, actions.get(0).animationType());
        assertFalse(batcher.complete(lease).reschedule());
    }

    @Test
    void repeatedHurtUsesTheLatestYawWithoutGrowingTheBatch() {
        ProjectedEntityUpdateBatcher batcher = new ProjectedEntityUpdateBatcher();
        UUID observerId = new UUID(0L, 3L);
        UUID entityId = new UUID(0L, 4L);

        ProjectedEntityUpdateBatcher.ScheduleLease lease = batcher.offerHurt(observerId, entityId, 15.0F);
        assertNotNull(lease);
        assertNull(batcher.offerHurt(observerId, entityId, 91.0F));

        List<ProjectedEntityUpdateBatcher.Action> actions = batcher.drain(lease).updates().get(entityId);
        assertEquals(1, actions.size());
        assertEquals(ProjectedEntityUpdateBatcher.ActionKind.HURT, actions.get(0).kind());
        assertEquals(91.0F, actions.get(0).yaw());
        assertFalse(batcher.complete(lease).reschedule());
    }

    @Test
    void thousandDistinctEntitiesAreDrainedInBoundedBatches() {
        ProjectedEntityUpdateBatcher batcher = new ProjectedEntityUpdateBatcher();
        UUID observerId = new UUID(0L, 5L);
        int scheduleRequests = 0;
        ProjectedEntityUpdateBatcher.ScheduleLease lease = null;
        for (int entityIndex = 0; entityIndex < 1_000; entityIndex++) {
            UUID entityId = new UUID(1L, entityIndex);
            ProjectedEntityUpdateBatcher.ScheduleLease offered =
                batcher.offerAnimation(observerId, entityId, EntityAnimationType.SWING_OFF_HAND);
            if (offered != null) {
                scheduleRequests++;
                lease = offered;
            }
        }

        int drained = 0;
        while (true) {
            Map<UUID, List<ProjectedEntityUpdateBatcher.Action>> updates = batcher.drain(lease).updates();
            assertTrue(updates.size() <= ProjectedEntityUpdateBatcher.MAX_ENTITIES_PER_DRAIN);
            drained += updates.size();
            ProjectedEntityUpdateBatcher.Completion completion = batcher.complete(lease);
            if (!completion.reschedule()) {
                break;
            }
            scheduleRequests++;
        }

        assertEquals(1_000, drained);
        assertEquals(4, scheduleRequests);
        assertEquals(0, batcher.pendingEntityCount(observerId));
    }

    @Test
    void anArrivalDuringDrainNeedsOnlyOneFollowupSchedule() {
        ProjectedEntityUpdateBatcher batcher = new ProjectedEntityUpdateBatcher();
        UUID observerId = new UUID(0L, 6L);
        UUID firstEntity = new UUID(0L, 7L);
        UUID secondEntity = new UUID(0L, 8L);

        ProjectedEntityUpdateBatcher.ScheduleLease lease =
            batcher.offerAnimation(observerId, firstEntity, EntityAnimationType.SWING_MAIN_ARM);
        assertNotNull(lease);
        assertEquals(1, batcher.drain(lease).updates().size());
        assertNull(batcher.offerAnimation(observerId, secondEntity, EntityAnimationType.SWING_MAIN_ARM));

        ProjectedEntityUpdateBatcher.Completion completion = batcher.complete(lease);
        assertTrue(completion.reschedule());
        assertEquals(secondEntity, completion.representativeEntityId());
        assertEquals(1, batcher.drain(lease).updates().size());
        assertFalse(batcher.complete(lease).reschedule());
    }

    @Test
    void rejectionReleasesTheObserverForTheNextBurst() {
        ProjectedEntityUpdateBatcher batcher = new ProjectedEntityUpdateBatcher();
        UUID observerId = new UUID(0L, 9L);
        UUID entityId = new UUID(0L, 10L);

        ProjectedEntityUpdateBatcher.ScheduleLease lease =
            batcher.offerAnimation(observerId, entityId, EntityAnimationType.SWING_MAIN_ARM);
        assertNotNull(lease);
        batcher.reject(lease);

        assertEquals(0, batcher.pendingEntityCount(observerId));
        assertNotNull(batcher.offerAnimation(observerId, entityId, EntityAnimationType.SWING_MAIN_ARM));
    }

    @Test
    void aRetiredSessionLeaseCannotDrainOrDiscardTheReplacementSession() {
        ProjectedEntityUpdateBatcher batcher = new ProjectedEntityUpdateBatcher();
        UUID observerId = new UUID(0L, 11L);
        UUID oldEntity = new UUID(0L, 12L);
        UUID newEntity = new UUID(0L, 13L);

        ProjectedEntityUpdateBatcher.ScheduleLease oldLease =
            batcher.offerAnimation(observerId, oldEntity, EntityAnimationType.SWING_MAIN_ARM);
        assertNotNull(oldLease);
        batcher.discard(observerId);
        ProjectedEntityUpdateBatcher.ScheduleLease newLease =
            batcher.offerAnimation(observerId, newEntity, EntityAnimationType.SWING_OFF_HAND);
        assertNotNull(newLease);
        assertNotEquals(oldLease.generation(), newLease.generation());

        assertTrue(batcher.drain(oldLease).isEmpty());
        batcher.discard(oldLease);
        assertEquals(1, batcher.drain(newLease).updates().size());
        assertFalse(batcher.complete(newLease).reschedule());
    }
}
