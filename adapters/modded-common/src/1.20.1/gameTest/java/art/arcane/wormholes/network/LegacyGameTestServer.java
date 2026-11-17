package art.arcane.wormholes.network;

import art.arcane.wormholes.config.toml.NetworkConfig;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;

/**
 * Bridges what the 1.20.1 GameTestServer lacks for the network probes: it has no game port or network listener, so the probes
 * open a loopback one and name it in the destination config (the runtime network started before it existed).
 */
final class LegacyGameTestServer {
    private LegacyGameTestServer() {
    }

    static void open(MinecraftServer server) {
        if (server.getPort() > 0) {
            return;
        }
        try {
            // The probes dial 127.0.0.1. Name it directly: under -Djava.net.preferIPv6Addresses (Forge's run
            // configuration) the JVM's loopback address is ::1, which the IPv4 probes cannot reach.
            InetAddress loopback = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
            int port;
            try (ServerSocket free = new ServerSocket(0, 1, loopback)) {
                port = free.getLocalPort();
            }
            server.getConnection().startTcpServerListener(loopback, port);
            server.setPort(port);
        } catch (IOException error) {
            throw new IllegalStateException("Could not open the game listener", error);
        }
    }

    static NetworkConfig advertise(MinecraftServer server, NetworkConfig config) {
        config.gamePortOverride = server.getPort();
        config.privateGamePortOverride = server.getPort();
        return config;
    }
}
