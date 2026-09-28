package art.arcane.wormholes.network;

import art.arcane.wormholes.config.toml.NetworkConfig;

import java.util.Locale;

public enum PlayerTransferMethod {
    DIRECT,
    PROXY;

    public static PlayerTransferMethod resolve(NetworkConfig.PeerEntry peer, String transferMode) {
        String mode = transferMode == null ? "auto" : transferMode.toLowerCase(Locale.ROOT);
        return mode.equals("proxy") || mode.equals("auto") && peer.useProxy ? PROXY : DIRECT;
    }

    public static boolean hasDirectHost(NetworkConfig.PeerEntry peer) {
        return !PeerEndpointResolver.gameEndpoints(peer).isEmpty();
    }
}
