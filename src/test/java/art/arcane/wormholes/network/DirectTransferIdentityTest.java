package art.arcane.wormholes.network;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class DirectTransferIdentityTest {
    @Test
    void directTransferRejectsAuthenticationModeChanges() {
        assertNotNull(TraversalAdmissionPolicy.directIdentityDenial(request(UUID.randomUUID(), true, true), false));
        assertNotNull(TraversalAdmissionPolicy.directIdentityDenial(request(UUID.randomUUID(), true, false), true));
    }

    @Test
    void matchingOfflineServersRequireTheIdentityThatDirectLoginWillProduce() {
        UUID offlineId = UUID.nameUUIDFromBytes("OfflinePlayer:Traveler".getBytes(StandardCharsets.UTF_8));
        assertNull(TraversalAdmissionPolicy.directIdentityDenial(request(offlineId, true, false), false));
        assertNotNull(TraversalAdmissionPolicy.directIdentityDenial(request(UUID.randomUUID(), true, false), false));
    }

    @Test
    void onlineAndProxyTransfersKeepTheirOwnAuthenticationPaths() {
        assertNull(TraversalAdmissionPolicy.directIdentityDenial(request(UUID.randomUUID(), true, true), true));
        assertNull(TraversalAdmissionPolicy.directIdentityDenial(request(UUID.randomUUID(), false, false), true));
        assertNull(TraversalAdmissionPolicy.directIdentityDenial(request(UUID.randomUUID(), false, true), false));
    }

    private static WireMessage.HandoffRequest request(UUID playerId, boolean direct, boolean online) {
        return new WireMessage.HandoffRequest(UUID.randomUUID(), playerId, "Traveler", null, direct, online, null);
    }
}
