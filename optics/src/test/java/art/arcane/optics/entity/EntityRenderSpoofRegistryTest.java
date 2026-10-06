package art.arcane.optics.entity;

import art.arcane.optics.math.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

public final class EntityRenderSpoofRegistryTest {
    @Test
    public void animationTargetsExcludeUnspawnedAndNonLivingEntities() {
        RecordingEntityOutput host = new RecordingEntityOutput();
        SpoofRegistry<Object, Vec3d> registry = new SpoofRegistry<>(host);
        UUID source = UUID.randomUUID();
        assertEquals(-1, registry.livingId(source));
        registry.track(source, SpoofedEntity.create(false, false, false));
        assertEquals(-1, registry.livingId(source));
        SpoofedEntity living = SpoofedEntity.create(false, false, true);
        registry.track(source, living);
        assertEquals(living.fakeId, registry.livingId(source));
        registry.destroyHidden(host);
        assertEquals(-1, registry.livingId(source));
    }

    @Test
    public void declaredPassengersWinAndRemovedRelationshipsAreSentOnce() {
        RecordingEntityOutput host = new RecordingEntityOutput();
        SpoofRegistry<Object, Vec3d> registry = new SpoofRegistry<>(host);
        UUID vehicle = UUID.randomUUID();
        UUID declared = UUID.randomUUID();
        UUID inferred = UUID.randomUUID();
        SpoofedEntity vehicleState = SpoofedEntity.create(false, false, true);
        SpoofedEntity declaredState = SpoofedEntity.create(false, false, true);
        SpoofedEntity inferredState = SpoofedEntity.create(false, false, true);
        registry.track(vehicle, vehicleState);
        registry.track(declared, declaredState);
        registry.track(inferred, inferredState);
        Collection<EntityRelationship> relationships = List.of(
            new EntityRelationship(vehicle, null, List.of(declared), null),
            new EntityRelationship(declared, null, List.of(), vehicle),
            new EntityRelationship(inferred, vehicle, List.of(), null));
        registry.applyRelationships(host, relationships);
        assertArrayEquals(new int[] {declaredState.fakeId}, host.passengers.getFirst());
        assertArrayEquals(new int[] {declaredState.fakeId, vehicleState.fakeId}, host.leashes.getFirst());
        registry.applyRelationships(host, relationships);
        assertEquals(1, host.passengers.size());
        registry.applyRelationships(host, (Collection<EntityRelationship>) List.<EntityRelationship>of(
            new EntityRelationship(declared, null, List.of(), null)));
        assertArrayEquals(new int[0], host.passengers.getLast());
        assertArrayEquals(new int[] {declaredState.fakeId, -1}, host.leashes.getLast());
        registry.applyRelationships(host, (Collection<EntityRelationship>) List.<EntityRelationship>of());
        assertEquals(2, host.passengers.size());
        assertEquals(2, host.leashes.size());
    }

    @Test
    public void teardownRetainsUncommittedIdsAndDestroysPlayerLabel() {
        RecordingEntityOutput host = new RecordingEntityOutput();
        SpoofRegistry<Object, Vec3d> registry = new SpoofRegistry<>(host);
        UUID source = UUID.randomUUID();
        SpoofedEntity state = SpoofedEntity.create(true, false, true);
        registry.track(source, state);
        registry.destroyHidden(host);
        assertFalse(registry.contains(source));
        assertEquals(1, registry.size());
        assertArrayEquals(new int[] {state.fakeId, state.labelFakeId}, host.destroyed.getFirst());
        assertEquals(List.of(state.fakeUuid), host.removedPlayers);
        registry.destroyAll(host);
        assertEquals(2, host.destroyed.size());
        registry.commitDestroyed();
        assertEquals(0, registry.size());
        registry.destroyAll(host);
        assertEquals(2, host.destroyed.size());
    }

    @Test
    public void motionSelectsRelativeRotationTeleportAndRotationOnly() {
        RecordingEntityOutput host = new RecordingEntityOutput();
        SpoofRegistry<Object, Vec3d> registry = new SpoofRegistry<>(host);
        SpoofedEntity state = SpoofedEntity.create(false, false, true);
        state.rememberPosition(0.0D, 0.0D, 0.0D);
        registry.syncMotion(host, state, state.updatePosition(1.0D, 0.0D, 0.0D), true, new Vec3d(1, 0, 0), 20, 0, false);
        registry.syncMotion(host, state, state.updatePosition(20.0D, 0.0D, 0.0D), false, new Vec3d(20, 0, 0), 20, 0, false);
        registry.syncMotion(host, state, state.updatePosition(20.0D, 0.0D, 0.0D), true, new Vec3d(20, 0, 0), 30, 0, false);
        registry.syncMotion(host, state, state.updatePosition(20.0D, 0.0D, 0.0D), false, new Vec3d(20, 0, 0), 30, 0, false);
        assertEquals(List.of(SpoofRegistry.MotionKind.RELATIVE_ROTATION,
            SpoofRegistry.MotionKind.TELEPORT, SpoofRegistry.MotionKind.ROTATION), host.motion);
    }
}
