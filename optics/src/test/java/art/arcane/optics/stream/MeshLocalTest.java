package art.arcane.optics.stream;

import art.arcane.optics.math.Vec3d;
import art.arcane.optics.entity.EntitySnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import art.arcane.optics.client.MeshPlan;

final class MeshLocalTest {
    @Test
    void cachedClaimsRequireCurrentGenerationSequenceKnownKeyAndValidBounds() {
        Fixture fixture = new Fixture(false, false);
        MeshStream.Ready<String> ready = fixture.nextReady(2, 12);
        ViewStreamMessage.MeshClaim claim = new ViewStreamMessage.MeshClaim(ready.coordinate().x(), ready.coordinate().y(),
            ready.coordinate().z(), 41);
        assertTrue(fixture.stream.cached(new ViewStreamMessage.MeshCached(1, fixture.begin.generation(), 2, true, List.of(claim))));
        assertFalse(fixture.stream.cached(new ViewStreamMessage.MeshCached(1, fixture.begin.generation(), 1, false, List.of(claim))));
        assertFalse(fixture.stream.cached(new ViewStreamMessage.MeshCached(1, fixture.begin.generation(), 2, false, List.of(claim))));
        assertFalse(fixture.stream.cached(new ViewStreamMessage.MeshCached(1, fixture.begin.generation() + 1, 3, false, List.of(claim))));
        assertFalse(fixture.stream.cached(new ViewStreamMessage.MeshCached(99, fixture.begin.generation(), 3, true, List.of(claim))));
        assertFalse(fixture.stream.cached(new ViewStreamMessage.MeshCached(1, fixture.begin.generation(), 3, true,
            List.of(new ViewStreamMessage.MeshClaim(Integer.MAX_VALUE, 0, 0, 41)))));
        assertTrue(fixture.stream.reuse(ready, 41));
        assertFalse(fixture.stream.reuse(ready, 42));
        assertTrue(fixture.stream.cached(new ViewStreamMessage.MeshCached(1, fixture.begin.generation(), 3, false, List.of(claim))));
        assertFalse(fixture.stream.reuse(ready, 41));
    }

    @Test
    void claimRetractionAffectsOnlyTheExactSectionAndReuseRequiresAwaitingAcknowledgement() {
        Fixture fixture = new Fixture(false, false);
        MeshStream.Ready<String> first = fixture.nextReady(2, 12);
        MeshStream.Ready<String> second = fixture.nextReady(14, 12);
        ViewStreamMessage.MeshClaim firstClaim = new ViewStreamMessage.MeshClaim(first.coordinate().x(), first.coordinate().y(),
            first.coordinate().z(), 41);
        ViewStreamMessage.MeshClaim secondClaim = new ViewStreamMessage.MeshClaim(second.coordinate().x(), second.coordinate().y(),
            second.coordinate().z(), 42);
        assertTrue(fixture.stream.cached(new ViewStreamMessage.MeshCached(1, fixture.begin.generation(), 1, true,
            List.of(firstClaim, secondClaim))));
        assertTrue(fixture.stream.reuse(first, 41));
        assertTrue(fixture.stream.reuse(second, 42));
        assertTrue(fixture.stream.cached(new ViewStreamMessage.MeshCached(1, fixture.begin.generation(), 2, false, List.of(firstClaim))));
        assertFalse(fixture.stream.reuse(first, 41));
        assertTrue(fixture.stream.reuse(second, 42));
        assertTrue(fixture.stream.acknowledge(new ViewStreamMessage.MeshAck(1, second.generation(), second.coordinate().x(),
            second.coordinate().y(), second.coordinate().z(), second.revision())));
        assertFalse(fixture.stream.reuse(second, 42));
    }

    @Test
    void cachedClaimsDoNotSurviveNewGenerationsOrRemoval() {
        Fixture fixture = new Fixture(false, false);
        MeshStream.Ready<String> ready = fixture.nextReady(2, 12);
        ViewStreamMessage.MeshClaim claim = new ViewStreamMessage.MeshClaim(ready.coordinate().x(), ready.coordinate().y(),
            ready.coordinate().z(), 41);
        assertTrue(fixture.stream.cached(new ViewStreamMessage.MeshCached(1, fixture.begin.generation(), 1, true, List.of(claim))));
        fixture.slot.geometry = fixture.slot.geometry.withDepth(48);
        fixture.refresh(14);
        ViewStreamMessage.MeshBegin next = (ViewStreamMessage.MeshBegin) fixture.stream.pollControl(key -> true);
        assertTrue(next.generation() > ready.generation());
        assertFalse(fixture.stream.reuse(ready, 41));
        assertFalse(fixture.stream.cached(new ViewStreamMessage.MeshCached(1, ready.generation(), 99, true, List.of(claim))));
        fixture.stream.remove(1);
        assertFalse(fixture.stream.cached(new ViewStreamMessage.MeshCached(1, next.generation(), 1, true, List.of(claim))));
    }

    @Test
    void unavailableViewCannotReuseAnOtherwiseMatchingClaim() {
        Fixture fixture = new Fixture(false, false);
        MeshStream.Ready<String> ready = fixture.nextReady(2, 12);
        ViewStreamMessage.MeshClaim claim = new ViewStreamMessage.MeshClaim(ready.coordinate().x(), ready.coordinate().y(),
            ready.coordinate().z(), 41);
        assertTrue(fixture.stream.cached(new ViewStreamMessage.MeshCached(1, fixture.begin.generation(), 1, true, List.of(claim))));
        assertTrue(fixture.stream.reuse(ready, 41));
        fixture.slot.failed = true;
        assertFalse(fixture.stream.reuse(ready, 41));
        fixture.slot.failed = false;
        fixture.slot.laneAttached = false;
        assertFalse(fixture.stream.reuse(ready, 41));
        fixture.slot.laneAttached = true;
        assertTrue(fixture.stream.reuse(ready, 41));
    }

    @Test
    void availabilityRequiresAnEligibleSameWorldMirror() {
        Fixture portal = new Fixture(false, true);
        assertFalse(portal.stream.local(portal.message(1, true)));
        Fixture remote = new Fixture(true, false);
        assertFalse(remote.stream.local(remote.message(1, true)));
        Fixture local = new Fixture(true, true);
        assertTrue(local.stream.local(local.message(1, true)));
    }

    @Test
    void staleSequencesGenerationsAndOutOfBoundsReportsCannotChangeCoverage() {
        Fixture fixture = new Fixture(true, true);
        UUID id = UUID.randomUUID();
        assertTrue(fixture.stream.local(new ViewStreamMessage.MeshLocal(1, fixture.begin.generation(), 2, true,
            List.of(fixture.coordinate()), List.of(id))));
        assertFalse(fixture.stream.local(fixture.message(1, false)));
        assertFalse(fixture.stream.local(new ViewStreamMessage.MeshLocal(1, fixture.begin.generation() + 1, 3,
            false, List.of(fixture.coordinate()), List.of(id))));
        assertFalse(fixture.stream.local(new ViewStreamMessage.MeshLocal(99, fixture.begin.generation(), 3,
            true, List.of(), List.of(id))));
        assertFalse(fixture.stream.local(new ViewStreamMessage.MeshLocal(1, fixture.begin.generation(), 3,
            true, List.of(new ViewStreamMessage.MeshCoordinate(Integer.MAX_VALUE, 0, 0)), List.of())));
        assertTrue(fixture.stream.localEntity(1, id));
        assertTrue(fixture.stream.local(new ViewStreamMessage.MeshLocal(1, fixture.begin.generation(), 3,
            false, List.of(fixture.coordinate()), List.of(id))));
        assertFalse(fixture.stream.localEntity(1, id));
    }

    @Test
    void retractingOnlyOneLoadedSectionResumesItsRemoteCapture() {
        Fixture fixture = new Fixture(true, true);
        ViewStreamMessage.MeshCoordinate first = fixture.coordinate();
        MeshPlan.Section second = MeshPlan.visible(fixture.slot.geometry, fixture.eye).get(1);
        ViewStreamMessage.MeshCoordinate retained = new ViewStreamMessage.MeshCoordinate(second.x(), second.y(), second.z());
        assertTrue(fixture.stream.local(new ViewStreamMessage.MeshLocal(1, fixture.begin.generation(), 1, true,
            List.of(first, retained), List.of())));
        ArrayList<ViewStreamMessage.MeshCoordinate> before = fixture.collect(2, 12);
        assertFalse(before.contains(first));
        assertFalse(before.contains(retained));
        assertTrue(fixture.stream.local(new ViewStreamMessage.MeshLocal(1, fixture.begin.generation(), 2, false,
            List.of(first), List.of())));
        ArrayList<ViewStreamMessage.MeshCoordinate> after = fixture.collect(14, 16);
        assertTrue(after.contains(first));
        assertFalse(after.contains(retained));
    }

    @Test
    void losingWorldEligibilityRestoresRemoteDeliveryAndEntityPresence() {
        Fixture fixture = new Fixture(true, true);
        UUID id = UUID.randomUUID();
        assertTrue(fixture.stream.local(new ViewStreamMessage.MeshLocal(1, fixture.begin.generation(), 1, true,
            List.of(fixture.coordinate()), List.of(id))));
        fixture.access.localWorld = false;
        ArrayList<ViewStreamMessage.MeshCoordinate> remote = fixture.collect(2, 16);
        assertTrue(remote.contains(fixture.coordinate()));
        assertFalse(fixture.stream.localEntity(1, id));
        assertFalse(fixture.stream.local(fixture.message(2, true)));
    }

    @Test
    void entitySuppressionIsExplicitPerUuidAndPerView() {
        Fixture fixture = new Fixture(true, true);
        UUID local = UUID.randomUUID();
        UUID remote = UUID.randomUUID();
        EntitySnapshot localVisual = visual(local);
        EntitySnapshot remoteVisual = visual(remote);
        assertTrue(fixture.stream.local(new ViewStreamMessage.MeshLocal(1, fixture.begin.generation(), 1, true,
            List.of(), List.of(local))));
        ViewStreamMessage.EntityFrame incoming = new ViewStreamMessage.EntityFrame(1, 7,
            List.of(localVisual, remoteVisual), List.of(local, remote), true);
        ViewStreamMessage.EntityFrame filtered = fixture.stream.localEntities(incoming);
        assertEquals(List.of(remoteVisual), filtered.entities());
        assertEquals(List.of(remote), filtered.presentIds());
        assertEquals(7, filtered.entitySeq());
        assertTrue(filtered.presence());
        ViewStreamMessage.EntityFrame other = new ViewStreamMessage.EntityFrame(2, 7, incoming.entities(), incoming.presentIds(), true);
        assertSame(other, fixture.stream.localEntities(other));
        assertFalse(fixture.stream.localEntity(2, local));
        assertTrue(fixture.stream.local(new ViewStreamMessage.MeshLocal(1, fixture.begin.generation(), 2, false,
            List.of(), List.of(local))));
        assertSame(incoming, fixture.stream.localEntities(incoming));
    }

    @Test
    void aNewGenerationAndRemovalRetireAllPreviousLocalClaims() {
        Fixture fixture = new Fixture(true, true);
        UUID id = UUID.randomUUID();
        assertTrue(fixture.stream.local(new ViewStreamMessage.MeshLocal(1, fixture.begin.generation(), 1, true,
            List.of(), List.of(id))));
        fixture.slot.geometry = fixture.slot.geometry.withDepth(48);
        fixture.refresh(2);
        ViewStreamMessage.MeshBegin next = (ViewStreamMessage.MeshBegin) fixture.stream.pollControl(key -> true);
        assertTrue(next.generation() > fixture.begin.generation());
        assertFalse(fixture.stream.localEntity(1, id));
        assertFalse(fixture.stream.local(new ViewStreamMessage.MeshLocal(1, fixture.begin.generation(), 99, true,
            List.of(), List.of(id))));
        assertTrue(fixture.stream.local(new ViewStreamMessage.MeshLocal(1, next.generation(), 1, true,
            List.of(), List.of(id))));
        fixture.stream.remove(1);
        assertFalse(fixture.stream.localEntity(1, id));
        assertFalse(fixture.stream.local(new ViewStreamMessage.MeshLocal(1, next.generation(), 2, true,
            List.of(), List.of(id))));
    }

    private static EntitySnapshot visual(UUID id) {
        return new EntitySnapshot(EntitySnapshot.MODE_FULL, 0, EntitySnapshot.FIELD_ALL_FULL, id, "minecraft:armor_stand", 11, 67, 20,
            1.975, 0, 0, -1, 180, 0, 0, 0, 0, true, "", "", "", null, null,
            EntitySnapshot.EMPTY, EntitySnapshot.EMPTY, EntitySnapshot.EMPTY);
    }

    private static final class Fixture {
        private final FakeEndpoints access = new FakeEndpoints(new ArrayList<>());
        private final MeshStream<String> stream = new MeshStream<>();
        private final ViewStreamSlot<String> slot;
        private final Vec3d eye = new Vec3d(11, 67, 15);
        private final ViewStreamMessage.MeshBegin begin;

        private Fixture(boolean mirror, boolean local) {
            SessionPortal portal = access.add(new SessionPortal("local-coverage", 0));
            portal.mirror = mirror;
            access.localWorld = local;
            slot = new ViewStreamSlot<>(portal.id, 1, false);
            slot.geometry = portal.geometry(new SessionPalette());
            slot.sentGeometry = slot.geometry;
            slot.announced = true;
            slot.laneAttached = true;
            refresh(1);
            begin = (ViewStreamMessage.MeshBegin) stream.pollControl(key -> true);
        }

        private ViewStreamMessage.MeshCoordinate coordinate() {
            MeshPlan.Section section = MeshPlan.visible(slot.geometry, eye).getFirst();
            return new ViewStreamMessage.MeshCoordinate(section.x(), section.y(), section.z());
        }

        private ViewStreamMessage.MeshLocal message(int sequence, boolean available) {
            return new ViewStreamMessage.MeshLocal(1, begin.generation(), sequence, available, List.of(coordinate()), List.of());
        }

        private void refresh(int tick) {
            stream.beginTick();
            stream.refresh(slot, access, "observer", tick, tick * SessionHarness.TICK_NANOS, false, eye);
        }

        private ArrayList<ViewStreamMessage.MeshCoordinate> collect(int start, int count) {
            ArrayList<ViewStreamMessage.MeshCoordinate> received = new ArrayList<>();
            for (int tick = start; tick < start + count; tick++) {
                refresh(tick);
                MeshStream.Ready<String> ready;
                while ((ready = stream.poll(tick * SessionHarness.TICK_NANOS)) != null) {
                    received.add(new ViewStreamMessage.MeshCoordinate(ready.coordinate().x(), ready.coordinate().y(), ready.coordinate().z()));
                    assertTrue(stream.acknowledge(new ViewStreamMessage.MeshAck(ready.slot().key, ready.generation(),
                        ready.coordinate().x(), ready.coordinate().y(), ready.coordinate().z(), ready.revision())));
                }
            }
            return received;
        }

        private MeshStream.Ready<String> nextReady(int start, int count) {
            for (int tick = start; tick < start + count; tick++) {
                refresh(tick);
                MeshStream.Ready<String> ready = stream.poll(tick * SessionHarness.TICK_NANOS);
                if (ready != null) {
                    return ready;
                }
            }
            throw new AssertionError("No section became ready within the capture window");
        }
    }
}
