package art.arcane.optics.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.HashMap;
import java.util.HashSet;
import art.arcane.optics.math.Vec3d;

import org.junit.jupiter.api.Test;

import art.arcane.optics.aperture.ApertureDescriptor;

final class ViewStreamSessionNestedTest {
    private static final long WITHOUT_MIRROR = SessionHarness.CLIENT_CAPS & ~ViewStreamCapability.CLIENT_MIRROR.mask();
    private static final long WITHOUT_RECURSION = SessionHarness.CLIENT_CAPS & ~ViewStreamCapability.CLIENT_RECURSION.mask();

    @Test
    void nativeMirrorCyclesKeepSixDistinctBranchKeysWhileTheEyeMoves() throws ViewStreamProtocolException {
        SessionHarness harness = new SessionHarness(SessionHarness.options(false, 0));
        harness.access.meshDistance = 64;
        SessionPortal first = harness.access.add(new SessionPortal("first mirror", 0));
        SessionPortal second = new SessionPortal("second mirror", 8);
        first.mirror = true;
        second.mirror = true;
        first.recursionDepth = 3;
        second.recursionDepth = 3;
        harness.access.nested.put(first.id, List.of(second));
        harness.access.nested.put(second.id, List.of(first));
        Map<Integer, UUID> entityContexts = new HashMap<>();
        harness.entities = (observer, portal, key, tick, full, hideObserver) -> {
            entityContexts.put(key, portal);
            return new ViewStreamMessage.EntityFrame(key, 1, List.of(), List.of(), true);
        };
        harness.handshake(SessionHarness.NATIVE_CAPS);
        harness.tick();
        assertEquals(4, ViewStreamLimits.MAX_MIRROR_REFLECTIONS);
        assertEquals(ViewStreamLimits.MAX_MIRROR_REFLECTIONS, harness.client.portals.size());
        assertEquals(ViewStreamLimits.MAX_MIRROR_REFLECTIONS, harness.access.contexts.size());
        assertEquals(2, new HashSet<>(harness.access.contexts.values()).size());
        assertEquals(ViewStreamLimits.MAX_MIRROR_REFLECTIONS, entityContexts.size());
        assertEquals(harness.access.contexts.keySet(), new HashSet<>(entityContexts.values()));
        int parentKey = 0;
        for (int depth = 0; depth < ViewStreamLimits.MAX_MIRROR_REFLECTIONS; depth++) {
            int key = keyOf(harness, parentKey);
            ApertureDescriptor geometry = harness.client.portals.get(key);
            assertEquals(depth == ViewStreamLimits.MAX_MIRROR_REFLECTIONS - 1 ? 0 : 1, geometry.nested().size());
            parentKey = key;
        }
        Map<Integer, ApertureDescriptor> initial = Map.copyOf(harness.client.portals);
        int begins = harness.sent(ViewStreamMessageType.MESH_BEGIN);
        for (int tick = 0; tick < 60; tick++) {
            harness.access.eye = new Vec3d(11 + tick * 0.1, 67, 15);
            harness.tick();
            assertEquals(initial, harness.client.portals);
            assertEquals(begins, harness.sent(ViewStreamMessageType.MESH_BEGIN));
        }
        harness.access.nested.put(first.id, List.of());
        harness.tick();
        assertEquals(1, harness.client.portals.size());
        assertEquals(Map.of(first.id, first.id), harness.access.contexts);
        assertTrue(harness.warnings.isEmpty(), harness.warnings.toString());
    }

    @Test
    void linkedCyclesRetainTheirThreeDescendantLimit() throws ViewStreamProtocolException {
        SessionHarness harness = new SessionHarness(SessionHarness.options(false, 0));
        harness.access.meshDistance = 64;
        SessionPortal first = harness.access.add(new SessionPortal("first linked portal", 0));
        SessionPortal second = new SessionPortal("second linked portal", 8);
        first.recursionDepth = 64;
        second.recursionDepth = 64;
        harness.access.nested.put(first.id, List.of(second));
        harness.access.nested.put(second.id, List.of(first));
        harness.handshake(SessionHarness.NATIVE_CAPS);
        harness.tick();
        assertEquals(4, harness.client.portals.size());
        assertTrue(harness.warnings.isEmpty(), harness.warnings.toString());
    }

    @Test
    void nativeLinkedBranchesRespectEachAperturesDepthAndDetachWhenBudgetMoves() throws ViewStreamProtocolException {
        SessionHarness harness = new SessionHarness(SessionHarness.options(false, 0));
        harness.access.meshDistance = 64;
        SessionPortal root = harness.access.add(new SessionPortal("linked root", 0));
        root.recursionDepth = 3;
        List<SessionPortal> children = new ArrayList<>();
        for (int index = 0; index < ViewStreamLimits.MAX_NESTED_GEOMETRY; index++) {
            children.add(new SessionPortal("branch " + index, index * 4));
        }
        harness.access.nested.put(root.id, children);
        harness.handshake(SessionHarness.NATIVE_CAPS);
        harness.tick();
        assertEquals(1 + ViewStreamLimits.MAX_NESTED_GEOMETRY, harness.client.portals.size());
        SessionPortal first = children.getFirst();
        first.recursionDepth = 2;
        first.geometryRevision++;
        SessionPortal grandchild = new SessionPortal("grandchild", 40);
        grandchild.recursionDepth = 0;
        harness.access.nested.put(first.id, List.of(grandchild));
        harness.access.nested.put(grandchild.id, List.of(root));
        harness.tick();
        assertEquals(1 + ViewStreamLimits.MAX_NESTED_GEOMETRY, harness.client.portals.size());
        assertEquals(1 + ViewStreamLimits.MAX_NESTED_GEOMETRY, harness.access.contexts.size());
        assertTrue(harness.access.contexts.containsValue(grandchild.id));
        assertFalse(harness.access.contexts.containsValue(children.getLast().id));
        assertEquals(1, harness.access.contexts.values().stream().filter(root.id::equals).count());
        root.recursionDepth = 0;
        root.geometryRevision++;
        harness.tick();
        assertEquals(1, harness.client.portals.size());
        assertEquals(Map.of(root.id, root.id), harness.access.contexts);
        assertTrue(harness.warnings.isEmpty(), harness.warnings.toString());
    }

    @Test
    void clientMirrorSessionsNeverAskForTheMirrorPlate() throws ViewStreamProtocolException {
        SessionHarness harness = new SessionHarness(SessionHarness.options(true, 8));
        SessionWorld world = new SessionWorld(21L);
        SessionPortal mirror = mirror(harness, world, 0);
        mirror.refused = true;
        harness.handshake(SessionHarness.CLIENT_CAPS);
        for (int i = 0; i < 4; i++) {
            harness.tick();
        }
        assertTrue(harness.session.owns(mirror.id), "a client mirror is owned even when its plate would be refused");
        assertEquals(List.of("release " + mirror.id), releases(harness));
        assertFalse(harness.access.plateRequested.contains(mirror.id), "the mirror plate was requested");
        assertFalse(harness.access.refusalChecked.contains(mirror.id), "the mirror plate refusal was consulted");
        assertEquals(1, harness.sent(ViewStreamMessageType.PORTAL));
        assertEquals(0, harness.sent(ViewStreamMessageType.PLATE_BEGIN));
        assertEquals(0, harness.sent(ViewStreamMessageType.PLATE_BRICKS));
        ApertureDescriptor geometry = harness.client.portals.values().iterator().next();
        assertTrue(geometry.mirror());
    }

    @Test
    void withoutTheClientMirrorCapabilityTheMirrorPlateStreams() throws ViewStreamProtocolException {
        SessionHarness harness = new SessionHarness(SessionHarness.options(true, 8));
        SessionWorld world = new SessionWorld(22L);
        SessionPortal mirror = mirror(harness, world, 0);
        harness.handshake(WITHOUT_MIRROR);
        harness.tick();
        assertTrue(harness.session.owns(mirror.id));
        assertEquals(1, harness.sent(ViewStreamMessageType.PLATE_BEGIN));
        assertTrue(harness.access.plateRequested.contains(mirror.id));
    }

    @Test
    void nestedPortalsStreamAsChildrenOfTheirAttendedParent() throws ViewStreamProtocolException {
        SessionHarness harness = new SessionHarness(SessionHarness.options(true, 8));
        SessionWorld world = new SessionWorld(23L);
        SessionPortal mirror = mirror(harness, world, 0);
        SessionPortal child = child(harness, world, mirror, "child", 8);
        harness.handshake(SessionHarness.CLIENT_CAPS);
        harness.tick();
        assertTrue(harness.session.owns(mirror.id));
        assertFalse(harness.session.owns(child.id), "a nested child is never owned as a top-level portal");
        assertEquals(2, harness.client.portals.size());
        int parentKey = keyOf(harness, 0);
        int childKey = keyOf(harness, parentKey);
        ApertureDescriptor parent = harness.client.portals.get(parentKey);
        ApertureDescriptor nested = harness.client.portals.get(childKey);
        assertEquals(1, parent.nested().size());
        assertEquals(nested, parent.nested().get(0), "the nested entry equals the child PORTAL");
        assertEquals(parentKey, nested.parentPortalKey());
        assertNotNull(harness.client.plates.get(childKey), "the child plate streamed");
        assertNull(harness.client.plates.get(parentKey), "the client mirror has no plate");
        assertEquals(List.of("release " + mirror.id), releases(harness));

        harness.access.interest.clear();
        for (int i = 0; i < ViewStreamOptions.DEFAULT_INTEREST_GRACE_TICKS + 2; i++) {
            harness.tick();
        }
        assertTrue(harness.client.portals.isEmpty(), "dropping the parent drops its children");
        assertEquals(2, harness.sent(ViewStreamMessageType.PORTAL_DROP));
    }

    @Test
    void aChildThatLeavesTheParentIsDroppedAndTheParentResent() throws ViewStreamProtocolException {
        SessionHarness harness = new SessionHarness(SessionHarness.options(true, 8));
        SessionWorld world = new SessionWorld(24L);
        SessionPortal mirror = mirror(harness, world, 0);
        child(harness, world, mirror, "child", 8);
        harness.handshake(SessionHarness.CLIENT_CAPS);
        harness.tick();
        int parentKey = keyOf(harness, 0);
        harness.access.nested.get(mirror.id).clear();
        harness.tick();
        assertEquals(1, harness.sent(ViewStreamMessageType.PORTAL_DROP));
        assertEquals(1, harness.client.portals.size());
        assertTrue(harness.client.portals.get(parentKey).nested().isEmpty());
        assertEquals(3, harness.sent(ViewStreamMessageType.PORTAL));
    }

    @Test
    void childGeometryChangesResendBothTheChildAndItsParent() throws ViewStreamProtocolException {
        SessionHarness harness = new SessionHarness(SessionHarness.options(true, 8));
        SessionWorld world = new SessionWorld(25L);
        SessionPortal mirror = mirror(harness, world, 0);
        SessionPortal child = child(harness, world, mirror, "child", 8);
        harness.handshake(SessionHarness.CLIENT_CAPS);
        harness.tick();
        harness.tick();
        assertEquals(2, harness.sent(ViewStreamMessageType.PORTAL));
        child.geometryRevision++;
        child.frontSide = !child.frontSide;
        harness.tick();
        assertEquals(4, harness.sent(ViewStreamMessageType.PORTAL));
        int parentKey = keyOf(harness, 0);
        int childKey = keyOf(harness, parentKey);
        assertEquals(harness.client.portals.get(childKey), harness.client.portals.get(parentKey).nested().get(0));
    }

    @Test
    void nestedChildrenNeedTheRecursionCapability() throws ViewStreamProtocolException {
        SessionHarness harness = new SessionHarness(SessionHarness.options(true, 8));
        SessionWorld world = new SessionWorld(26L);
        SessionPortal mirror = mirror(harness, world, 0);
        child(harness, world, mirror, "child", 8);
        harness.handshake(WITHOUT_RECURSION);
        harness.tick();
        assertEquals(1, harness.client.portals.size());
        assertTrue(harness.client.portals.values().iterator().next().nested().isEmpty());
        assertEquals(0, harness.access.nestedCalls);
    }

    @Test
    void aSessionResetReattachesChildrenUnderTheNewParentKey() throws ViewStreamProtocolException {
        SessionHarness harness = new SessionHarness(SessionHarness.options(true, 8));
        SessionWorld world = new SessionWorld(27L);
        SessionPortal mirror = mirror(harness, world, 0);
        child(harness, world, mirror, "child", 8);
        harness.handshake(SessionHarness.CLIENT_CAPS);
        harness.tick();
        int before = keyOf(harness, 0);
        harness.session.reset(ViewStreamMessage.ResetReason.TELEPORT);
        harness.pump();
        harness.tick();
        assertEquals(2, harness.client.portals.size());
        int parentKey = keyOf(harness, 0);
        assertTrue(parentKey != before);
        int childKey = keyOf(harness, parentKey);
        assertEquals(parentKey, harness.client.portals.get(childKey).parentPortalKey());
        assertEquals(1, harness.client.portals.get(parentKey).nested().size());
    }

    @Test
    void clientMirrorEntityFramesHideTheObserverWhileServerMirrorsKeepIt() throws ViewStreamProtocolException {
        assertEquals(List.of(true), hideObserverCalls(SessionHarness.CLIENT_CAPS), "the client draws its own reflection");
        assertEquals(List.of(false), hideObserverCalls(WITHOUT_MIRROR), "a streamed mirror plate needs the projected observer");
    }

    private static List<Boolean> hideObserverCalls(long clientCaps) throws ViewStreamProtocolException {
        SessionHarness harness = new SessionHarness(SessionHarness.options(true, 8));
        List<Boolean> calls = new ArrayList<Boolean>();
        harness.entities = (observer, portal, key, tick, full, hideObserver) -> {
            calls.add(hideObserver);
            return null;
        };
        mirror(harness, new SessionWorld(28L), 0);
        harness.handshake(clientCaps);
        harness.tick();
        return calls;
    }

    private static SessionPortal mirror(SessionHarness harness, SessionWorld world, int offsetX) {
        SessionPortal mirror = harness.access.add(new SessionPortal("mirror", offsetX));
        mirror.mirror = true;
        mirror.recursionDepth = 2;
        mirror.plate = mirror.build(world);
        return mirror;
    }

    private static SessionPortal child(SessionHarness harness, SessionWorld world, SessionPortal parent, String name, int offsetX) {
        SessionPortal child = new SessionPortal(name, offsetX);
        child.plate = child.build(world);
        harness.access.nested.computeIfAbsent(parent.id, id -> new ArrayList<SessionPortal>()).add(child);
        return child;
    }

    private static int keyOf(SessionHarness harness, int parentKey) {
        for (Map.Entry<Integer, ApertureDescriptor> entry : harness.client.portals.entrySet()) {
            if (entry.getValue().parentPortalKey() == parentKey) {
                return entry.getKey();
            }
        }
        throw new AssertionError("no portal with parent key " + parentKey);
    }

    private static List<String> releases(SessionHarness harness) {
        List<String> releases = new ArrayList<String>();
        for (String event : harness.events) {
            if (event.startsWith("release ")) {
                releases.add(event);
            }
        }
        return releases;
    }
}
