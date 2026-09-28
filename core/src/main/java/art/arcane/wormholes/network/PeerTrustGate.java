package art.arcane.wormholes.network;

import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.network.mesh.MeshHandlers;
import art.arcane.wormholes.network.mesh.PeerQuarantineStore;
import art.arcane.wormholes.network.mesh.TombstoneService;

import java.security.PublicKey;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

final class PeerTrustGate {
    private final NetworkManager network;
    private final Logger logger;
    private final PeerTrustStore trustStore;
    private final PeerQuarantineStore quarantine;
    private final TombstoneService tombstones;
    private final Set<String> pendingLogged = ConcurrentHashMap.newKeySet();
    private final Set<String> tombstoneLogged = ConcurrentHashMap.newKeySet();

    PeerTrustGate(NetworkManager network, Logger logger, PeerTrustStore trustStore, PeerQuarantineStore quarantine, TombstoneService tombstones) {
        this.network = network;
        this.logger = logger;
        this.trustStore = trustStore;
        this.quarantine = quarantine;
        this.tombstones = tombstones;
    }

    byte[] key(String peerName) {
        return trustStore.get(peerName);
    }

    PublicKey publicKey(String peerName) {
        return trustStore.getPublicKey(peerName);
    }

    void trustPeer(String peerName, String publicKey) {
        byte[] decoded = Handshake.decodePublicKeyText(publicKey);
        if (decoded == null) {
            throw new IllegalArgumentException("Invalid public key for " + peerName);
        }
        trustStore.trustOrReplace(peerName, decoded);
    }

    /**
     * Trust from an introduction (the proxy roster): refuses a name under a live tombstone and refuses
     * to replace a key an operator already pinned. Returns true when the key is trusted afterwards.
     */
    boolean trustIntroduced(String peerName, String publicKey) {
        byte[] decoded = Handshake.decodePublicKeyText(publicKey);
        if (decoded == null) {
            return false;
        }
        byte[] trustedKey = trustStore.get(peerName);
        if (trustedKey != null) {
            if (Handshake.sameKey(trustedKey, decoded)) {
                return true;
            }
            logger.warning("net: refusing introduced key for " + peerName + ", it differs from the trusted one");
            return false;
        }
        NetworkConfig active = network.activeConfig();
        if (tombstones.blocksKey(peerName, decoded, System.currentTimeMillis(), MeshHandlers.tombstoneTtlMillis(active.mesh))) {
            if (tombstoneLogged.add(peerName)) {
                logger.warning("net: refusing introduced peer " + peerName + ", it was removed network-wide; /wh server import <code> re-links it");
            }
            return false;
        }
        trustStore.trust(peerName, decoded);
        return true;
    }

    void trustKey(String peerName, byte[] publicKey) {
        trustStore.trustOrReplace(peerName, publicKey);
        pendingLogged.remove(peerName);
        tombstoneLogged.remove(peerName);
    }

    boolean forgetPeer(String peerName) {
        pendingLogged.remove(peerName);
        return trustStore.remove(peerName);
    }

    boolean approveConnection(String peerName, byte[] publicKey) {
        return approve(peerName, publicKey, "peer");
    }

    boolean approveSideband(String peerName, byte[] publicKey) {
        return approve(peerName, publicKey, "status sideband peer");
    }

    /**
     * Trust tiers, in order: a pinned key must match; a network-wide tombstone for this key refuses
     * the link until an operator re-imports the peer; an introduced (quarantined) key is promoted only
     * under auto-accept and otherwise waits for /wh network accept; finally trust-on-first-use.
     */
    private boolean approve(String peerName, byte[] publicKey, String label) {
        NetworkConfig active = network.activeConfig();
        byte[] trustedKey = trustStore.get(peerName);
        if (trustedKey != null) {
            if (Handshake.sameKey(trustedKey, publicKey)) {
                return true;
            }
            logger.warning("net: rejecting " + label + " " + peerName + " because its public key changed");
            return false;
        }
        long now = System.currentTimeMillis();
        if (tombstones.blocksKey(peerName, publicKey, now, MeshHandlers.tombstoneTtlMillis(active.mesh))) {
            if (tombstoneLogged.add(peerName)) {
                logger.warning("net: rejecting " + label + " " + peerName + " because it was removed network-wide; /wh server import <code> re-links it");
            }
            return false;
        }
        PeerQuarantineStore.Entry quarantined = quarantine.get(peerName);
        if (quarantined != null) {
            if (!Handshake.sameKey(quarantined.announce().publicKey(), publicKey)) {
                logger.warning("net: rejecting " + label + " " + peerName + " because its key differs from the introduced key");
                return false;
            }
            if (active.mesh.autoAcceptIntroductions) {
                network.mesh().acceptQuarantined(peerName);
                return true;
            }
            if (pendingLogged.add(peerName)) {
                logger.info("net: " + label + " " + peerName + " is waiting for approval: /wh network accept " + peerName);
            }
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
        logger.warning("net: rejecting " + label + " " + peerName + " because its public key did not match stored trust");
        return false;
    }
}
