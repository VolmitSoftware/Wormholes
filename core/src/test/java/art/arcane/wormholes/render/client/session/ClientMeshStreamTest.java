package art.arcane.wormholes.render.client.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import art.arcane.wormholes.network.client.ClientViewCodec;
import art.arcane.wormholes.network.client.ClientViewCapability;
import art.arcane.wormholes.network.client.BrickLightSource;
import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.wormholes.network.client.ClientViewMessageType;
import art.arcane.wormholes.network.client.ClientViewProtocolException;
import art.arcane.wormholes.network.client.ClientViewProtocol;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.ArrayDeque;
import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.network.client.SessionPalette;
import art.arcane.wormholes.render.ProjectionWorldChangeTracker;
import art.arcane.wormholes.render.plate.PlateBox;
import art.arcane.wormholes.render.plate.PlateTestFixtures;
import art.arcane.wormholes.render.plate.ViewPlate;
import java.util.UUID;

final class ClientMeshStreamTest {
    @Test
    void unannouncedBranchDoesNotHoldReadyRootSectionsBehindItsBegin() {
        FakePortalAccess access = new FakePortalAccess(new ArrayList<String>());
        SessionPortal blockedPortal = access.add(new SessionPortal("blocked", 0));
        SessionPortal readyPortal = access.add(new SessionPortal("ready", 0));
        ClientViewPortalSlot<String> blocked = new ClientViewPortalSlot<String>(blockedPortal.id, 1, false);
        blocked.geometry = blockedPortal.geometry(new SessionPalette());
        blocked.sentGeometry = blocked.geometry;
        blocked.laneAttached = true;
        ClientViewPortalSlot<String> root = new ClientViewPortalSlot<String>(readyPortal.id, 2, false);
        root.geometry = readyPortal.geometry(new SessionPalette());
        root.sentGeometry = root.geometry;
        root.laneAttached = true;
        root.announced = true;
        GeometryVector eye = new GeometryVector(11, 67, 15);
        ClientMeshStream<String> stream = new ClientMeshStream<String>();
        stream.beginTick();
        stream.refresh(blocked, access, "observer", 1, SessionHarness.TICK_NANOS, false, eye);
        stream.refresh(root, access, "observer", 1, SessionHarness.TICK_NANOS, false, eye);
        assertTrue(stream.pollControl(key -> key == root.key) instanceof ClientViewMessage.MeshBegin begin && begin.portalKey() == root.key);
        stream.beginTick();
        stream.refresh(root, access, "observer", 2, SessionHarness.TICK_NANOS * 2, false, eye);
        ClientMeshStream.Ready<String> first = stream.poll(SessionHarness.TICK_NANOS * 2);
        assertTrue(first != null);
        assertEquals(root.key, first.slot().key);
        assertEquals(ClientMeshPlan.visible(root.geometry, eye).getFirst().coordinate(), first.coordinate());
        acknowledge(stream, first);
        ClientMeshStream.Ready<String> next;
        while ((next = stream.poll(SessionHarness.TICK_NANOS * 2)) != null) {
            assertEquals(root.key, next.slot().key);
            acknowledge(stream, next);
        }
        blocked.announced = true;
        assertTrue(stream.poll(SessionHarness.TICK_NANOS * 2) == null, "section must wait for its own MeshBegin");
        assertTrue(stream.pollControl(key -> true) instanceof ClientViewMessage.MeshBegin begin && begin.portalKey() == blocked.key);
        next = stream.poll(SessionHarness.TICK_NANOS * 2);
        assertTrue(next != null);
        assertEquals(blocked.key, next.slot().key);
        assertTrue(access.meshCalls <= 2 * ClientMeshStream.CAPTURES_PER_TICK);
    }

    @Test
    void nativeMirrorUsesSectionStreamingWithoutLegacyMirrorCapabilityOrPlate() throws ClientViewProtocolException {
        SessionHarness harness = new SessionHarness(SessionHarness.options(false, 0));
        harness.access.meshDistance = 208;
        SessionPortal mirror = harness.access.add(new SessionPortal("native-mirror", 0));
        mirror.mirror = true;
        mirror.refused = true;
        harness.handshake(SessionHarness.NATIVE_CAPS & ~ClientViewCapability.CLIENT_MIRROR.mask());
        for (int tick = 0; tick < 8; tick++) {
            harness.tick();
        }
        assertTrue(harness.session.owns(mirror.id));
        ClientViewMessage.Portal portal = (ClientViewMessage.Portal) harness.last(ClientViewMessageType.PORTAL);
        assertTrue(portal.geometry().mirror());
        assertEquals(208, portal.geometry().depthBlocks());
        assertEquals(ClientMeshStream.MAX_IN_FLIGHT, harness.sent(ClientViewMessageType.MESH_SECTION));
        assertTrue(harness.access.plateRequested.isEmpty());
        assertTrue(harness.access.refusalChecked.isEmpty());
        ClientViewMessage.MeshSection section = (ClientViewMessage.MeshSection) harness.last(ClientViewMessageType.MESH_SECTION);
        assertEquals(ClientViewInbound.HANDLED, harness.c2s(ClientViewCodec.encodeC2S(ack(section))));
        harness.tick();
        assertEquals(ClientMeshStream.MAX_IN_FLIGHT + 1, harness.sent(ClientViewMessageType.MESH_SECTION));
    }

    @Test
    void fullSpeedMeshAndFrameAcknowledgementsKeepTheSessionActive() throws ClientViewProtocolException {
        SessionHarness harness = new SessionHarness(SessionHarness.options(false, ClientViewProtocol.DEFAULT_ACK_WINDOW_FRAMES));
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
                ClientViewMessage message = harness.client.received.get(cursor++);
                if (message instanceof ClientViewMessage.MeshSection section) {
                    assertEquals(ClientViewInbound.HANDLED, harness.c2s(ClientViewCodec.encodeC2S(ack(section))));
                    acknowledged++;
                }
            }
            harness.pump();
            assertEquals(ClientViewSessionState.CLIENT_VIEW, harness.session.state(), "session reset on tick " + tick);
            assertEquals(0L, harness.session.stats().c2sDropped());
        }
        assertTrue(acknowledged >= 32 * ClientMeshStream.CAPTURES_PER_TICK, "section streaming must sustain its capture budget");
        assertEquals(0, harness.session.stats().outstandingGroups());
        assertTrue(harness.warnings.isEmpty(), harness.warnings.toString());
    }

    @Test
    void dirtyResidentRefreshesDuringInitialCoverageAndWaitsForReplacement() {
        FakePortalAccess access = new FakePortalAccess(new ArrayList<String>());
        access.meshChanges = new ProjectionWorldChangeTracker();
        SessionPortal portal = access.add(new SessionPortal("dirty-refresh", 0));
        ClientViewPortalSlot<String> slot = new ClientViewPortalSlot<String>(portal.id, 1, false);
        slot.geometry = portal.geometry(new SessionPalette()).withDepth(512);
        slot.sentGeometry = slot.geometry;
        slot.announced = true;
        slot.laneAttached = true;
        GeometryVector eye = new GeometryVector(11, 67, 15);
        List<ClientMeshPlan.Section> visible = ClientMeshPlan.visible(slot.geometry, eye);
        ClientMeshPlan.Section target = visible.get(128);
        PlateBox clip = target.clip();
        UUID unchangedWorld = UUID.randomUUID();
        for (int index = 0; index < Math.min(512, visible.size()); index++) {
            PlateBox resident = visible.get(index).clip();
            access.meshPlates.put(resident, PlateTestFixtures.tracked(portal.id, resident, unchangedWorld, 0));
        }
        UUID world = UUID.randomUUID();
        ViewPlate<String> previous = PlateTestFixtures.tracked(portal.id, clip, world, 0);
        access.meshPlates.put(clip, previous);
        ClientMeshStream<String> stream = new ClientMeshStream<String>();
        Set<ClientMeshPlan.Coordinate> received = new HashSet<ClientMeshPlan.Coordinate>();
        int tick = 1;
        for (; tick <= 40; tick++) {
            ClientMeshStream.Ready<String> section = capture(stream, slot, access, eye, tick);
            while (section != null) {
                received.add(section.coordinate());
                acknowledge(stream, section);
                section = stream.poll(tick * SessionHarness.TICK_NANOS);
            }
        }
        assertTrue(received.contains(target.coordinate()));
        assertTrue(received.size() < visible.size(), "initial coverage must still be loading");
        access.meshRequests.clear();
        access.meshChanges.markChanged(world, clip.minX(), clip.minY(), clip.minZ());
        int dirtyTick = tick;
        while (!access.meshRequests.contains(clip) && tick < dirtyTick + 12) {
            ClientMeshStream.Ready<String> section = capture(stream, slot, access, eye, tick);
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
            ClientMeshStream.Ready<String> section = capture(stream, slot, access, eye, tick);
            while (section != null) {
                if (section.coordinate().equals(target.coordinate())) {
                    assertTrue(section.plate() == replacement);
                    updated = true;
                }
                acknowledge(stream, section);
                section = stream.poll(tick * SessionHarness.TICK_NANOS);
            }
            assertTrue(access.meshCalls - calls <= ClientMeshStream.CAPTURES_PER_TICK);
        }
        assertTrue(updated, "replacement must stay on the pending capture path");
    }

    @Test
    void completedCoverageKeepsRefreshingAcrossCursorWraps() {
        FakePortalAccess access = new FakePortalAccess(new ArrayList<String>());
        SessionPortal portal = access.add(new SessionPortal("refresh", 0));
        ClientViewPortalSlot<String> slot = new ClientViewPortalSlot<String>(portal.id, 1, false);
        slot.geometry = portal.geometry(new SessionPalette());
        slot.sentGeometry = slot.geometry;
        slot.announced = true;
        slot.laneAttached = true;
        ClientMeshStream<String> stream = new ClientMeshStream<String>();
        GeometryVector eye = new GeometryVector(11, 67, 15);
        int sectionCount = ClientMeshPlan.visible(slot.geometry, eye).size();
        assertTrue(sectionCount > ClientMeshStream.CAPTURES_PER_TICK);

        for (int step = 1; step <= sectionCount * 3; step++) {
            int callsBefore = access.meshCalls;
            int tick = step * 20;
            ClientMeshStream.Ready<String> section = capture(stream, slot, access, eye, tick);
            while (section != null) {
                acknowledge(stream, section);
                section = stream.poll(tick * SessionHarness.TICK_NANOS);
            }
            assertEquals(ClientMeshStream.CAPTURES_PER_TICK, access.meshCalls - callsBefore,
                "eligible refresh work must continue when the cursor wraps");
        }
    }

    @Test
    void movingEyeDoesNotRestartResidentRefreshAtTheNearestSections() {
        FakePortalAccess access = new FakePortalAccess(new ArrayList<String>());
        SessionPortal portal = access.add(new SessionPortal("moving-refresh", 0));
        ClientViewPortalSlot<String> slot = new ClientViewPortalSlot<String>(portal.id, 1, false);
        slot.geometry = portal.geometry(new SessionPalette()).withDepth(512);
        slot.sentGeometry = slot.geometry;
        slot.announced = true;
        slot.laneAttached = true;
        ClientMeshStream<String> stream = new ClientMeshStream<String>();
        GeometryVector eye = new GeometryVector(11, 67, 15);
        GeometryVector moved = new GeometryVector(12, 67, 15);
        ArrayList<ClientMeshPlan.Coordinate> residents = new ArrayList<ClientMeshPlan.Coordinate>();
        Set<ClientMeshPlan.Coordinate> movedVisible = new HashSet<ClientMeshPlan.Coordinate>();
        for (ClientMeshPlan.Section section : ClientMeshPlan.visible(slot.geometry, moved)) {
            movedVisible.add(section.coordinate());
        }
        for (int step = 1; step <= 64; step++) {
            int tick = step * 20;
            ClientMeshStream.Ready<String> section = capture(stream, slot, access, eye, tick);
            while (section != null) {
                residents.add(section.coordinate());
                acknowledge(stream, section);
                section = stream.poll(tick * SessionHarness.TICK_NANOS);
            }
        }
        assertTrue(residents.size() > 128);
        ClientMeshPlan.Coordinate changed = null;
        for (int index = residents.size() - 1; index >= 128; index--) {
            if (movedVisible.contains(residents.get(index))) {
                changed = residents.get(index);
                break;
            }
        }
        assertTrue(changed != null);
        access.meshPlates.remove(new ClientMeshPlan.Section(changed.x(), changed.y(), changed.z(), 0).clip());
        boolean refreshed = false;
        for (int step = 65; step <= 320 && !refreshed; step++) {
            int tick = step * 20;
            ClientMeshStream.Ready<String> section = capture(stream, slot, access, step % 2 == 0 ? eye : moved, tick);
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
        FakePortalAccess access = new FakePortalAccess(new ArrayList<String>());
        SessionPortal portal = access.add(new SessionPortal("progress", 0));
        ClientViewPortalSlot<String> slot = new ClientViewPortalSlot<String>(portal.id, 1, false);
        slot.geometry = portal.geometry(new SessionPalette()).withDepth(208);
        slot.sentGeometry = slot.geometry;
        slot.announced = true;
        slot.laneAttached = true;
        ClientMeshStream<String> stream = new ClientMeshStream<String>();
        Set<ClientMeshPlan.Coordinate> received = new HashSet<ClientMeshPlan.Coordinate>();
        for (int tick = 1; tick <= 100; tick++) {
            GeometryVector eye = new GeometryVector(11 + (tick / 8 % 2), 67, 15);
            stream.beginTick();
            stream.refresh(slot, access, "observer", tick, tick * SessionHarness.TICK_NANOS, false, eye);
            ClientViewMessage control;
            while ((control = stream.pollControl(key -> true)) != null) {
                if (control instanceof ClientViewMessage.MeshDrop drop) {
                    received.remove(new ClientMeshPlan.Coordinate(drop.sectionX(), drop.sectionY(), drop.sectionZ()));
                }
            }
            ClientMeshStream.Ready<String> section;
            while ((section = stream.poll(tick * SessionHarness.TICK_NANOS)) != null) {
                assertTrue(received.add(section.coordinate()), "initial coverage must precede refresh work");
                assertTrue(stream.acknowledge(new ClientViewMessage.MeshAck(1, section.generation(), section.coordinate().x(),
                    section.coordinate().y(), section.coordinate().z(), section.revision())));
            }
        }
        assertFalse(received.isEmpty());
        assertEquals(100 * ClientMeshStream.CAPTURES_PER_TICK, access.meshCalls);
    }

    @Test
    void clientDistanceStreamsBoundedSectionsAndWaitsForTheirOwnAcks() throws ClientViewProtocolException {
        SessionHarness harness = meshHarness();
        for (int tick = 0; tick < 8; tick++) {
            harness.tick();
        }
        assertEquals(ClientMeshStream.MAX_IN_FLIGHT, harness.sent(ClientViewMessageType.MESH_SECTION));
        assertEquals(0, harness.sent(ClientViewMessageType.PLATE_BEGIN));
        assertTrue(harness.access.plateRequested.isEmpty());
        ClientViewMessage.Portal portal = (ClientViewMessage.Portal) harness.last(ClientViewMessageType.PORTAL);
        assertEquals(512, portal.geometry().depthBlocks());
        ClientViewMessage.MeshBegin begin = (ClientViewMessage.MeshBegin) harness.last(ClientViewMessageType.MESH_BEGIN);
        assertEquals(513, begin.bounds().sizeZ());
        assertEquals(ClientMeshPlan.capacity(portal.geometry()), begin.maxResidentSections());
        ClientViewMessage.MeshSection section = (ClientViewMessage.MeshSection) harness.last(ClientViewMessageType.MESH_SECTION);
        assertEquals(ClientViewInbound.HANDLED, harness.c2s(ClientViewCodec.encodeC2S(ack(section))));
        harness.tick();
        assertEquals(ClientMeshStream.MAX_IN_FLIGHT + 1, harness.sent(ClientViewMessageType.MESH_SECTION));
        assertTrue(harness.warnings.isEmpty(), harness.warnings.toString());
    }

    @Test
    void retargetDropsOldGenerationCreditsAndIgnoresLateAcks() throws ClientViewProtocolException {
        SessionHarness harness = meshHarness();
        harness.tick();
        ClientViewMessage.MeshSection old = (ClientViewMessage.MeshSection) harness.last(ClientViewMessageType.MESH_SECTION);
        harness.access.meshDistance = 256;
        harness.tick();
        ClientViewMessage.MeshSection replacement = (ClientViewMessage.MeshSection) harness.last(ClientViewMessageType.MESH_SECTION);
        assertTrue(replacement.generation() > old.generation());
        assertEquals(ClientViewInbound.IGNORED, harness.c2s(ClientViewCodec.encodeC2S(ack(old))));
        assertEquals(ClientViewInbound.HANDLED, harness.c2s(ClientViewCodec.encodeC2S(ack(replacement))));
        assertTrue(harness.warnings.isEmpty(), harness.warnings.toString());
    }

    @Test
    void failedEncodingOfRetiredGenerationDoesNotRejectItsReplacement() throws ClientViewProtocolException {
        SessionHarness harness = meshHarness();
        harness.access.light = (sectionX, sectionY, sectionZ, block, sky) -> {
            harness.access.light = BrickLightSource.NONE;
            harness.access.meshDistance = 256;
            harness.session.tick(++harness.serverTick);
            throw new IllegalStateException("retired section encoding failed");
        };
        harness.tick();
        harness.tick();

        assertEquals(ClientViewSessionState.CLIENT_VIEW, harness.session.state());
        assertTrue(harness.session.owns(harness.access.portals.keySet().iterator().next()));
        assertTrue(harness.warnings.isEmpty(), harness.warnings.toString());
        ClientViewMessage.MeshSection replacement = (ClientViewMessage.MeshSection) harness.last(ClientViewMessageType.MESH_SECTION);
        assertTrue(replacement.generation() > 1);
    }

    @Test
    void unavailableSectionsKeepCaptureWorkBounded() throws ClientViewProtocolException {
        SessionHarness harness = meshHarness();
        harness.access.meshReady = false;
        for (int tick = 0; tick < 5; tick++) {
            harness.tick();
        }
        assertEquals(0, harness.sent(ClientViewMessageType.MESH_SECTION));
        assertTrue(harness.access.meshCalls <= 5 * ClientMeshStream.CAPTURES_PER_TICK);
        harness.access.meshReady = true;
        harness.tick();
        assertEquals(ClientMeshStream.CAPTURES_PER_TICK, harness.sent(ClientViewMessageType.MESH_SECTION));
        assertFalse(harness.access.meshPlates.isEmpty());
    }

    @Test
    void stalledNearCapturesDoNotBlockOtherVisibleSections() throws ClientViewProtocolException {
        SessionHarness harness = meshHarness();
        SessionPortal portal = harness.access.portals.values().iterator().next();
        List<ClientMeshPlan.Section> order = ClientMeshPlan.visible(portal.geometry(new SessionPalette()).withDepth(512), harness.access.eye);
        harness.access.unavailableMesh.add(order.get(0).clip());
        harness.access.unavailableMesh.add(order.get(1).clip());
        for (int tick = 0; tick < 8; tick++) {
            int before = harness.access.meshCalls;
            harness.tick();
            assertTrue(harness.access.meshCalls - before <= ClientMeshStream.CAPTURES_PER_TICK);
        }
        assertTrue(harness.sent(ClientViewMessageType.MESH_SECTION) > 4);
        assertTrue(harness.warnings.isEmpty(), harness.warnings.toString());
    }

    @Test
    void residentChangesRefreshWhileInitialCoverageIsStillLoading() {
        FakePortalAccess access = new FakePortalAccess(new ArrayList<String>());
        SessionPortal portal = access.add(new SessionPortal("refresh-during-load", 0));
        ClientViewPortalSlot<String> slot = new ClientViewPortalSlot<String>(portal.id, 1, false);
        slot.geometry = portal.geometry(new SessionPalette()).withDepth(512);
        slot.sentGeometry = slot.geometry;
        slot.announced = true;
        slot.laneAttached = true;
        ClientMeshStream<String> stream = new ClientMeshStream<String>();
        GeometryVector eye = new GeometryVector(11, 67, 15);
        ClientMeshStream.Ready<String> first = capture(stream, slot, access, eye, 1);
        acknowledge(stream, first);
        ClientMeshStream.Ready<String> next;
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
        assertTrue(access.meshPlates.size() < ClientMeshPlan.visible(slot.geometry, eye).size());
    }

    @Test
    void identicalRebuiltSectionsDoNotResendTheirPayload() throws ClientViewProtocolException {
        SessionHarness harness = meshHarness();
        harness.access.meshDistance = 16;
        for (int tick = 0; tick < 120; tick++) {
            harness.tick();
            for (ClientViewMessage message : List.copyOf(harness.client.received)) {
                if (message instanceof ClientViewMessage.MeshSection section) {
                    harness.c2s(ClientViewCodec.encodeC2S(ack(section)));
                }
            }
            harness.client.received.removeIf(message -> message instanceof ClientViewMessage.MeshSection);
        }
        harness.access.meshPlates.clear();
        for (int tick = 0; tick < 80; tick++) {
            harness.tick();
        }
        assertEquals(0, harness.sent(ClientViewMessageType.MESH_SECTION));
        assertTrue(harness.warnings.isEmpty(), harness.warnings.toString());
    }

    @Test
    void mirrorAndItsChildBothReceiveIndependentSectionStreams() throws ClientViewProtocolException {
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
        for (ClientViewMessage message : harness.client.received) {
            if (message instanceof ClientViewMessage.MeshSection section) {
                streamed.add(section.portalKey());
            }
        }
        assertEquals(2, streamed.size());
        assertEquals(2, harness.client.portals.size());
        assertEquals(0, harness.sent(ClientViewMessageType.PLATE_BEGIN));
        assertTrue(harness.warnings.isEmpty(), harness.warnings.toString());
    }

    @Test
    void movingEyeRetainsSectionsAndPendingEncodingUntilAcknowledged() {
        FakePortalAccess access = new FakePortalAccess(new ArrayList<String>());
        SessionPortal portal = access.add(new SessionPortal("retention", 0));
        ClientViewPortalSlot<String> slot = new ClientViewPortalSlot<String>(portal.id, 1, false);
        slot.geometry = portal.geometry(new SessionPalette()).withDepth(512);
        slot.sentGeometry = slot.geometry;
        slot.announced = true;
        slot.laneAttached = true;
        ClientMeshStream<String> stream = new ClientMeshStream<String>();
        ClientMeshStream.Ready<String> retained = removableCapture(stream, slot, access);
        assertTrue(stream.current(retained));
        stream.beginTick();
        stream.refresh(slot, access, "observer", 1000, 1000 * SessionHarness.TICK_NANOS,
            false, new GeometryVector(150, 67, 15));
        ClientViewMessage control;
        while ((control = stream.pollControl(key -> true)) != null) {
            assertFalse(control instanceof ClientViewMessage.MeshDrop);
        }
        assertTrue(stream.current(retained));
        assertTrue(stream.acknowledge(new ClientViewMessage.MeshAck(slot.key, retained.generation(), retained.coordinate().x(),
            retained.coordinate().y(), retained.coordinate().z(), retained.revision())));
        assertFalse(stream.current(retained));
    }

    @Test
    void refusalQueuedBeforeRetargetDoesNotRejectTheReplacementGeneration() throws ClientViewProtocolException {
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
        ClientViewMessage.MeshBegin old = (ClientViewMessage.MeshBegin) harness.last(ClientViewMessageType.MESH_BEGIN);
        harness.c2s(ClientViewCodec.encodeC2S(new ClientViewMessage.PlateRefused(old.portalKey(), old.generation())));
        harness.access.meshDistance = 256;
        harness.tick();
        while (!tasks.isEmpty()) {
            tasks.remove().run();
        }
        harness.pump();
        harness.tick();
        assertTrue(harness.session.owns(portal.id));
        assertEquals(0, harness.sent(ClientViewMessageType.PORTAL_DROP));
        assertTrue(harness.warnings.isEmpty(), harness.warnings.toString());
    }

    @Test
    void ackTimeoutStartsWhenQueuedCaptureEntersFlight() {
        FakePortalAccess access = new FakePortalAccess(new ArrayList<String>());
        SessionPortal portal = access.add(new SessionPortal("deadline", 0));
        ClientViewPortalSlot<String> slot = new ClientViewPortalSlot<String>(portal.id, 1, false);
        slot.geometry = portal.geometry(new SessionPalette()).withDepth(512);
        slot.sentGeometry = slot.geometry;
        slot.announced = true;
        slot.laneAttached = true;
        GeometryVector eye = new GeometryVector(11, 67, 15);
        ClientMeshStream<String> stream = new ClientMeshStream<String>();
        stream.beginTick();
        stream.refresh(slot, access, "observer", 1, 0, false, eye);
        while (stream.pollControl(key -> true) != null) {
        }
        stream.poll(ClientMeshStream.TIMEOUT_NANOS - 1);
        stream.beginTick();
        assertDoesNotThrow(() -> stream.refresh(slot, access, "observer", 2, ClientMeshStream.TIMEOUT_NANOS + 1, false, eye));
        assertThrows(IllegalStateException.class,
            () -> stream.refresh(slot, access, "observer", 3, 2 * ClientMeshStream.TIMEOUT_NANOS, false, eye));
    }

    @Test
    void clientMemoryRefusalRetriesItsPortalWithoutReleasingNativeOwnership() throws ClientViewProtocolException {
        SessionHarness harness = new SessionHarness(SessionHarness.options(false, 0));
        harness.access.meshDistance = 64;
        SessionPortal first = harness.access.add(new SessionPortal("first", 0));
        SessionPortal second = harness.access.add(new SessionPortal("second", 32));
        harness.handshake(SessionHarness.NATIVE_CAPS);
        harness.tick();
        ClientViewMessage.MeshBegin begin = (ClientViewMessage.MeshBegin) harness.last(ClientViewMessageType.MESH_BEGIN);
        int refusedOrigin = harness.client.portals.get(begin.portalKey()).originX();
        SessionPortal refused = refusedOrigin == first.offsetX + 10 ? first : second;
        SessionPortal retained = refused == first ? second : first;
        harness.c2s(ClientViewCodec.encodeC2S(new ClientViewMessage.PlateRefused(begin.portalKey(), begin.generation())));
        harness.pump();
        assertEquals(1, harness.sent(ClientViewMessageType.PORTAL_DROP));
        assertTrue(harness.session.owns(refused.id));
        harness.tick();
        assertTrue(harness.session.owns(refused.id));
        assertTrue(harness.session.owns(retained.id));
        int before = harness.sent(ClientViewMessageType.MESH_BEGIN);
        for (int tick = 0; tick <= ClientViewServerSession.NATIVE_RETRY_TICKS; tick++) {
            harness.tick();
        }
        assertTrue(harness.sent(ClientViewMessageType.MESH_BEGIN) > before);
        assertTrue(harness.session.owns(refused.id));
        assertEquals(ClientViewSessionState.CLIENT_VIEW, harness.session.state());
        assertTrue(harness.warnings.isEmpty(), harness.warnings.toString());
    }

    @Test
    void teleportResetDiscardsOldCreditsAndStartsFreshPortalKeys() throws ClientViewProtocolException {
        SessionHarness harness = meshHarness();
        harness.tick();
        ClientViewMessage.MeshSection old = (ClientViewMessage.MeshSection) harness.last(ClientViewMessageType.MESH_SECTION);
        harness.session.reset(ClientViewMessage.ResetReason.TELEPORT);
        harness.pump();
        harness.tick();
        ClientViewMessage.MeshSection next = (ClientViewMessage.MeshSection) harness.last(ClientViewMessageType.MESH_SECTION);
        assertTrue(next.portalKey() != old.portalKey());
        assertTrue(next.generation() > old.generation());
        assertEquals(ClientViewInbound.IGNORED, harness.c2s(ClientViewCodec.encodeC2S(ack(old))));
        assertEquals(ClientViewInbound.HANDLED, harness.c2s(ClientViewCodec.encodeC2S(ack(next))));
        assertTrue(harness.warnings.isEmpty(), harness.warnings.toString());
    }

    @Test
    void unavailableCaptureTimesOutWithoutResettingTheSession() throws ClientViewProtocolException {
        SessionHarness harness = meshHarness();
        harness.access.meshReady = false;
        harness.tick();
        harness.clock.addAndGet(ClientMeshStream.TIMEOUT_NANOS);
        harness.tick();
        assertEquals(ClientViewSessionState.CLIENT_VIEW, harness.session.state());
        assertEquals(1, harness.sent(ClientViewMessageType.PORTAL_DROP));
        assertEquals(0, harness.sent(ClientViewMessageType.MESH_SECTION));
        assertEquals(1, harness.warnings.size());
        assertTrue(harness.warnings.getFirst().getCause().getMessage().contains("capture timeout"));
    }

    private static ClientMeshStream.Ready<String> removableCapture(ClientMeshStream<String> stream,
        ClientViewPortalSlot<String> slot, FakePortalAccess access) {
        Set<ClientMeshPlan.Coordinate> moved = new HashSet<ClientMeshPlan.Coordinate>();
        for (ClientMeshPlan.Section section : ClientMeshPlan.visible(slot.geometry, new GeometryVector(150, 67, 15))) {
            moved.add(section.coordinate());
        }
        for (int tick = 1; tick < 1000; tick++) {
            ClientMeshStream.Ready<String> next = capture(stream, slot, access, new GeometryVector(11, 67, 15), tick);
            ClientMeshStream.Ready<String> selected = null;
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

    private static void acknowledge(ClientMeshStream<String> stream, ClientMeshStream.Ready<String> section) {
        stream.acknowledge(new ClientViewMessage.MeshAck(section.slot().key, section.generation(), section.coordinate().x(),
            section.coordinate().y(), section.coordinate().z(), section.revision()));
    }

    private static ClientMeshStream.Ready<String> capture(ClientMeshStream<String> stream, ClientViewPortalSlot<String> slot,
                                                          FakePortalAccess access, GeometryVector eye, int tick) {
        stream.beginTick();
        stream.refresh(slot, access, "observer", tick, tick * SessionHarness.TICK_NANOS, false, eye);
        while (stream.pollControl(key -> true) != null) {
        }
        return stream.poll(tick * SessionHarness.TICK_NANOS);
    }

    private static SessionHarness meshHarness() throws ClientViewProtocolException {
        SessionHarness harness = new SessionHarness(SessionHarness.options(false, 0));
        harness.access.meshDistance = 512;
        harness.access.add(new SessionPortal("mesh", 0));
        harness.handshake(SessionHarness.NATIVE_CAPS);
        return harness;
    }

    private static ClientViewMessage.MeshAck ack(ClientViewMessage.MeshSection section) {
        return new ClientViewMessage.MeshAck(section.portalKey(), section.generation(), section.sectionX(), section.sectionY(),
            section.sectionZ(), section.revision());
    }
}
