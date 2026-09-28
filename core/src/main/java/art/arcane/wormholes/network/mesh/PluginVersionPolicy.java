package art.arcane.wormholes.network.mesh;

import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.network.WireCapability;

import java.util.Objects;

/**
 * Replaces the old exact plugin-version gate. {@code exact} keeps identical-version links only;
 * {@code compatible} links any peer whose negotiated capability set still carries the protocol-21
 * baseline and reports which local capabilities the link lost. Never compares version strings for
 * ordering: capability bits are the compatibility contract.
 */
public final class PluginVersionPolicy {
    public record Verdict(boolean accepted, String rejection, long missingCapabilities) {
        public boolean reduced() {
            return missingCapabilities != 0L;
        }
    }

    private PluginVersionPolicy() {
    }

    public static Verdict check(String policy, String localVersion, String peerVersion, long localCapabilities, long negotiatedCapabilities) {
        long missing = localCapabilities & ~negotiatedCapabilities;
        if (NetworkConfig.PLUGIN_VERSION_POLICY_EXACT.equals(policy)) {
            if (!Objects.equals(localVersion, peerVersion)) {
                return new Verdict(false, "Wormholes version mismatch: peer " + peerVersion + ", local " + localVersion, missing);
            }
            return new Verdict(true, null, missing);
        }
        if (!WireCapability.PROTOCOL_21.in(negotiatedCapabilities)) {
            return new Verdict(false, "peer " + peerVersion + " does not share the PROTOCOL_21 baseline capability with local " + localVersion, missing);
        }
        return new Verdict(true, null, missing);
    }

    /** Human list of capability bits: known names, "bit N" for the rest, "none" when empty. */
    public static String describe(long capabilities) {
        if (capabilities == 0L) {
            return "none";
        }
        StringBuilder out = new StringBuilder();
        for (int bit = 0; bit < 64; bit++) {
            long mask = 1L << bit;
            if ((capabilities & mask) == 0L) {
                continue;
            }
            if (out.length() > 0) {
                out.append(", ");
            }
            String name = nameOf(bit);
            out.append(name == null ? "bit " + bit : name);
        }
        return out.toString();
    }

    private static String nameOf(int bit) {
        for (WireCapability capability : WireCapability.values()) {
            if (capability.bit() == bit) {
                return capability.name();
            }
        }
        return null;
    }
}
