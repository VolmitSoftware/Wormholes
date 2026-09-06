package art.arcane.wormholes.network;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.config.toml.NetworkConfig;

import org.bukkit.entity.Player;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Locale;
import java.util.logging.Level;

public final class PlayerTransfer {
    public enum Method {
        DIRECT,
        PROXY
    }

    public static final String PROXY_CHANNEL = "BungeeCord";

    private PlayerTransfer() {
    }

    public static boolean send(Player player, NetworkConfig.PeerEntry peer, String transferMode) {
        return send(player, peer, resolveMethod(peer, transferMode));
    }

    public static boolean send(Player player, NetworkConfig.PeerEntry peer, Method method) {
        return send(player, peer, method, PeerEndpointResolver.playerTransferEndpoint(peer, player.getAddress(), null));
    }

    static boolean send(Player player, NetworkConfig.PeerEntry peer, Method method,
                        GameEndpoint endpoint) {
        if (method == Method.PROXY) {
            return sendViaProxy(player, peer);
        }
        return sendViaTransferPacket(player, peer, endpoint);
    }

    static boolean usesProxy(NetworkConfig.PeerEntry peer, String transferMode) {
        return resolveMethod(peer, transferMode) == Method.PROXY;
    }

    static Method resolveMethod(NetworkConfig.PeerEntry peer, String transferMode) {
        String mode = transferMode == null ? "auto" : transferMode.toLowerCase(Locale.ROOT);
        return mode.equals("proxy") || mode.equals("auto") && peer.useProxy ? Method.PROXY : Method.DIRECT;
    }

    static boolean hasDirectHost(NetworkConfig.PeerEntry peer) {
        return !PeerEndpointResolver.gameEndpoints(peer).isEmpty();
    }

    static boolean supportsClientTransfer(Player player) {
        PacketEventsAPI<?> api = PacketEvents.getAPI();
        if (api == null || api.getPlayerManager() == null) {
            return false;
        }
        try {
            return supportsClientTransfer(api.getPlayerManager().getClientVersion(player));
        } catch (RuntimeException error) {
            logTransferFailure("Could not determine transfer support for " + player.getName(), error);
            return false;
        }
    }

    static boolean supportsClientTransfer(ClientVersion version) {
        return version != null && version != ClientVersion.UNKNOWN
            && version.isNewerThanOrEquals(ClientVersion.V_1_20_5);
    }

    private static boolean sendViaTransferPacket(Player player, NetworkConfig.PeerEntry peer,
                                                 GameEndpoint endpoint) {
        if (!supportsClientTransfer(player)) {
            Wormholes.w("net: direct transfer requires a known Minecraft 1.20.5 or newer client for " + player.getName());
            return false;
        }
        InetSocketAddress clientAddress = player.getAddress();
        if (endpoint == null) {
            Wormholes.w("net: peer " + peer.name + " has no reachable host; cannot transfer " + player.getName());
            return false;
        }
        Wormholes.v("[xfer] transfer-packet " + player.getName()
            + " client=" + formatAddress(clientAddress)
            + " localClient=" + PeerEndpointResolver.isLocalClient(clientAddress)
            + " selected=" + endpoint
            + " peer=" + peer.name
            + " peerHost=" + peer.host
            + " fallbackHosts=" + peer.fallbackHosts
            + " publicHost=" + peer.publicHost
            + " publicPort=" + peer.publicPort);
        try {
            player.transfer(endpoint.host(), endpoint.port());
            return true;
        } catch (RuntimeException error) {
            logTransferFailure("Direct transfer of " + player.getName() + " to " + peer.name + " failed", error);
            return false;
        }
    }

    private static boolean sendViaProxy(Player player, NetworkConfig.PeerEntry peer) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(64);
            DataOutputStream out = new DataOutputStream(buffer);
            out.writeUTF("Connect");
            out.writeUTF(peer.name);
            player.sendPluginMessage(Wormholes.instance, PROXY_CHANNEL, buffer.toByteArray());
            return true;
        } catch (IOException | RuntimeException error) {
            logTransferFailure("Proxy transfer of " + player.getName() + " to " + peer.name + " failed", error);
            return false;
        }
    }

    private static void logTransferFailure(String message, Throwable error) {
        if (Wormholes.instance == null) {
            Wormholes.w(message + ": " + error);
            return;
        }
        Wormholes.instance.getLogger().log(Level.WARNING, message, error);
    }

    private static String formatAddress(InetSocketAddress address) {
        if (address == null) {
            return "-";
        }
        return address.getHostString() + ":" + address.getPort();
    }
}
