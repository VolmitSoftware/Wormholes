package art.arcane.optics.stream;

import art.arcane.optics.math.Vec3d;
import art.arcane.optics.view.WorldChangeTracker;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import art.arcane.optics.client.MeshPlan;

final class MeshLocalSchedulingTest {
    @Test
    void shrinkingCoverageDuringDirtyScanningRetainsItsGenerationAndRemoteProgress() throws ReflectiveOperationException {
        Fixture fixture = new Fixture();
        fixture.advance(100, true);
        fixture.awaitPartialDirtySweep();
        List<ViewStreamMessage.MeshCoordinate> covered = fixture.coordinates.subList(1, fixture.coordinates.size());
        fixture.coverage(covered, true);
        fixture.advance(20, true);
        fixture.coverage(List.of(fixture.coordinates.getFirst()), true);
        fixture.advance(10, true);
        fixture.coverage(fixture.coordinates, false);
        int received = fixture.received;
        fixture.advance(30, true);

        assertTrue(fixture.received > received);
        assertEquals(1, fixture.generations);
    }

    @Test
    void coverageChangesReleasePendingAndAwaitingEntriesWithoutLosingResumedDelivery() {
        Fixture fixture = new Fixture();
        fixture.advance(4, false);
        fixture.coverage(fixture.coordinates, true);
        fixture.advance(10, true);
        fixture.coverage(fixture.coordinates, false);
        int received = fixture.received;
        fixture.advance(30, true);

        assertTrue(fixture.received > received);
        assertEquals(1, fixture.generations);
    }

    private static final class Fixture {
        private final FakeEndpoints access = new FakeEndpoints(new ArrayList<>());
        private final MeshStream<String> stream = new MeshStream<>();
        private final ViewStreamSlot<String> slot;
        private final Vec3d eye = new Vec3d(11, 67, 15);
        private final List<ViewStreamMessage.MeshCoordinate> coordinates;
        private int tick;
        private int sequence;
        private int generation;
        private int generations;
        private int received;

        private Fixture() {
            SessionPortal portal = access.add(new SessionPortal("local-scan", 0));
            portal.mirror = true;
            access.localWorld = true;
            access.meshChanges = new WorldChangeTracker();
            slot = new ViewStreamSlot<>(portal.id, 1, false);
            slot.geometry = portal.geometry(new SessionPalette()).withDepth(256);
            slot.sentGeometry = slot.geometry;
            slot.announced = true;
            slot.laneAttached = true;
            coordinates = new ArrayList<>();
            UUID world = UUID.randomUUID();
            for (MeshPlan.Section section : MeshPlan.visible(slot.geometry, eye)) {
                coordinates.add(new ViewStreamMessage.MeshCoordinate(section.x(), section.y(), section.z()));
                access.meshPlates.put(section.clip(), PlateTestFixtures.tracked(portal.id, section.clip(), world, 0));
            }
            assertTrue(coordinates.size() > 1);
        }

        private void awaitPartialDirtySweep() throws ReflectiveOperationException {
            Field statesField = MeshStream.class.getDeclaredField("states");
            statesField.setAccessible(true);
            Map<?, ?> states = (Map<?, ?>) statesField.get(stream);
            Object state = states.get(slot.key);
            Field cursor = state.getClass().getDeclaredField("dirtyCursor");
            Field limit = state.getClass().getDeclaredField("dirtyLimit");
            Field residents = state.getClass().getDeclaredField("residents");
            cursor.setAccessible(true);
            limit.setAccessible(true);
            residents.setAccessible(true);
            for (int attempt = 0; attempt < 100; attempt++) {
                if (cursor.getInt(state) > 1 && cursor.getInt(state) < limit.getInt(state)) {
                    assertTrue(((List<?>) residents.get(state)).size() > 1);
                    return;
                }
                advance(1, true);
            }
            throw new AssertionError("The resident dirty sweep must be partway through its unchanged snapshot before coverage shrinks it to one entry");
        }

        private void advance(int count, boolean acknowledge) {
            for (int step = 0; step < count; step++) {
                tick++;
                int calls = access.meshCalls;
                stream.beginTick();
                stream.refresh(slot, access, "observer", tick, tick * SessionHarness.TICK_NANOS, false, eye);
                assertTrue(access.meshCalls - calls <= MeshStream.SECTION_CHECKS_PER_TICK);
                ViewStreamMessage control;
                while ((control = stream.pollControl(key -> true)) != null) {
                    if (control instanceof ViewStreamMessage.MeshBegin begin) {
                        generation = begin.generation();
                        generations++;
                    }
                }
                MeshStream.Ready<String> ready;
                while ((ready = stream.poll(tick * SessionHarness.TICK_NANOS)) != null) {
                    received++;
                    if (acknowledge) {
                        assertTrue(stream.acknowledge(new ViewStreamMessage.MeshAck(slot.key, ready.generation(),
                            ready.coordinate().x(), ready.coordinate().y(), ready.coordinate().z(), ready.revision())));
                    }
                }
            }
        }

        private void coverage(List<ViewStreamMessage.MeshCoordinate> sections, boolean available) {
            for (int start = 0; start < sections.size(); start += ViewStreamMessage.MeshLocal.MAX_SECTIONS) {
                int end = Math.min(sections.size(), start + ViewStreamMessage.MeshLocal.MAX_SECTIONS);
                assertTrue(stream.local(new ViewStreamMessage.MeshLocal(slot.key, generation, ++sequence, available,
                    sections.subList(start, end), List.of())));
            }
        }
    }
}
