package art.arcane.wormholes.network.mesh;

import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.network.NetworkManager;
import art.arcane.wormholes.network.NetworkRouter;
import art.arcane.wormholes.network.PortalInfo;
import art.arcane.wormholes.network.PortalSyncService;
import art.arcane.wormholes.network.RemotePortalRegistry;
import art.arcane.wormholes.network.TraversalService;
import art.arcane.wormholes.network.WireMessage;
import art.arcane.wormholes.network.view.RemoteViewCache;
import art.arcane.wormholes.network.view.ViewServer;
import art.arcane.wormholes.network.view.ViewSubscriptionManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Three managers on real sockets: alpha trusts beta, beta trusts gamma, nobody pasted a code between alpha and gamma. */
@Timeout(90)
class NetworkManagerMeshIntegrationTest {
    private static final Logger LOGGER = Logger.getLogger("NetworkManagerMeshIntegrationTest");

    @TempDir
    Path tempDir;

    private final List<NetworkManager> managers = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (NetworkManager manager : managers) {
            manager.stop();
        }
        managers.clear();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void awaitTrue(String what, BooleanSupplier condition, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(250L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("Interrupted while waiting for: " + what);
            }
        }
        fail("Timed out waiting for: " + what);
    }

    private NetworkManager manager(String name, int listenPort, int gamePort) {
        NetworkConfig config = new NetworkConfig();
        config.enabled = true;
        config.serverName = name;
        config.advertiseHostOverride = "127.0.0.1";
        config.listenPort = listenPort;
        NetworkManager manager = new NetworkManager(LOGGER, config, "26.2", "test", gamePort, tempDir.resolve(name));
        managers.add(manager);
        return manager;
    }

    private static NetworkConfig.PeerEntry route(String peerName, int peerPort) {
        NetworkConfig.PeerEntry peer = new NetworkConfig.PeerEntry();
        peer.name = peerName;
        peer.host = "127.0.0.1";
        peer.port = peerPort;
        return peer;
    }

    private static void link(NetworkManager a, int portA, NetworkManager b, int portB) {
        a.savePeer(route(b.getLocalName(), portB));
        b.savePeer(route(a.getLocalName(), portA));
        a.trustPeer(b.getLocalName(), b.getPublicKey());
        b.trustPeer(a.getLocalName(), a.getPublicKey());
    }

    private static RemotePortalRegistry attachRouter(NetworkManager manager) {
        RemotePortalRegistry registry = new RemotePortalRegistry();
        PortalSyncService sync = new PortalSyncService(manager, List::of, Runnable::run);
        RemoteViewCache viewCache = new RemoteViewCache();
        NetworkRouter router = new NetworkRouter(registry, sync, new TraversalService(manager), new ViewServer(manager), viewCache,
            new ViewSubscriptionManager(manager, viewCache), manager.getReplicationManager(), manager);
        manager.setMessageSink(router::onMessage);
        manager.setPeerStateSink(router::onPeerState);
        return registry;
    }

    private static PortalInfo sampleInfo(UUID id) {
        return new PortalInfo(id, "Gamma gate", "world", "GATEWAY", true, "N", "E", "U",
            10.5D, 64.0D, 20.5D, 9.5D, 63.5D, 19.5D, 11.5D, 66.5D, 21.5D);
    }

    @Test
    void introductionThroughBetaLandsInQuarantineAndAcceptLinksGammaPortalsToAlpha() throws IOException {
        int portA = freePort();
        int portB = freePort();
        int portC = freePort();
        NetworkManager alpha = manager("alpha", portA, 25565);
        NetworkManager beta = manager("beta", portB, 25566);
        NetworkManager gamma = manager("gamma", portC, 25567);
        link(alpha, portA, beta, portB);
        link(beta, portB, gamma, portC);
        RemotePortalRegistry alphaRegistry = attachRouter(alpha);
        attachRouter(beta);
        attachRouter(gamma);

        alpha.start();
        beta.start();
        gamma.start();
        awaitTrue("alpha-beta and beta-gamma links", () -> alpha.isPeerReady("beta") && beta.isPeerReady("alpha")
            && beta.isPeerReady("gamma") && gamma.isPeerReady("beta"), 15_000L);

        awaitTrue("gamma quarantined at alpha via beta", () -> alpha.quarantine().get("gamma") != null, 15_000L);
        awaitTrue("alpha quarantined at gamma via beta", () -> gamma.quarantine().get("alpha") != null, 15_000L);
        assertEquals("beta", alpha.quarantine().get("gamma").introducer());
        assertEquals("beta", gamma.quarantine().get("alpha").introducer());
        assertNull(alpha.trustedKey("gamma"));
        assertNull(alpha.getPeer("gamma"));
        assertFalse(alpha.isPeerReady("gamma"));
        assertTrue(beta.members().epochOf("gamma", 0L) >= 1L);

        assertTrue(gamma.mesh().acceptQuarantined("alpha"));
        assertTrue(alpha.mesh().acceptQuarantined("gamma"));
        assertNotNull(alpha.trustedKey("gamma"));
        assertEquals(portC, alpha.getPeer("gamma").port);
        awaitTrue("alpha-gamma link after accept", () -> alpha.isPeerReady("gamma") && gamma.isPeerReady("alpha"), 30_000L);

        UUID portalId = UUID.randomUUID();
        // Both sides dial after the accept, so one of the two links is closed as a duplicate; resend until one survives.
        awaitTrue("gamma portal visible at alpha", () -> {
            if (alphaRegistry.get("gamma", portalId) != null) {
                return true;
            }
            gamma.send("alpha", new WireMessage.PortalUpsert(sampleInfo(portalId)));
            return false;
        }, 10_000L);
        awaitTrue("gamma recorded as alpha member", () -> alpha.members().epochOf("gamma", 0L) >= 1L, 10_000L);
    }

    @Test
    void tombstoneIssuedByBetaRemovesGammaAtAlphaAndGammaForgetsBeta() throws IOException {
        int portA = freePort();
        int portB = freePort();
        int portC = freePort();
        NetworkManager alpha = manager("alpha", portA, 25565);
        NetworkManager beta = manager("beta", portB, 25566);
        NetworkManager gamma = manager("gamma", portC, 25567);
        link(alpha, portA, beta, portB);
        link(beta, portB, gamma, portC);
        alpha.trustPeer("gamma", gamma.getPublicKey());
        alpha.savePeer(route("gamma", portC));
        attachRouter(alpha);
        attachRouter(beta);
        attachRouter(gamma);

        alpha.start();
        beta.start();
        gamma.start();
        awaitTrue("all links", () -> alpha.isPeerReady("beta") && beta.isPeerReady("gamma") && gamma.isPeerReady("beta"), 15_000L);
        awaitTrue("beta learned gamma epoch", () -> beta.members().epochOf("gamma", 0L) >= 1L, 15_000L);

        assertTrue(beta.tombstone("gamma"));
        assertNull(beta.trustedKey("gamma"));
        assertNull(beta.getPeer("gamma"));
        awaitTrue("alpha forgot gamma", () -> alpha.trustedKey("gamma") == null && alpha.getPeer("gamma") == null, 15_000L);
        awaitTrue("gamma forgot beta", () -> gamma.trustedKey("beta") == null && gamma.getPeer("beta") == null, 15_000L);
        assertTrue(alpha.tombstones().epochOf("gamma", -1L) >= 1L);
    }
}
