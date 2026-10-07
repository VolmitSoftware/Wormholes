package art.arcane.optics.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.ArrayDeque;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.view.WorldChangeTracker;
import art.arcane.optics.math.BlockBox;
import art.arcane.optics.plate.ViewPlate;
import java.util.UUID;
import art.arcane.optics.client.MeshPlan;

final class MeshStreamTest {
    @Test
    void cachedViewsShareReadyReservationsWithoutChargingThePhysicalCaptureBudget() {
        FakeEndpoints access = new FakeEndpoints(new ArrayList<String>());
        ArrayList<ViewStreamSlot<String>> slots = new ArrayList<ViewStreamSlot<String>>();
        for (int key = 1; key <= 2; key++) {
            SessionPortal portal = access.add(new SessionPortal("cached-" + key, key * 32));
            ViewStreamSlot<String> slot = new ViewStreamSlot<String>(portal.id, key, false);
            slot.geometry = portal.geometry(new SessionPalette()).withDepth(512);
            slot.sentGeometry = slot.geometry;
            slot.announced = true;
            slot.laneAttached = true;
            slots.add(slot);
        }
        MeshStream<String> stream = new MeshStream<String>();
        Vec3d eye = new Vec3d(11, 67, 15);
        int[] received = new int[3];
        for (int tick = 1; tick <= 11; tick++) {
            int calls = access.meshCalls;
            stream.beginTick();
            for (ViewStreamSlot<String> slot : slots) {
                stream.refresh(slot, access, "observer", tick, tick * SessionHarness.TICK_NANOS, false, eye);
            }
            while (stream.pollControl(key -> true) != null) {
            }
            MeshStream.Ready<String> section;
            while ((section = stream.poll(tick * SessionHarness.TICK_NANOS)) != null) {
                if (tick > 1) {
                    received[section.slot().key]++;
                }
                acknowledge(stream, section);
            }
            assertTrue(access.meshCalls - calls <= MeshStream.SECTION_CHECKS_PER_TICK);
        }
        assertTrue(received[1] >= 80, "the first view must continue delivering cached sections");
        assertTrue(received[2] >= 80, "the second view must receive its fair reservation share");
    }

    @Test
    void unannouncedBranchDoesNotHoldReadyRootSectionsBehindItsBegin() {
        FakeEndpoints access = new FakeEndpoints(new ArrayList<String>());
        SessionPortal blockedPortal = access.add(new SessionPortal("blocked", 0));
        SessionPortal readyPortal = access.add(new SessionPortal("ready", 0));
        ViewStreamSlot<String> blocked = new ViewStreamSlot<String>(blockedPortal.id, 1, false);
        blocked.geometry = blockedPortal.geometry(new SessionPalette());
        blocked.sentGeometry = blocked.geometry;
        blocked.laneAttached = true;
        ViewStreamSlot<String> root = new ViewStreamSlot<String>(readyPortal.id, 2, false);
        root.geometry = readyPortal.geometry(new SessionPalette());
        root.sentGeometry = root.geometry;
        root.laneAttached = true;
        root.announced = true;
        Vec3d eye = new Vec3d(11, 67, 15);
        MeshStream<String> stream = new MeshStream<String>();
        stream.beginTick();
        stream.refresh(blocked, access, "observer", 1, SessionHarness.TICK_NANOS, false, eye);
        stream.refresh(root, access, "observer", 1, SessionHarness.TICK_NANOS, false, eye);
        assertTrue(stream.pollControl(key -> key == root.key) instanceof ViewStreamMessage.MeshBegin begin && begin.portalKey() == root.key);
        stream.beginTick();
        stream.refresh(root, access, "observer", 2, SessionHarness.TICK_NANOS * 2, false, eye);
        MeshStream.Ready<String> first = stream.poll(SessionHarness.TICK_NANOS * 2);
        assertTrue(first != null);
        assertEquals(root.key, first.slot().key);
        assertEquals(MeshPlan.visible(root.geometry, eye).getFirst().coordinate(), first.coordinate());
        acknowledge(stream, first);
        MeshStream.Ready<String> next;
        while ((next = stream.poll(SessionHarness.TICK_NANOS * 2)) != null) {
            assertEquals(root.key, next.slot().key);
            acknowledge(stream, next);
        }
        blocked.announced = true;
        assertTrue(stream.poll(SessionHarness.TICK_NANOS * 2) == null, "section must wait for its own MeshBegin");
        assertTrue(stream.pollControl(key -> true) instanceof ViewStreamMessage.MeshBegin begin && begin.portalKey() == blocked.key);
        next = stream.poll(SessionHarness.TICK_NANOS * 2);
        assertTrue(next != null);
        assertEquals(blocked.key, next.slot().key);
        assertTrue(access.meshCalls <= 2 * MeshStream.SECTION_CHECKS_PER_TICK);
    }

    @Test
    void nativeMirrorUsesSectionStreamingWithoutLegacyMirrorCapabilityOrPlate() throws ViewStreamProtocolException {
        SessionHarness harness = new SessionHarness(SessionHarness.options(false, 0));
        harness.access.meshDistance = 208;
        SessionPortal mirror = harness.access.add(new SessionPortal("native-mirror", 0));
        mirror.mirror = true;
        mirror.refused = true;
        harness.handshake(SessionHarness.NATIVE_CAPS & ~ViewStreamCapability.CLIENT_MIRROR.mask());
        for (int tick = 0; tick < 8; tick++) {
            harness.tick();
        }
        assertTrue(harness.session.owns(mirror.id));
        ViewStreamMessage.Portal portal = (ViewStreamMessage.Portal) harness.last(ViewStreamMessageType.PORTAL);
        assertTrue(portal.geometry().mirror());
        assertEquals(208, portal.geometry().depthBlocks());
        assertEquals(MeshStream.MAX_IN_FLIGHT, harness.sent(ViewStreamMessageType.MESH_SECTION));
        assertTrue(harness.access.plateRequested.isEmpty());
        assertTrue(harness.access.refusalChecked.isEmpty());
        ViewStreamMessage.MeshSection section = (ViewStreamMessage.MeshSection) harness.last(ViewStreamMessageType.MESH_SECTION);
        assertEquals(ViewStreamInbound.HANDLED, harness.c2s(ViewStreamFixtures.CODEC.encodeC2S(ack(section))));
        harness.tick();
        assertEquals(MeshStream.MAX_IN_FLIGHT + 1, harness.sent(ViewStreamMessageType.MESH_SECTION));
    }

    @Test
    void fullSpeedMeshAndFrameAcknowledgementsKeepTheSessionActive() throws ViewStreamProtocolException {
        SessionHarness harness = new SessionHarness(SessionHarness.options(false, ViewStreamLimits.DEFAULT_ACK_WINDOW_FRAMES));
        harness.c2sSpacingNanos = 0L;
        harness.client.autoAck = true;
        harness.access.meshDistance = 512;
        harness.access.add(new SessionPortal("full-speed-mesh", 0));
        harness.handshake(SessionHarness.NATIVE_CAPS);
        int cursor = 0;
        int acknowledged = 0;
        for (int tick = 0; tick < 40; tick++) {
            harness.tick();
            while (cursor < harness.client.received.size()) {
                ViewStreamMessage message = harness.client.received.get(cursor++);
                if (message instanceof ViewStreamMessage.MeshSection section) {
                    assertEquals(ViewStreamInbound.HANDLED, harness.c2s(ViewStreamFixtures.CODEC.encodeC2S(ack(section))));
                    acknowledged++;
                }
            }
            harness.pump();
            assertEquals(ViewStreamSessionState.CLIENT_VIEW, harness.session.state(), "session reset on tick " + tick);
            assertEquals(0L, harness.session.stats().c2sDropped());
        }
        assertTrue(acknowledged >= 32 * MeshStream.MAX_PENDING_CAPTURES, "cached section streaming must sustain its bounded reservations");
        assertEquals(0, harness.session.stats().outstandingGroups());
        assertTrue(harness.warnings.isEmpty(), harness.warnings.toString());
    }

    @ParameterizedTest
    @CsvSource({"false, 0", "true, 0", "false, 128", "true, 128"})
    void dirtyResidentRefreshesDuringInitialCoverageAndWaitsForReplacement(boolean reclaimed, int targetIndex) throws ReflectiveOperationException {
        FakeEndpoints access = new FakeEndpoints(new ArrayList<String>());
        access.meshChanges = new WorldChangeTracker();
        SessionPortal portal = access.add(new SessionPortal("dirty-refresh", 0));
        ViewStreamSlot<String> slot = new ViewStreamSlot<String>(portal.id, 1, false);
        slot.geometry = portal.geometry(new SessionPalette()).withDepth(512);
        slot.sentGeometry = slot.geometry;
        slot.announced = true;
        slot.laneAttached = true;
        Vec3d eye = new Vec3d(11, 67, 15);
        List<MeshPlan.Section> visible = MeshPlan.visible(slot.geometry, eye);
        MeshPlan.Section target = visible.get(targetIndex);
        BlockBox clip = target.clip();
        UUID world = UUID.randomUUID();
        UUID unchangedWorld = targetIndex == 0 ? world : UUID.randomUUID();
        for (int index = 0; index < Math.min(512, visible.size()); index++) {
            BlockBox resident = visible.get(index).clip();
            access.meshPlates.put(resident, PlateTestFixtures.tracked(portal.id, resident, unchangedWorld, 0));
        }
        ViewPlate<String> previous = PlateTestFixtures.tracked(portal.id, clip, world, 0);
        access.meshPlates.put(clip, previous);
        MeshStream<String> stream = new MeshStream<String>();
        Set<MeshPlan.Coordinate> received = new HashSet<MeshPlan.Coordinate>();
        int tick = 1;
        for (; tick <= 40; tick++) {
            MeshStream.Ready<String> section = capture(stream, slot, access, eye, tick);
            while (section != null) {
                received.add(section.coordinate());
                acknowledge(stream, section);
                section = stream.poll(tick * SessionHarness.TICK_NANOS);
            }
        }
        assertTrue(received.contains(target.coordinate()));
        assertTrue(received.size() < visible.size(), "initial coverage must still be loading");
        if (reclaimed || targetIndex == 0) {
            Field statesField = MeshStream.class.getDeclaredField("states");
            statesField.setAccessible(true);
            Map<?, ?> states = (Map<?, ?>) statesField.get(stream);
            Object state = states.get(slot.key);
            if (targetIndex == 0) {
                Field cursorField = state.getClass().getDeclaredField("dirtyCursor");
                cursorField.setAccessible(true);
                cursorField.setInt(state, 100);
                Field limitField = state.getClass().getDeclaredField("dirtyLimit");
                limitField.setAccessible(true);
                limitField.setInt(state, received.size());
            }
            Field entriesField = state.getClass().getDeclaredField("entries");
            entriesField.setAccessible(true);
            Map<?, ?> entries = (Map<?, ?>) entriesField.get(state);
            Object entry = entries.get(target.coordinate());
            Field previousField = entry.getClass().getDeclaredField("previous");
            previousField.setAccessible(true);
            WeakReference<?> reference = (WeakReference<?>) previousField.get(entry);
            if (reclaimed) {
                reference.clear();
            }
        }
        access.meshRequests.clear();
        access.meshChanges.markChanged(world, clip.minX(), clip.minY(), clip.minZ());
        if (targetIndex == 0) {
            for (BlockBox resident : access.meshPlates.keySet()) {
                access.meshChanges.markChanged(world, resident.minX(), resident.minY(), resident.minZ());
            }
        }
        previous.refreshDirt(access.meshChanges);
        int dirtyTick = tick;
        while (!access.meshRequests.contains(clip) && tick < dirtyTick + 12) {
            MeshStream.Ready<String> section = capture(stream, slot, access, eye, tick);
            while (section != null) {
                assertFalse(section.coordinate().equals(target.coordinate()), "the dirty cached snapshot must not be resent");
                acknowledge(stream, section);
                section = stream.poll(tick * SessionHarness.TICK_NANOS);
            }
            tick++;
        }
        assertTrue(access.meshRequests.contains(clip), "dirty resident must bypass the long ordinary refresh sweep");
        ViewPlate<String> replacement = PlateTestFixtures.tracked(portal.id, clip, world, access.meshChanges.currentVersion());
        access.meshPlates.put(clip, replacement);
        boolean updated = false;
        for (int deadline = tick + 4; tick < deadline && !updated; tick++) {
            int calls = access.meshCalls;
            MeshStream.Ready<String> section = capture(stream, slot, access, eye, tick);
            while (section != null) {
                if (section.coordinate().equals(target.coordinate())) {
                    assertTrue(section.plate() == replacement);
                    updated = true;
                }
                acknowledge(stream, section);
                section = stream.poll(tick * SessionHarness.TICK_NANOS);
            }
            assertTrue(access.meshCalls - calls <= MeshStream.SECTION_CHECKS_PER_TICK);
        }
        assertTrue(updated, "replacement must stay on the pending capture path");
    }

    @Test
    void completedCoverageKeepsRefreshingAcrossCursorWraps() {
        FakeEndpoints access = new FakeEndpoints(new ArrayList<String>());
        SessionPortal portal = access.add(new SessionPortal("refresh", 0));
        ViewStreamSlot<String> slot = new ViewStreamSlot<String>(portal.id, 1, false);
        slot.geometry = portal.geometry(new SessionPalette());
        slot.sentGeometry = slot.geometry;
        slot.announced = true;
        slot.laneAttached = true;
        MeshStream<String> stream = new MeshStream<String>();
        Vec3d eye = new Vec3d(11, 67, 15);
        int sectionCount = MeshPlan.visible(slot.geometry, eye).size();
        assertTrue(sectionCount > MeshStream.MAX_PENDING_CAPTURES);

        for (int step = 1; step <= sectionCount * 3; step++) {
            int callsBefore = access.meshCalls;
            int tick = step * 20;
            MeshStream.Ready<String> section = capture(stream, slot, access, eye, tick);
            while (section != null) {
                acknowledge(stream, section);
                section = stream.poll(tick * SessionHarness.TICK_NANOS);
            }
            assertEquals(MeshStream.MAX_PENDING_CAPTURES, access.meshCalls - callsBefore,
                "eligible refresh work must continue when the cursor wraps");
        }
    }

    @Test
    void movingEyeDoesNotRestartResidentRefreshAtTheNearestSections() {
        FakeEndpoints access = new FakeEndpoints(new ArrayList<String>());
        SessionPortal portal = access.add(new SessionPortal("moving-refresh", 0));
        ViewStreamSlot<String> slot = new ViewStreamSlot<String>(portal.id, 1, false);
        slot.geometry = portal.geometry(new SessionPalette()).withDepth(512);
        slot.sentGeometry = slot.geometry;
        slot.announced = true;
        slot.laneAttached = true;
        MeshStream<String> stream = new MeshStream<String>();
        Vec3d eye = new Vec3d(11, 67, 15);
        Vec3d moved = new Vec3d(12, 67, 15);
        ArrayList<MeshPlan.Coordinate> residents = new ArrayList<MeshPlan.Coordinate>();
        Set<MeshPlan.Coordinate> movedVisible = new HashSet<MeshPlan.Coordinate>();
        for (MeshPlan.Section section : MeshPlan.visible(slot.geometry, moved)) {
            movedVisible.add(section.coordinate());
        }
        for (int step = 1; step <= 64; step++) {
            int tick = step * 20;
            MeshStream.Ready<String> section = capture(stream, slot, access, eye, tick);
            while (section != null) {
                residents.add(section.coordinate());
                acknowledge(stream, section);
                section = stream.poll(tick * SessionHarness.TICK_NANOS);
            }
        }
        assertTrue(residents.size() > 128);
        MeshPlan.Coordinate changed = null;
        for (int index = residents.size() - 1; index >= 128; index--) {
            if (movedVisible.contains(residents.get(index))) {
                changed = residents.get(index);
                break;
            }
        }
        assertTrue(changed != null);
        access.meshPlates.remove(new MeshPlan.Section(changed.x(), changed.y(), changed.z(), 0).clip());
        boolean refreshed = false;
        for (int step = 65; step <= 320 && !refreshed; step++) {
            int tick = step * 20;
            MeshStream.Ready<String> section = capture(stream, slot, access, step % 2 == 0 ? eye : moved, tick);
            while (section != null) {
                refreshed |= section.coordinate().equals(changed);
                acknowledge(stream, section);
                section = stream.poll(tick * SessionHarness.TICK_NANOS);
            }
        }
        assertTrue(refreshed, "a changed resident visible from both eye positions must refresh while the eye keeps moving");
    }

    @Test
    void movingEyeProgressesWhileRetainingResidentSections() {
        FakeEndpoints access = new FakeEndpoints(new ArrayList<String>());
        SessionPortal portal = access.add(new SessionPortal("progress", 0));
        ViewStreamSlot<String> slot = new ViewStreamSlot<String>(portal.id, 1, false);
        slot.geometry = portal.geometry(new SessionPalette()).withDepth(208);
        slot.sentGeometry = slot.geometry;
        slot.announced = true;
        slot.laneAttached = true;
        MeshStream<String> stream = new MeshStream<String>();
        Set<MeshPlan.Coordinate> received = new HashSet<MeshPlan.Coordinate>();
        for (int tick = 1; tick <= 100; tick++) {
            Vec3d eye = new Vec3d(11 + (tick / 8 % 2), 67, 15);
            stream.beginTick();
            stream.refresh(slot, access, "observer", tick, tick * SessionHarness.TICK_NANOS, false, eye);
            ViewStreamMessage control;
            while ((control = stream.pollControl(key -> true)) != null) {
                if (control instanceof ViewStreamMessage.MeshDrop drop) {
                    received.remove(new MeshPlan.Coordinate(drop.sectionX(), drop.sectionY(), drop.sectionZ()));
                }
            }
            MeshStream.Ready<String> section;
            while ((section = stream.poll(tick * SessionHarness.TICK_NANOS)) != null) {
                assertTrue(received.add(section.coordinate()), "initial coverage must precede refresh work");
                assertTrue(stream.acknowledge(new ViewStreamMessage.MeshAck(1, section.generation(), section.coordinate().x(),
                    section.coordinate().y(), section.coordinate().z(), section.revision())));
            }
        }
        assertFalse(received.isEmpty());
        assertEquals(100 * MeshStream.MAX_PENDING_CAPTURES, access.meshCalls);
    }

    @Test
    void clientDistanceStreamsBoundedSectionsAndWaitsForTheirOwnAcks() throws ViewStreamProtocolException {
        SessionHarness harness = meshHarness();
        for (int tick = 0; tick < 8; tick++) {
            harness.tick();
        }
        assertEquals(MeshStream.MAX_IN_FLIGHT, harness.sent(ViewStreamMessageType.MESH_SECTION));
        assertEquals(0, harness.sent(ViewStreamMessageType.PLATE_BEGIN));
        assertTrue(harness.access.plateRequested.isEmpty());
        ViewStreamMessage.Portal portal = (ViewStreamMessage.Portal) harness.last(ViewStreamMessageType.PORTAL);
        assertEquals(512, portal.geometry().depthBlocks());
        ViewStreamMessage.MeshBegin begin = (ViewStreamMessage.MeshBegin) harness.last(ViewStreamMessageType.MESH_BEGIN);
        assertEquals(513, begin.bounds().sizeZ());
        assertEquals(MeshPlan.capacity(portal.geometry()), begin.maxResidentSections());
        ViewStreamMessage.MeshSection section = (ViewStreamMessage.MeshSection) harness.last(ViewStreamMessageType.MESH_SECTION);
        assertEquals(ViewStreamInbound.HANDLED, harness.c2s(ViewStreamFixtures.CODEC.encodeC2S(ack(section))));
        harness.tick();
        assertEquals(MeshStream.MAX_IN_FLIGHT + 1, harness.sent(ViewStreamMessageType.MESH_SECTION));
        assertTrue(harness.warnings.isEmpty(), harness.warnings.toString());
    }

    @Test
    void retargetDropsOldGenerationCreditsAndIgnoresLateAcks() throws ViewStreamProtocolException {
        SessionHarness harness = meshHarness();
        harness.tick();
        ViewStreamMessage.MeshSection old = (ViewStreamMessage.MeshSection) harness.last(ViewStreamMessageType.MESH_SECTION);
        harness.access.meshDistance = 256;
        harness.tick();
        ViewStreamMessage.MeshSection replacement = (ViewStreamMessage.MeshSection) harness.last(ViewStreamMessageType.MESH_SECTION);
        assertTrue(replacement.generation() > old.generation());
        assertEquals(ViewStreamInbound.IGNORED, harness.c2s(ViewStreamFixtures.CODEC.encodeC2S(ack(old))));
        assertEquals(ViewStreamInbound.HANDLED, harness.c2s(ViewStreamFixtures.CODEC.encodeC2S(ack(replacement))));
        assertTrue(harness.warnings.isEmpty(), harness.warnings.toString());
    }

    @Test
    void failedEncodingOfRetiredGenerationDoesNotRejectItsReplacement() throws ViewStreamProtocolException {
        SessionHarness harness = meshHarness();
        harness.access.light = (sectionX, sectionY, sectionZ, block, sky) -> {
            harness.access.light = BrickLightSource.NONE;
            harness.access.meshDistance = 256;
            harness.session.tick(++harness.serverTick);
            throw new IllegalStateException("retired section encoding failed");
        };
        harness.tick();
        harness.tick();

        assertEquals(ViewStreamSessionState.CLIENT_VIEW, harness.session.state());
        assertTrue(harness.session.owns(harness.access.portals.keySet().iterator().next()));
        assertTrue(harness.warnings.isEmpty(), harness.warnings.toString());
        ViewStreamMessage.MeshSection replacement = (ViewStreamMessage.MeshSection) harness.last(ViewStreamMessageType.MESH_SECTION);
        assertTrue(replacement.generation() > 1);
    }

    @Test
    void unavailableSectionsKeepCaptureWorkBounded() throws ViewStreamProtocolException {
        SessionHarness harness = meshHarness();
        harness.access.meshReady = false;
        for (int tick = 0; tick < 5; tick++) {
            harness.tick();
        }
        assertEquals(0, harness.sent(ViewStreamMessageType.MESH_SECTION));
        assertTrue(harness.access.meshCalls <= 5 * MeshStream.SECTION_CHECKS_PER_TICK);
        harness.access.meshReady = true;
        harness.tick();
        assertEquals(MeshStream.MAX_PENDING_CAPTURES, harness.sent(ViewStreamMessageType.MESH_SECTION));
        assertFalse(harness.access.meshPlates.isEmpty());
    }

    @Test
    void stalledNearCapturesDoNotBlockOtherVisibleSections() throws ViewStreamProtocolException {
        SessionHarness harness = meshHarness();
        SessionPortal portal = harness.access.portals.values().iterator().next();
        List<MeshPlan.Section> order = MeshPlan.visible(portal.geometry(new SessionPalette()).withDepth(512), harness.access.eye);
        harness.access.unavailableMesh.add(order.get(0).clip());
        harness.access.unavailableMesh.add(order.get(1).clip());
        for (int tick = 0; tick < 8; tick++) {
            int before = harness.access.meshCalls;
            harness.tick();
            assertTrue(harness.access.meshCalls - before <= MeshStream.SECTION_CHECKS_PER_TICK);
        }
        assertTrue(harness.sent(ViewStreamMessageType.MESH_SECTION) > 4);
        assertTrue(harness.warnings.isEmpty(), harness.warnings.toString());
    }

    @Test
    void residentChangesRefreshWhileInitialCoverageIsStillLoading() {
        FakeEndpoints access = new FakeEndpoints(new ArrayList<String>());
        SessionPortal portal = access.add(new SessionPortal("refresh-during-load", 0));
        ViewStreamSlot<String> slot = new ViewStreamSlot<String>(portal.id, 1, false);
        slot.geometry = portal.geometry(new SessionPalette()).withDepth(512);
        slot.sentGeometry = slot.geometry;
        slot.announced = true;
        slot.laneAttached = true;
        MeshStream<String> stream = new MeshStream<String>();
        Vec3d eye = new Vec3d(11, 67, 15);
        MeshStream.Ready<String> first = capture(stream, slot, access, eye, 1);
        acknowledge(stream, first);
        MeshStream.Ready<String> next;
        while ((next = stream.poll(SessionHarness.TICK_NANOS)) != null) {
            acknowledge(stream, next);
        }
        access.meshPlates.clear();
        boolean refreshed = false;
        for (int tick = 2; tick <= 80; tick++) {
            next = capture(stream, slot, access, eye, tick);
            while (next != null) {
                if (next.coordinate().equals(first.coordinate())) {
                    assertTrue(next.revision() > first.revision());
                    refreshed = true;
                }
                acknowledge(stream, next);
                next = stream.poll(tick * SessionHarness.TICK_NANOS);
            }
        }
        assertTrue(refreshed);
        assertTrue(access.meshPlates.size() < MeshPlan.visible(slot.geometry, eye).size());
    }

    @Test
    void identicalRebuiltSectionsDoNotResendTheirPayload() throws ViewStreamProtocolException {
        SessionHarness harness = meshHarness();
        harness.access.meshDistance = 16;
        for (int tick = 0; tick < 120; tick++) {
            harness.tick();
            for (ViewStreamMessage message : List.copyOf(harness.client.received)) {
                if (message instanceof ViewStreamMessage.MeshSection section) {
                    harness.c2s(ViewStreamFixtures.CODEC.encodeC2S(ack(section)));
                }
            }
            harness.client.received.removeIf(message -> message instanceof ViewStreamMessage.MeshSection);
        }
        harness.access.meshPlates.clear();
        for (int tick = 0; tick < 80; tick++) {
            harness.tick();
        }
        assertEquals(0, harness.sent(ViewStreamMessageType.MESH_SECTION));
        assertTrue(harness.warnings.isEmpty(), harness.warnings.toString());
    }

    @Test
    void mirrorAndItsChildBothReceiveIndependentSectionStreams() throws ViewStreamProtocolException {
        SessionHarness harness = new SessionHarness(SessionHarness.options(false, 0));
        harness.access.meshDistance = 64;
        SessionPortal parent = harness.access.add(new SessionPortal("mirror", 0));
        parent.mirror = true;
        parent.recursionDepth = 1;
        SessionPortal child = new SessionPortal("child", 8);
        harness.access.nested.put(parent.id, List.of(child));
        harness.handshake(SessionHarness.NATIVE_CAPS);
        for (int tick = 0; tick < 4; tick++) {
            harness.tick();
        }
        Set<Integer> streamed = new HashSet<Integer>();
        for (ViewStreamMessage message : harness.client.received) {
            if (message instanceof ViewStreamMessage.MeshSection section) {
                streamed.add(section.portalKey());
            }
        }
        assertEquals(2, streamed.size());
        assertEquals(2, harness.client.portals.size());
        assertEquals(0, harness.sent(ViewStreamMessageType.PLATE_BEGIN));
        assertTrue(harness.warnings.isEmpty(), harness.warnings.toString());
    }

    @Test
    void descendantTopologyChangesRetainTheParentsMeshGenerationAndPendingSections() {
        FakeEndpoints access = new FakeEndpoints(new ArrayList<String>());
        SessionPortal portal = access.add(new SessionPortal("stable parent", 0));
        ViewStreamSlot<String> slot = new ViewStreamSlot<String>(portal.id, 1, false);
        ApertureDescriptor base = portal.geometry(new SessionPalette()).withDepth(512);
        slot.geometry = base;
        slot.sentGeometry = slot.geometry;
        slot.announced = true;
        slot.laneAttached = true;
        MeshStream<String> stream = new MeshStream<String>();
        MeshStream.Ready<String> retained = removableCapture(stream, slot, access);
        ApertureDescriptor first = base.withParent(1);
        ApertureDescriptor second = base.withParent(2);
        List<ApertureDescriptor> changes = List.of(base.withNested(List.of(first)),
            base.withNested(List.of(first.withNested(List.of(second)), second)),
            base.withNested(List.of(second, first)), base);
        for (int index = 0; index < changes.size(); index++) {
            slot.geometry = changes.get(index);
            slot.sentGeometry = slot.geometry;
            stream.beginTick();
            stream.refresh(slot, access, "observer", 1000 + index, (1000 + index) * SessionHarness.TICK_NANOS,
                false, new Vec3d(11, 67, 15));
            assertTrue(stream.current(retained));
            ViewStreamMessage control;
            while ((control = stream.pollControl(key -> true)) != null) {
                assertFalse(control instanceof ViewStreamMessage.MeshBegin);
                assertFalse(control instanceof ViewStreamMessage.MeshDrop);
            }
        }
        slot.geometry = base.withDepth(256);
        slot.sentGeometry = slot.geometry;
        stream.beginTick();
        stream.refresh(slot, access, "observer", 1004, 1004 * SessionHarness.TICK_NANOS,
            false, new Vec3d(11, 67, 15));
        assertFalse(stream.current(retained));
        assertTrue(stream.pollControl(key -> true) instanceof ViewStreamMessage.MeshBegin begin
            && begin.generation() > retained.generation());
    }

    @Test
    void movingEyeRetainsSectionsAndPendingEncodingUntilAcknowledged() {
        FakeEndpoints access = new FakeEndpoints(new ArrayList<String>());
        SessionPortal portal = access.add(new SessionPortal("retention", 0));
        ViewStreamSlot<String> slot = new ViewStreamSlot<String>(portal.id, 1, false);
        slot.geometry = portal.geometry(new SessionPalette()).withDepth(512);
        slot.sentGeometry = slot.geometry;
        slot.announced = true;
        slot.laneAttached = true;
        MeshStream<String> stream = new MeshStream<String>();
        MeshStream.Ready<String> retained = removableCapture(stream, slot, access);
        assertTrue(stream.current(retained));
        stream.beginTick();
        stream.refresh(slot, access, "observer", 1000, 1000 * SessionHarness.TICK_NANOS,
            false, new Vec3d(150, 67, 15));
        ViewStreamMessage control;
        while ((control = stream.pollControl(key -> true)) != null) {
            assertFalse(control instanceof ViewStreamMessage.MeshDrop);
        }
        assertTrue(stream.current(retained));
        assertTrue(stream.acknowledge(new ViewStreamMessage.MeshAck(slot.key, retained.generation(), retained.coordinate().x(),
            retained.coordinate().y(), retained.coordinate().z(), retained.revision())));
        assertFalse(stream.current(retained));
    }

    @Test
    void refusalQueuedBeforeRetargetDoesNotRejectTheReplacementGeneration() throws ViewStreamProtocolException {
        ArrayDeque<Runnable> tasks = new ArrayDeque<Runnable>();
        SessionHarness harness = new SessionHarness(SessionHarness.options(false, 0), tasks::add, 0);
        harness.access.meshDistance = 512;
        SessionPortal portal = harness.access.add(new SessionPortal("refused-retarget", 0));
        harness.handshake(SessionHarness.NATIVE_CAPS);
        while (!tasks.isEmpty()) {
            tasks.remove().run();
        }
        harness.tick();
        while (!tasks.isEmpty()) {
            tasks.remove().run();
        }
        harness.pump();
        ViewStreamMessage.MeshBegin old = (ViewStreamMessage.MeshBegin) harness.last(ViewStreamMessageType.MESH_BEGIN);
        harness.c2s(ViewStreamFixtures.CODEC.encodeC2S(new ViewStreamMessage.PlateRefused(old.portalKey(), old.generation())));
        harness.access.meshDistance = 256;
        harness.tick();
        while (!tasks.isEmpty()) {
            tasks.remove().run();
        }
        harness.pump();
        harness.tick();
        assertTrue(harness.session.owns(portal.id));
        assertEquals(0, harness.sent(ViewStreamMessageType.PORTAL_DROP));
        assertTrue(harness.warnings.isEmpty(), harness.warnings.toString());
    }

    @Test
    void ackTimeoutStartsWhenQueuedCaptureEntersFlight() {
        FakeEndpoints access = new FakeEndpoints(new ArrayList<String>());
        SessionPortal portal = access.add(new SessionPortal("deadline", 0));
        ViewStreamSlot<String> slot = new ViewStreamSlot<String>(portal.id, 1, false);
        slot.geometry = portal.geometry(new SessionPalette()).withDepth(512);
        slot.sentGeometry = slot.geometry;
        slot.announced = true;
        slot.laneAttached = true;
        Vec3d eye = new Vec3d(11, 67, 15);
        MeshStream<String> stream = new MeshStream<String>();
        stream.beginTick();
        stream.refresh(slot, access, "observer", 1, 0, false, eye);
        while (stream.pollControl(key -> true) != null) {
        }
        stream.poll(MeshStream.TIMEOUT_NANOS - 1);
        stream.beginTick();
        assertDoesNotThrow(() -> stream.refresh(slot, access, "observer", 2, MeshStream.TIMEOUT_NANOS + 1, false, eye));
        assertThrows(IllegalStateException.class,
            () -> stream.refresh(slot, access, "observer", 3, 2 * MeshStream.TIMEOUT_NANOS, false, eye));
    }

    @Test
    void clientMemoryRefusalRetriesItsPortalWithoutReleasingNativeOwnership() throws ViewStreamProtocolException {
        SessionHarness harness = new SessionHarness(SessionHarness.options(false, 0));
        harness.access.meshDistance = 64;
        SessionPortal first = harness.access.add(new SessionPortal("first", 0));
        SessionPortal second = harness.access.add(new SessionPortal("second", 32));
        harness.handshake(SessionHarness.NATIVE_CAPS);
        harness.tick();
        ViewStreamMessage.MeshBegin begin = (ViewStreamMessage.MeshBegin) harness.last(ViewStreamMessageType.MESH_BEGIN);
        int refusedOrigin = harness.client.portals.get(begin.portalKey()).originX();
        SessionPortal refused = refusedOrigin == first.offsetX + 10 ? first : second;
        SessionPortal retained = refused == first ? second : first;
        harness.c2s(ViewStreamFixtures.CODEC.encodeC2S(new ViewStreamMessage.PlateRefused(begin.portalKey(), begin.generation())));
        harness.pump();
        assertEquals(1, harness.sent(ViewStreamMessageType.PORTAL_DROP));
        assertTrue(harness.session.owns(refused.id));
        harness.tick();
        assertTrue(harness.session.owns(refused.id));
        assertTrue(harness.session.owns(retained.id));
        int before = harness.sent(ViewStreamMessageType.MESH_BEGIN);
        for (int tick = 0; tick <= ViewStreamSession.NATIVE_RETRY_TICKS; tick++) {
            harness.tick();
        }
        assertTrue(harness.sent(ViewStreamMessageType.MESH_BEGIN) > before);
        assertTrue(harness.session.owns(refused.id));
        assertEquals(ViewStreamSessionState.CLIENT_VIEW, harness.session.state());
        assertTrue(harness.warnings.isEmpty(), harness.warnings.toString());
    }

    @Test
    void teleportResetDiscardsOldCreditsAndStartsFreshPortalKeys() throws ViewStreamProtocolException {
        SessionHarness harness = meshHarness();
        harness.tick();
        ViewStreamMessage.MeshSection old = (ViewStreamMessage.MeshSection) harness.last(ViewStreamMessageType.MESH_SECTION);
        harness.session.reset(ViewStreamMessage.ResetReason.TELEPORT);
        harness.pump();
        harness.tick();
        ViewStreamMessage.MeshSection next = (ViewStreamMessage.MeshSection) harness.last(ViewStreamMessageType.MESH_SECTION);
        assertTrue(next.portalKey() != old.portalKey());
        assertTrue(next.generation() > old.generation());
        assertEquals(ViewStreamInbound.IGNORED, harness.c2s(ViewStreamFixtures.CODEC.encodeC2S(ack(old))));
        assertEquals(ViewStreamInbound.HANDLED, harness.c2s(ViewStreamFixtures.CODEC.encodeC2S(ack(next))));
        assertTrue(harness.warnings.isEmpty(), harness.warnings.toString());
    }

    @Test
    void unavailableCaptureTimesOutWithoutResettingTheSession() throws ViewStreamProtocolException {
        SessionHarness harness = meshHarness();
        harness.access.meshReady = false;
        harness.tick();
        harness.clock.addAndGet(MeshStream.TIMEOUT_NANOS);
        harness.tick();
        assertEquals(ViewStreamSessionState.CLIENT_VIEW, harness.session.state());
        assertEquals(1, harness.sent(ViewStreamMessageType.PORTAL_DROP));
        assertEquals(0, harness.sent(ViewStreamMessageType.MESH_SECTION));
        assertEquals(1, harness.warnings.size());
        assertTrue(harness.warnings.getFirst().getCause().getMessage().contains("capture timeout"));
    }

    @Test
    void validBudgetQueuedCapturesKeepTheirPortalButLoadingFailuresStillExpire() throws ViewStreamProtocolException {
        SessionHarness harness = meshHarness();
        harness.access.meshReady = false;
        harness.access.meshQueued = true;
        harness.tick();
        for (int tick = 0; tick < 32; tick++) {
            harness.clock.addAndGet(MeshStream.TIMEOUT_NANOS);
            harness.tick();
            assertEquals(0, harness.sent(ViewStreamMessageType.PORTAL_DROP));
        }
        harness.access.meshQueued = false;
        harness.clock.addAndGet(MeshStream.TIMEOUT_NANOS);
        harness.tick();
        assertEquals(1, harness.sent(ViewStreamMessageType.PORTAL_DROP));
        assertTrue(harness.warnings.getFirst().getCause().getMessage().contains("capture timeout"));
    }

    private static MeshStream.Ready<String> removableCapture(MeshStream<String> stream,
        ViewStreamSlot<String> slot, FakeEndpoints access) {
        Set<MeshPlan.Coordinate> moved = new HashSet<MeshPlan.Coordinate>();
        for (MeshPlan.Section section : MeshPlan.visible(slot.geometry, new Vec3d(150, 67, 15))) {
            moved.add(section.coordinate());
        }
        for (int tick = 1; tick < 1000; tick++) {
            MeshStream.Ready<String> next = capture(stream, slot, access, new Vec3d(11, 67, 15), tick);
            MeshStream.Ready<String> selected = null;
            while (next != null) {
                if (selected == null && !moved.contains(next.coordinate())) {
                    selected = next;
                } else {
                    acknowledge(stream, next);
                }
                next = stream.poll(tick * SessionHarness.TICK_NANOS);
            }
            if (selected != null) {
                return selected;
            }
        }
        throw new AssertionError("no removable section captured");
    }

    private static void acknowledge(MeshStream<String> stream, MeshStream.Ready<String> section) {
        stream.acknowledge(new ViewStreamMessage.MeshAck(section.slot().key, section.generation(), section.coordinate().x(),
            section.coordinate().y(), section.coordinate().z(), section.revision()));
    }

    private static MeshStream.Ready<String> capture(MeshStream<String> stream, ViewStreamSlot<String> slot,
                                                          FakeEndpoints access, Vec3d eye, int tick) {
        stream.beginTick();
        stream.refresh(slot, access, "observer", tick, tick * SessionHarness.TICK_NANOS, false, eye);
        while (stream.pollControl(key -> true) != null) {
        }
        return stream.poll(tick * SessionHarness.TICK_NANOS);
    }

    private static SessionHarness meshHarness() throws ViewStreamProtocolException {
        SessionHarness harness = new SessionHarness(SessionHarness.options(false, 0));
        harness.access.meshDistance = 512;
        harness.access.add(new SessionPortal("mesh", 0));
        harness.handshake(SessionHarness.NATIVE_CAPS);
        return harness;
    }

    private static ViewStreamMessage.MeshAck ack(ViewStreamMessage.MeshSection section) {
        return new ViewStreamMessage.MeshAck(section.portalKey(), section.generation(), section.sectionX(), section.sectionY(),
            section.sectionZ(), section.revision());
    }
}
