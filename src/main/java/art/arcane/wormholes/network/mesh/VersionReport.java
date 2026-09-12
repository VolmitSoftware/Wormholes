package art.arcane.wormholes.network.mesh;

import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.network.NetworkManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-peer view for {@code /wh network versions}: protocol and plugin version from the peer's last
 * announce, the capability set negotiated on the live link (or last seen on the sideband), and
 * whether that set is smaller than what this build offers.
 */
public final class VersionReport {
    public record Row(String server, boolean ready, int protocolVersion, String pluginVersion, long capabilities,
                      long localCapabilities, boolean reduced) {
        public String capabilityNames() {
            return PluginVersionPolicy.describe(capabilities);
        }

        /** What this server offers and the peer does not; with a lane switched off here, nothing. */
        public String missingNames() {
            return PluginVersionPolicy.describe(localCapabilities & ~capabilities);
        }
    }

    private VersionReport() {
    }

    public static List<Row> build(NetworkManager network) {
        List<NetworkConfig.PeerEntry> peers = network.peers();
        peers.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.name, b.name));
        long local = network.localCapabilities();
        List<Row> rows = new ArrayList<>(peers.size());
        for (NetworkConfig.PeerEntry peer : peers) {
            MeshMemberTable.Member member = network.members().get(peer.name);
            boolean ready = network.isPeerReady(peer.name);
            long capabilities = network.peerCapabilities(peer.name);
            if (capabilities == 0L && member != null) {
                capabilities = member.capabilities() & local;
            }
            int protocol = member == null ? 0 : member.protocolVersion();
            String plugin = member == null || member.pluginVersion().isBlank() ? "unknown" : member.pluginVersion();
            boolean reduced = capabilities != 0L && (local & ~capabilities) != 0L;
            rows.add(new Row(peer.name, ready, protocol, plugin, capabilities, local, reduced));
        }
        return rows;
    }
}
