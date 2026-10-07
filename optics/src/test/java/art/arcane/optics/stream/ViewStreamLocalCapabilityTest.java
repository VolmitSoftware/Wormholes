package art.arcane.optics.stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ViewStreamLocalCapabilityTest {
    @Test
    void nativeClientWithoutLocalCoverageRemainsAcceptedAndSelectsNativeRendering() throws Exception {
        SessionHarness harness = new SessionHarness(SessionHarness.options(true, 8));
        long nativeCapabilities = ViewStreamCapability.of(ViewStreamCapability.PLATES, ViewStreamCapability.MESH_RENDER,
            ViewStreamCapability.ENTITY_FRAMES, ViewStreamCapability.CLIENT_MIRROR);
        harness.handshake(nativeCapabilities);
        assertTrue(ViewStreamCapability.LOCAL_MESH.in(harness.client.offer.serverCaps()));
        assertFalse(ViewStreamCapability.LOCAL_MESH.in(harness.client.accept.caps()));
        assertTrue(ViewStreamCapability.MESH_RENDER.in(harness.client.accept.caps()));
        assertEquals(ViewStreamSessionState.CLIENT_VIEW, harness.session.state());
        assertTrue(harness.session.nativeRendererSelected());
    }
}
