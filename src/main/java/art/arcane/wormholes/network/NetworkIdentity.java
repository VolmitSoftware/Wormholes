package art.arcane.wormholes.network;

import art.arcane.wormholes.config.toml.NetworkConfig;

import java.security.PrivateKey;
import java.util.logging.Logger;

final class NetworkIdentity {
    private final NetworkManager network;
    private final IdentityStore identityStore;
    private final PublicHostResolver publicHostResolver;
    private final String mcVersion;
    private final String pluginVersion;

    private volatile String generatedName;
    private volatile String detectedLanHost;
    private volatile String detectedPublicHost;

    NetworkIdentity(NetworkManager network, Logger logger, IdentityStore identityStore, String mcVersion, String pluginVersion) {
        this.network = network;
        this.identityStore = identityStore;
        this.publicHostResolver = new PublicHostResolver(logger);
        this.mcVersion = mcVersion;
        this.pluginVersion = pluginVersion;
    }

    String mcVersion() {
        return mcVersion;
    }

    String pluginVersion() {
        return pluginVersion;
    }

    byte[] publicKeyBytes() {
        return identityStore.publicKeyBytes();
    }

    PrivateKey privateKey() {
        return identityStore.privateKey();
    }

    String publicKey() {
        return Handshake.encodePublicKey(identityStore.publicKeyBytes());
    }

    String fingerprint() {
        return Handshake.fingerprint(identityStore.publicKeyBytes());
    }

    String localName() {
        NetworkConfig active = network.activeConfig();
        if (active.serverName != null && !active.serverName.isBlank()) {
            return active.serverName;
        }
        return generatedServerName();
    }

    String advertiseHost() {
        NetworkConfig active = network.activeConfig();
        if (active.advertiseHostOverride != null && !active.advertiseHostOverride.isBlank()) {
            return active.advertiseHostOverride;
        }
        String publicHost = detectedPublicHost;
        if (publicHost == null) {
            publicHost = publicHostResolver.cached();
        }
        if (publicHost != null && !publicHost.isBlank()) {
            return publicHost;
        }
        String lan = detectedLanHost;
        if (lan == null) {
            lan = LanAddressResolver.detectLanAddress();
            detectedLanHost = lan;
        }
        return lan;
    }

    GameEndpoint gameEndpoint() {
        NetworkConfig active = network.activeConfig();
        String host = active.gameHostOverride == null || active.gameHostOverride.isBlank()
            ? advertiseHost() : active.gameHostOverride;
        int port = active.gamePortOverride == 0 ? network.gamePort() : active.gamePortOverride;
        return new GameEndpoint(host, port);
    }

    GameEndpoint privateGameEndpoint() {
        NetworkConfig active = network.activeConfig();
        String host = active.privateGameHostOverride;
        if (host == null || host.isBlank()) {
            host = network.gameBindHost();
        }
        if (host == null || host.isBlank()) {
            host = detectedLanHost;
            if (host == null) {
                host = LanAddressResolver.detectLanAddress();
                detectedLanHost = host;
            }
        }
        int port = active.privateGamePortOverride == 0 ? network.gamePort() : active.privateGamePortOverride;
        return new GameEndpoint(host, port);
    }

    String resolvedPublicHost() {
        String publicHost = detectedPublicHost;
        if (publicHost != null && !publicHost.isBlank()) {
            return publicHost;
        }
        return publicHostResolver.cached();
    }

    void setInferredAdvertiseHost(String host) {
        NetworkConfig active = network.activeConfig();
        if (host == null || host.isBlank() || (active.advertiseHostOverride != null && !active.advertiseHostOverride.isBlank())) {
            return;
        }
        if (PublicHostResolver.isValidHostLiteral(host)) {
            detectedPublicHost = host;
        }
    }

    void forgetDetectedPublicHost() {
        detectedPublicHost = null;
    }

    void resolvePublicHostAsync() {
        NetworkConfig active = network.activeConfig();
        if (active.advertiseHostOverride != null && !active.advertiseHostOverride.isBlank()) {
            return;
        }
        publicHostResolver.refreshAsync(resolved -> {
            if (resolved != null && !resolved.isBlank()) {
                detectedPublicHost = resolved;
            }
        });
    }

    void shutdown() {
        publicHostResolver.shutdown();
    }

    LocalIdentity snapshot() {
        return new LocalIdentity(localName(), mcVersion, pluginVersion, advertiseHost(), network.getBoundListenPort(),
            gameEndpoint(), privateGameEndpoint(), identityStore.publicKeyBytes(), identityStore.privateKey());
    }

    private String generatedServerName() {
        String name = generatedName;
        if (name == null) {
            name = "wh-" + fingerprint().replace(":", "");
            generatedName = name;
        }
        return name;
    }
}
