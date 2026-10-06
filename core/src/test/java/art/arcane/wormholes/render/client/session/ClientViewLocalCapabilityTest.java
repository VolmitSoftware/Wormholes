package art.arcane.wormholes.render.client.session;

import art.arcane.wormholes.network.client.ClientViewCapability;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ClientViewLocalCapabilityTest {
    @Test
    void nativeClientWithoutLocalCoverageRemainsAcceptedAndSelectsNativeRendering() throws Exception {
        SessionHarness harness = new SessionHarness(SessionHarness.options(true, 8));
        long nativeCapabilities = ClientViewCapability.of(ClientViewCapability.PLATES, ClientViewCapability.MESH_RENDER,
            ClientViewCapability.ENTITY_FRAMES, ClientViewCapability.CLIENT_MIRROR);
        harness.handshake(nativeCapabilities);
        assertTrue(ClientViewCapability.LOCAL_MESH.in(harness.client.offer.serverCaps()));
        assertFalse(ClientViewCapability.LOCAL_MESH.in(harness.client.accept.caps()));
        assertTrue(ClientViewCapability.MESH_RENDER.in(harness.client.accept.caps()));
        assertEquals(ClientViewSessionState.CLIENT_VIEW, harness.session.state());
        assertTrue(harness.session.nativeRendererSelected());
    }
}
