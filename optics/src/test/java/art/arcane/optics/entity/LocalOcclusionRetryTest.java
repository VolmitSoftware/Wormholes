package art.arcane.optics.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import art.arcane.optics.math.Vec3d;
import art.arcane.optics.occlusion.LocalOcclusionArbiter;
import art.arcane.optics.spi.FakeOpticsScheduler;

public final class LocalOcclusionRetryTest {
    @Test
    public void ownershipFailuresRetryOnTheObserverScheduler() {
        RecordingEntityOutput output = new RecordingEntityOutput();
        FakeOpticsScheduler<Object, Object> scheduler = new FakeOpticsScheduler<Object, Object>();
        LocalOcclusionArbiter<Object, Object> arbiter = new LocalOcclusionArbiter<Object, Object>(new ValidEntities(), output, scheduler);
        Object observer = new Object();
        Object entity = new Object();
        UUID entityId = UUID.randomUUID();
        output.hideFailures = 1;

        arbiter.replace(observer, UUID.randomUUID(), Map.of(entityId, entity));

        assertTrue(output.hidden.isEmpty());
        assertEquals(1, output.warnings.size());
        assertEquals(1, scheduler.pendingObserver());

        scheduler.runNextObserverTask();

        assertEquals(List.of(entity), output.hidden);
        assertEquals(0, scheduler.pendingObserver());
    }

    @Test
    public void rejectedRetriesCanBeScheduledAgain() {
        RecordingEntityOutput output = new RecordingEntityOutput();
        FakeOpticsScheduler<Object, Object> scheduler = new FakeOpticsScheduler<Object, Object>();
        LocalOcclusionArbiter<Object, Object> arbiter = new LocalOcclusionArbiter<Object, Object>(new ValidEntities(), output, scheduler);
        Object observer = new Object();
        UUID ownerId = UUID.randomUUID();
        output.hideFailures = 2;
        scheduler.reject(true);

        arbiter.replace(observer, ownerId, Map.of(UUID.randomUUID(), new Object()));
        scheduler.reject(false);
        arbiter.replace(observer, ownerId, Map.of(UUID.randomUUID(), new Object()));

        assertEquals(1, scheduler.pendingObserver());
    }

    private static final class ValidEntities implements EntityFeed<Object, Object, Object, Object> {
        @Override
        public List<EntitySnapshot> entities(Object view, SnapshotProjector.EntityRange range) {
            return List.of();
        }

        @Override
        public Collection<Object> localEntities(Object world, Vec3d center, int range) {
            return List.of();
        }

        @Override
        public boolean visible(Object observer, Object view, UUID entityId) {
            return true;
        }

        @Override
        public EntityProfile profile(Object view, UUID entityId) {
            return null;
        }

        @Override
        public int stateVersion(Object view, UUID entityId) {
            return 0;
        }

        @Override
        public boolean hasMap(Object view, UUID entityId) {
            return false;
        }

        @Override
        public boolean valid(Object entity) {
            return true;
        }
    }
}
