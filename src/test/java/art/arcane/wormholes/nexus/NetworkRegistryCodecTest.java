package art.arcane.wormholes.nexus;

import art.arcane.volmlib.util.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkRegistryCodecTest {
    @TempDir
    Path tempDir;

    @Test
    void everyNetworkFieldSurvivesAnEncodeDecodeRoundTrip() {
        UUID networkId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID hubId = UUID.randomUUID();
        UUID remoteId = UUID.randomUUID();
        UUID managerId = UUID.randomUUID();

        PortalNetwork original = PortalNetwork.create(networkId, "Trade Ring", ownerId)
                .withVisibility(Visibility.HIDDEN)
                .withTopology(Topology.RING)
                .withHubPortalId(hubId)
                .withMember(hubId, new NetworkMember(hubId, "AB12", "Market", 1_700_000_000_000L, null))
                .withMember(remoteId, new NetworkMember(remoteId, "CD34", "Outpost", 1_700_000_001_000L, "beta"))
                .withRole(managerId, NetworkRole.MANAGER)
                .withCooldownGroup("trade")
                .withDefaultCostTemplate("tariff")
                .withServerName("alpha");

        PortalNetwork decoded = NetworkRegistryCodec.decode(NetworkRegistryCodec.encode(original));

        assertEquals(original, decoded);
        assertEquals("Trade Ring", decoded.name());
        assertEquals(Visibility.HIDDEN, decoded.visibility());
        assertEquals(Topology.RING, decoded.topology());
        assertEquals(hubId, decoded.hubPortalId());
        assertEquals("beta", decoded.member(remoteId).serverName());
        assertEquals("Market", decoded.member(hubId).label());
        assertEquals(1_700_000_000_000L, decoded.member(hubId).joinedAtMillis());
        assertEquals(NetworkRole.OWNER, decoded.role(ownerId));
        assertEquals(NetworkRole.MANAGER, decoded.role(managerId));
        assertEquals("trade", decoded.cooldownGroup());
        assertEquals("tariff", decoded.defaultCostTemplate());
        assertEquals("alpha", decoded.serverName());
    }

    @Test
    void decodingToleratesAnOlderRecordWithoutOptionalFields() {
        UUID networkId = UUID.randomUUID();
        JSONObject minimal = new JSONObject();
        minimal.put("id", networkId.toString());
        minimal.put("name", "plain");

        PortalNetwork decoded = NetworkRegistryCodec.decode(minimal);

        assertEquals(networkId, decoded.id());
        assertEquals("plain", decoded.name());
        assertEquals(Visibility.MEMBERS, decoded.visibility());
        assertEquals(Topology.MESH, decoded.topology());
        assertNull(decoded.hubPortalId());
        assertTrue(decoded.members().isEmpty());
        assertEquals("", decoded.cooldownGroup());
    }

    @Test
    void savedNetworksReloadFromDiskWithAWorkingReverseIndex() throws IOException {
        UUID firstPortal = UUID.randomUUID();
        UUID secondPortal = UUID.randomUUID();
        NetworkRegistry registry = new NetworkRegistry(tempDir);
        registry.load();

        PortalNetwork network = PortalNetwork.create(UUID.randomUUID(), "Mines", UUID.randomUUID())
                .withMember(firstPortal, new NetworkMember(firstPortal, "AAAA", "", 1L, null))
                .withMember(secondPortal, new NetworkMember(secondPortal, "BBBB", "", 2L, null));
        registry.save(network);

        assertEquals(network.id(), registry.memberOf(firstPortal).id());
        assertEquals("BBBB", registry.resolve(network.id(), "bbbb").address());
        assertEquals(secondPortal, registry.resolve(network.id(), "BBBB").portalId());

        NetworkRegistry reloaded = new NetworkRegistry(tempDir);
        reloaded.load();

        assertEquals(1, reloaded.all().size());
        assertNotNull(reloaded.byName("mines"));
        assertEquals(network, reloaded.byId(network.id()));
        assertEquals(network.id(), reloaded.memberOf(secondPortal).id());
    }

    @Test
    void removingAMemberAndDeletingANetworkClearTheReverseIndexAndTheFile() throws IOException {
        UUID portalId = UUID.randomUUID();
        UUID strandedId = UUID.randomUUID();
        NetworkRegistry registry = new NetworkRegistry(tempDir);
        registry.load();

        PortalNetwork network = PortalNetwork.create(UUID.randomUUID(), "Docks", UUID.randomUUID())
                .withMember(portalId, new NetworkMember(portalId, "AAAA", "", 1L, null))
                .withMember(strandedId, new NetworkMember(strandedId, "BBBB", "", 2L, null));
        registry.save(network);
        registry.save(network.withoutMember(strandedId));

        assertNull(registry.memberOf(strandedId));
        assertEquals(network.id(), registry.memberOf(portalId).id());

        registry.delete(network.id());

        assertNull(registry.memberOf(portalId));
        assertNull(registry.byId(network.id()));
        assertNull(registry.byName("docks"));
        assertTrue(registry.all().isEmpty());

        NetworkRegistry reloaded = new NetworkRegistry(tempDir);
        reloaded.load();
        assertTrue(reloaded.all().isEmpty());
    }
}
