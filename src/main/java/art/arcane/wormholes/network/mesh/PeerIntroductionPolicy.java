package art.arcane.wormholes.network.mesh;

import art.arcane.wormholes.config.toml.NetworkConfig;

/**
 * Decides what happens to a signed announce for a peer this server does not trust yet. The
 * introducer is the trusted link the announce arrived on; the policy never trusts an announce whose
 * self-signature fails, and only {@code auto-accept-introductions} turns a valid introduction into
 * trust without an operator.
 */
public final class PeerIntroductionPolicy {
    public enum Decision {
        ACCEPT,
        QUARANTINE,
        REJECT
    }

    private PeerIntroductionPolicy() {
    }

    public static Decision decide(PeerAnnounce announce, String introducer, NetworkConfig.MeshConfig mesh) {
        if (mesh == null || !mesh.enabled || announce == null || introducer == null || introducer.isBlank()) {
            return Decision.REJECT;
        }
        if (!announce.verify() || !mesh.mayIntroduce(introducer)) {
            return Decision.REJECT;
        }
        return mesh.autoAcceptIntroductions ? Decision.ACCEPT : Decision.QUARANTINE;
    }
}
