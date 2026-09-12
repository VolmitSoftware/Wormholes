package art.arcane.wormholes.network;

import art.arcane.wormholes.config.toml.NetworkConfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a roster entry is allowed to do once it has proved it came from the proxy. Even then it is an
 * introduction, not an operator decision: no tombstone clearing, no key replacement, no config flip.
 */
class ProxyRosterImportTest {
    private static final Logger LOGGER = Logger.getLogger(ProxyRosterImportTest.class.getName());

    @TempDir
    Path dataDirectory;

    @Test
    void aProxyImportAddsTrustAndARouteWithoutTurningTheNetworkOn() throws Exception {
        NetworkConfig config = config();
        NetworkManager network = new NetworkManager(LOGGER, config, "26.2", "test", 25565, dataDirectory);
        ImportExportService importExport = new ImportExportService(network);

        assertTrue(importExport.importProxyServerCode(code("creative", key())));

        NetworkConfig.PeerEntry saved = network.getPeer("creative");
        assertNotNull(saved, "the route must be saved so the proxy handoff can find the backend");
        assertTrue(saved.useProxy);
        assertNotNull(network.trustedKey("creative"));
        assertFalse(config.enabled, "a roster entry must never enable the network behind the operator");
        assertFalse(network.isRunning());
    }

    @Test
    void aTombstonedPeerIsNotReAdmittedByTheProxyAndAKeyIsNeverReplaced() throws Exception {
        NetworkConfig config = config();
        NetworkManager network = new NetworkManager(LOGGER, config, "26.2", "test", 25565, dataDirectory);
        ImportExportService importExport = new ImportExportService(network);
        String removedKey = key();

        network.trustPeer("creative", removedKey);
        assertTrue(network.tombstone("creative"), "an operator removed the peer network-wide");
        assertNull(network.trustedKey("creative"));

        assertFalse(importExport.importProxyServerCode(code("creative", removedKey)),
            "a roster must not clear the tombstone an operator set");
        assertNull(network.trustedKey("creative"));

        String pinned = key();
        network.trustPeer("lobby", pinned);
        assertFalse(importExport.importProxyServerCode(code("lobby", key())),
            "a roster must not rewrite a key the operator pinned");
        assertEquals(pinned, Base64.getUrlEncoder().withoutPadding().encodeToString(network.trustedKey("lobby")));

        assertTrue(importExport.importProxyServerCode(code("lobby", pinned)),
            "the same key stays trusted and refreshes the route");
    }

    private static NetworkConfig config() {
        NetworkConfig config = new NetworkConfig();
        config.enabled = false;
        config.listenEnabled = false;
        config.serverName = "survival";
        config.advertiseHostOverride = "127.0.0.1";
        return config;
    }

    private static String key() throws Exception {
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic().getEncoded());
    }

    private static ServerCode code(String name, String publicKey) {
        return new ServerCode(name, "10.0.0.5", List.of(), 8901, new GameEndpoint("10.0.0.5", 25565), null, publicKey);
    }
}
