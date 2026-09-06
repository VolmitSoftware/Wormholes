package art.arcane.wormholes.network;

import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.Wormholes;

import org.bukkit.entity.Player;

import java.util.Locale;

public final class ServerConnectService {
    public enum Result {
        QUEUED,
        UNKNOWN_SERVER,
        NOT_READY,
        TRANSFER_FAILED
    }

    private ServerConnectService() {
    }

    public static String resolveName(NetworkManager network, String requested) {
        if (network == null || requested == null || requested.isBlank()) {
            return null;
        }
        NetworkConfig.PeerEntry exact = network.getPeer(requested);
        if (exact != null) {
            return exact.name;
        }
        String lowered = requested.trim().toLowerCase(Locale.ROOT);
        for (NetworkConfig.PeerEntry peer : network.peers()) {
            if (peer.name != null && peer.name.toLowerCase(Locale.ROOT).equals(lowered)) {
                return peer.name;
            }
        }
        return null;
    }

    public static Result connect(NetworkManager network, Player player, String serverName, String transferMode) {
        NetworkConfig.PeerEntry peer = network.getPeer(serverName);
        if (peer == null) {
            return Result.UNKNOWN_SERVER;
        }
        if (!network.isPeerReady(serverName)) {
            return Result.NOT_READY;
        }
        TraversalService traversal = Wormholes.traversalService;
        if (traversal == null) {
            return Result.TRANSFER_FAILED;
        }
        return traversal.beginServerHandoff(player, serverName, transferMode) ? Result.QUEUED : Result.TRANSFER_FAILED;
    }
}
