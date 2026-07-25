package art.arcane.wormholes.network;

import art.arcane.wormholes.config.toml.NetworkConfig;

import java.util.logging.Logger;

final class PeerTrustGate {
    private final NetworkManager network;
    private final Logger logger;
    private final PeerTrustStore trustStore;

    PeerTrustGate(NetworkManager network, Logger logger, PeerTrustStore trustStore) {
        this.network = network;
        this.logger = logger;
        this.trustStore = trustStore;
    }

    byte[] key(String peerName) {
        return trustStore.get(peerName);
    }

    void trustPeer(String peerName, String publicKey) {
        byte[] decoded = Handshake.decodePublicKeyText(publicKey);
        if (decoded == null) {
            throw new IllegalArgumentException("Invalid public key for " + peerName);
        }
        trustStore.trustOrReplace(peerName, decoded);
    }

    boolean approveConnection(String peerName, byte[] publicKey) {
        NetworkConfig active = network.activeConfig();
        byte[] trustedKey = trustStore.get(peerName);
        if (trustedKey != null) {
            if (Handshake.sameKey(trustedKey, publicKey)) {
                return true;
            }
            logger.warning("net: rejecting peer " + peerName + " because its public key changed");
            return false;
        }
        NetworkConfig.PeerEntry known = network.directory().find(peerName);
        if (known == null && !active.trustOnFirstUse) {
            return false;
        }
        if (trustStore.trust(peerName, publicKey)) {
            logger.info("net: trusted peer " + peerName + " public key " + Handshake.fingerprint(publicKey));
            return true;
        }
        logger.warning("net: rejecting peer " + peerName + " because its public key did not match stored trust");
        return false;
    }

    boolean approveSideband(String peerName, byte[] publicKey) {
        NetworkConfig active = network.activeConfig();
        byte[] trustedKey = trustStore.get(peerName);
        if (trustedKey != null) {
            if (Handshake.sameKey(trustedKey, publicKey)) {
                return true;
            }
            logger.warning("net: rejecting status sideband peer " + peerName + " because its public key changed");
            return false;
        }
        NetworkConfig.PeerEntry known = network.directory().find(peerName);
        if (known == null && !active.trustOnFirstUse) {
            return false;
        }
        if (trustStore.trust(peerName, publicKey)) {
            logger.info("net: trusted peer " + peerName + " public key " + Handshake.fingerprint(publicKey));
            return true;
        }
        logger.warning("net: rejecting status sideband peer " + peerName + " because its public key did not match stored trust");
        return false;
    }
}
