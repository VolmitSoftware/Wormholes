package art.arcane.wormholes.network;

import art.arcane.wormholes.config.toml.NetworkConfig;

import org.bukkit.entity.Player;

import java.util.Locale;

public final class ServerConnectService {
    public enum Result {
        SENT,
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
        PlayerTransfer.Method method = PlayerTransfer.resolveMethod(peer, transferMode);
        if (method == PlayerTransfer.Method.PROXY) {
            return PlayerTransfer.send(player, peer, method) ? Result.SENT : Result.TRANSFER_FAILED;
        }
        if (!network.isPeerReady(serverName) || !PlayerTransfer.hasDirectHost(peer)) {
            return Result.NOT_READY;
        }
        return PlayerTransfer.send(player, peer, method, network.privatePlayerEndpoint(serverName))
            ? Result.SENT
            : Result.TRANSFER_FAILED;
    }
}
