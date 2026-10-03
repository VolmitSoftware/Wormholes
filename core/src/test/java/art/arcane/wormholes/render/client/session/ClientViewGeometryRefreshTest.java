package art.arcane.wormholes.render.client.session;

import art.arcane.wormholes.network.client.ClientViewMessageType;
import art.arcane.wormholes.network.client.ClientViewProtocolException;
import art.arcane.wormholes.render.client.ClientPortalGeometry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ClientViewGeometryRefreshTest {
    @Test
    void changingBackendStampsRetainEqualDescriptorsWhileRefreshingNestedAttendance() throws ClientViewProtocolException {
        SessionHarness harness = new SessionHarness(SessionHarness.options(false, 0));
        harness.access.meshDistance = 32;
        SessionPortal root = harness.access.add(new SessionPortal("root mirror", 0));
        SessionPortal child = new SessionPortal("child mirror", 8);
        root.mirror = true;
        root.recursionDepth = 1;
        child.mirror = true;
        harness.access.nested.put(root.id, List.of(child));
        harness.handshake(SessionHarness.NATIVE_CAPS);
        harness.tick();
        assertEquals(2, harness.client.portals.size());
        Map<Integer, ClientPortalGeometry> initial = Map.copyOf(harness.client.portals);
        int descriptors = harness.sent(ClientViewMessageType.PORTAL);
        int begins = harness.sent(ClientViewMessageType.MESH_BEGIN);
        int nestedCalls = harness.access.nestedCalls;

        for (int tick = 0; tick < 40; tick++) {
            root.geometryRevision++;
            child.geometryRevision++;
            harness.tick();
        }

        assertEquals(initial, harness.client.portals);
        assertEquals(descriptors, harness.sent(ClientViewMessageType.PORTAL));
        assertEquals(begins, harness.sent(ClientViewMessageType.MESH_BEGIN));
        assertTrue(harness.access.nestedCalls >= nestedCalls + 40);
        assertTrue(harness.access.contexts.containsValue(child.id));

        root.frontSide = true;
        root.geometryRevision++;
        harness.tick();

        assertTrue(harness.sent(ClientViewMessageType.PORTAL) > descriptors);
        assertTrue(harness.sent(ClientViewMessageType.MESH_BEGIN) > begins);
        assertTrue(harness.client.portals.values().stream().anyMatch(ClientPortalGeometry::frontSide));
        assertTrue(harness.access.contexts.containsValue(child.id));
        assertTrue(harness.warnings.isEmpty(), harness.warnings.toString());
    }
}
