package art.arcane.wormholes.network;

import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.modded.MinecraftJsonDocuments;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.optics.frame.Frame;
import art.arcane.wormholes.portal.RemotePortal;
import art.arcane.optics.math.Face;
import net.minecraft.SharedConstants;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import java.util.stream.Stream;

public final class NativeStatusProbe {
    private NativeStatusProbe() {
    }

    public static CompletableFuture<Boolean> run(WormholesModRuntime runtime) {
        runtime.requireServerThread();
        MinecraftServer server = runtime.server();
        NetworkManager target = runtime.network().manager();
        NetworkConfig previous = target.activeConfig();
        String targetName = target.getLocalName();
        String probeName = "status-probe-" + UUID.randomUUID();
        NetworkConfig enabled = config(targetName);
        enabled.enabled = true;
        target.applyConfig(enabled);
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        Thread.ofVirtual().name("Wormholes-status-probe").start(() -> {
            UUID portalId = UUID.randomUUID();
            Throwable failure = null;
            try {
                exchange(server, target, probeName, portalId);
            } catch (Throwable error) {
                failure = error;
            }
            Throwable outcome = failure;
            server.execute(() -> finish(runtime, previous, probeName, portalId, outcome, result));
        });
        return result;
    }

    private static void exchange(MinecraftServer server, NetworkManager target, String probeName, UUID portalId) throws IOException {
        Path directory = Files.createTempDirectory("wormholes-status-probe-");
        NetworkManager client = null;
        try (ServerSocket reserved = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            client = new NetworkManager(Logger.getLogger("Wormholes-status-probe"), new NetworkManager.Options(config(probeName),
                SharedConstants.getCurrentVersion().name(), "probe", reserved.getLocalPort(), directory,
                MinecraftJsonDocuments.INSTANCE, SharedConstants.getCurrentVersion().protocolVersion()));
            NetworkConfig.PeerEntry route = new NetworkConfig.PeerEntry();
            route.name = target.getLocalName();
            route.host = "127.0.0.1";
            route.port = 0;
            route.publicHost = "127.0.0.1";
            route.publicPort = server.getPort();
            route.privateHost = "127.0.0.1";
            route.privatePort = server.getPort();
            client.savePeer(route);
            Frame frame = Frame.canonical(Face.N);
            PortalInfo info = new PortalInfo(portalId, "Status probe gateway", "minecraft:overworld", "GATEWAY", true,
                frame.getNormal().name(), frame.getRight().name(), frame.getUp().name(),
                0, 64, 0, 0, 64, 0, 2, 67, 0);
            WireMessage directoryMessage = new WireMessage.PortalDirectory(List.of(info));
            MinecraftStatusBridge.StatusPacket request = client.createStatusBridgePacket(target.getLocalName(),
                List.of(new MinecraftStatusBridge.EncodedMessage(directoryMessage, WireCodec.encodeFrame(directoryMessage))));
            MinecraftStatusBridge.StatusPacket response = client.statusBridge().poll(route, request);
            if (response == null || !response.verify() || !target.getLocalName().equals(response.sourceServer())
                || !probeName.equals(response.targetServer()) || response.ackNonce() != request.nonce()) {
                throw new IllegalStateException("Native status response did not authenticate the request");
            }
        } finally {
            if (client != null) {
                client.stop();
            }
            try (Stream<Path> paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
    }

    private static NetworkConfig config(String name) {
        NetworkConfig config = new NetworkConfig();
        config.serverName = name;
        config.listenEnabled = false;
        config.advertiseHostOverride = "127.0.0.1";
        config.transport.udsEnabled = false;
        config.mesh.enabled = false;
        return config;
    }

    private static void finish(WormholesModRuntime runtime, NetworkConfig previous, String probeName,
                               UUID portalId, Throwable failure, CompletableFuture<Boolean> result) {
        try {
            RemotePortal portal = runtime.network().remotePortals().get(probeName, portalId);
            if (failure == null && (portal == null || !"Status probe gateway".equals(portal.getName()))) {
                failure = new IllegalStateException("Signed directory message did not reach the native registry");
            }
            runtime.network().manager().removePeer(probeName);
            runtime.network().remotePortals().removePeer(probeName);
            runtime.network().manager().applyConfig(previous);
        } catch (Throwable cleanupError) {
            if (failure == null) {
                failure = cleanupError;
            } else {
                failure.addSuppressed(cleanupError);
            }
        }
        if (failure == null) {
            result.complete(true);
        } else {
            result.completeExceptionally(failure);
        }
    }
}
