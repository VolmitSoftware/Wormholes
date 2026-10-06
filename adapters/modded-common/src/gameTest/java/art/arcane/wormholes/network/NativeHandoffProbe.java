package art.arcane.wormholes.network;

import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.modded.MinecraftJsonDocuments;
import art.arcane.wormholes.modded.MinecraftGameTestPlayer;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.MinecraftProxyPayload;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.optics.crossing.PlaneCrossing;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.transit.TransitionProfile;
import net.minecraft.world.effect.MobEffects;
import com.mojang.authlib.GameProfile;
import io.netty.buffer.Unpooled;
import io.netty.buffer.ByteBuf;
import io.netty.util.ReferenceCountUtil;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.HiddenByteBuf;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import net.minecraft.network.protocol.handshake.ClientIntent;
import net.minecraft.server.network.ServerHandshakePacketListenerImpl;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.stream.Stream;

public final class NativeHandoffProbe {
    private NativeHandoffProbe() {
    }

    public static CompletableFuture<Boolean> run(GameTestHelper helper, WormholesModRuntime runtime) {
        MinecraftServer server = runtime.server();
        NameAndId identity = NameAndId.createOffline("HandoffProbe");
        BlockPos anchor = helper.absolutePos(new BlockPos(8, 3, 8));
        MinecraftPortal portal = runtime.portals().create(identity.id(), helper.getLevel(),
            List.of(anchor, anchor.above(), anchor.east(), anchor.east().above()), PortalType.GATEWAY, new Vec3(0, 0, -1));
        portal.setTransitionProfile(TransitionProfile.NONE.withMaskOverrideTicks(100));
        PlaneCrossing crossing = new PlaneCrossing(portal.getFrame(), portal.getOrigin(),
            portal.getOrigin().add(new art.arcane.optics.math.Vec3(0, 0, 0.2D)), new art.arcane.optics.math.Vec3(0, 0, 0.1D),
            new art.arcane.optics.math.Vec3(0, 0, -1), true);
        NetworkManager target = runtime.network().manager();
        NetworkConfig previous = target.activeConfig();
        String peer = "handoff-probe-" + UUID.randomUUID();
        NetworkConfig enabled = config(target.getLocalName());
        target.applyConfig(enabled);
        target.statusPollInFlight.add(peer);
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        Thread.ofVirtual().name("Wormholes-handoff-probe").start(() -> {
            Throwable failure = null;
            MinecraftGameTestPlayer[] joined = new MinecraftGameTestPlayer[1];
            try {
                exchange(runtime, target, peer, identity, portal, crossing, joined);
            } catch (Throwable error) {
                failure = error;
            }
            Throwable outcome = failure;
            server.execute(() -> {
                Throwable finalFailure = outcome;
                try {
                    if (joined[0] != null) {
                        joined[0].close();
                    }
                    runtime.network().viewServer().peerDisconnected(peer);
                    target.statusPollInFlight.remove(peer);
                    target.removePeer(peer);
                    runtime.network().remotePortals().removePeer(peer);
                    target.applyConfig(previous);
                    ServerPlayer owner = joined[0] == null ? new ServerPlayer(server, helper.getLevel(),
                        new GameProfile(identity.id(), identity.name()), CommonListenerCookie.createInitial(
                            new GameProfile(identity.id(), identity.name()), false).clientInformation()) : joined[0].player();
                    runtime.portals().remove(owner, portal.getId());
                } catch (Throwable error) {
                    if (finalFailure == null) {
                        finalFailure = error;
                    } else {
                        finalFailure.addSuppressed(error);
                    }
                }
                if (finalFailure == null) {
                    LoggerFactory.getLogger("WormholesGameTest").info(
                        "WORMHOLES_GAME_TEST_PASS player_handoff signed_admission replay_ack native_join portal_arrival receipt transfer_handshake proxy_payload_send proxy_payload_codec");
                    result.complete(true);
                } else {
                    result.completeExceptionally(finalFailure);
                }
            });
        });
        return result;
    }

    private static void exchange(WormholesModRuntime runtime, NetworkManager target, String peer, NameAndId identity,
                                 MinecraftPortal portal, PlaneCrossing crossing, MinecraftGameTestPlayer[] joined) throws Exception {
        MinecraftServer server = runtime.server();
        Path directory = Files.createTempDirectory("wormholes-handoff-probe-");
        NetworkManager client = null;
        UUID transfer = UUID.randomUUID();
        try (ServerSocket reserved = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            client = new NetworkManager(Logger.getLogger("Wormholes-handoff-probe"), new NetworkManager.Options(config(peer),
                SharedConstants.getCurrentVersion().name(), "probe", reserved.getLocalPort(), directory,
                MinecraftJsonDocuments.INSTANCE, SharedConstants.getCurrentVersion().protocolVersion()));
            Queue<WireMessage> received = new ConcurrentLinkedQueue<>();
            Queue<WireMessage> outgoing = new ConcurrentLinkedQueue<>();
            client.setMessageSink((sender, message) -> received.add(message));
            NetworkConfig.PeerEntry route = new NetworkConfig.PeerEntry();
            route.name = target.getLocalName();
            route.host = "127.0.0.1";
            route.publicHost = "127.0.0.1";
            route.privateHost = "127.0.0.1";
            route.publicPort = server.getPort();
            route.privatePort = server.getPort();
            client.savePeer(route);
            client.statusPollInFlight.add(route.name);
            client.start();
            NetworkManager source = client;
            WireMessage.HandoffRequest request = new WireMessage.HandoffRequest(transfer, identity.id(), identity.name(),
                portal.getId(), true, false, true, WireTraversive.fromCrossing(crossing));
            outgoing.add(request);
            await(() -> received.stream().anyMatch(message -> message instanceof WireMessage.HandoffAck ack && ack.transferId().equals(transfer)),
                () -> pollAdmission(source, route, outgoing, received, transfer), "destination terrain admission");
            received.clear();
            outgoing.add(request);
            await(() -> received.stream().anyMatch(message -> message instanceof WireMessage.HandoffAck ack && ack.transferId().equals(transfer)),
                () -> pollAdmission(source, route, outgoing, received, transfer), "duplicate admission acknowledgement");
            onServer(server, () -> {
                joined[0] = MinecraftGameTestPlayer.connect(runtime, runtime.portals().resolveLevel(portal), identity.name());
                return true;
            });
            await(() -> received.stream().anyMatch(message -> message instanceof WireMessage.HandoffResult receipt
                    && receipt.transferId().equals(transfer) && receipt.arrived()),
                () -> poll(source, route, outgoing), "native joined player arrival receipt");
            onServer(server, () -> {
                art.arcane.optics.math.Vec3 expected = crossing.outPoint(portal.getFrame(), portal.getOrigin());
                if (joined[0].player().position().distanceToSqr(expected.x(), expected.y(), expected.z()) > 0.04D) {
                    throw new IllegalStateException("Native joined traveler was not placed at the portal crossing");
                }
                if (!joined[0].player().hasEffect(MobEffects.DARKNESS)
                    || joined[0].player().getEffect(MobEffects.DARKNESS).getDuration() > 100) {
                    throw new IllegalStateException("Native joined traveler did not receive the configured arrival mask");
                }
                if (runtime.network().handoffs().hasAdmission(identity.id())) {
                    throw new IllegalStateException("Placed traveler retained an active admission");
                }
                proxyDispatch(joined[0]);
                proxyCodec(server);
                transferHandshake(server);
                return true;
            });
            received.clear();
            outgoing.add(new WireMessage.HandoffStatus(transfer, identity.id()));
            await(() -> received.stream().anyMatch(message -> message instanceof WireMessage.HandoffResult receipt
                    && receipt.transferId().equals(transfer) && receipt.arrived()),
                () -> poll(source, route, outgoing), "persisted arrival receipt replay");
        } finally {
            onServer(server, () -> {
                runtime.network().handoffs().receive(peer, new WireMessage.HandoffCancel(transfer, identity.id()));
                return true;
            });
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

    private static void transferHandshake(MinecraftServer server) {
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelInitializer<EmbeddedChannel>() {
            @Override
            protected void initChannel(EmbeddedChannel channel) {
                Connection.configureInMemoryPipeline(channel.pipeline(), PacketFlow.SERVERBOUND);
                connection.configurePacketHandler(channel.pipeline());
            }
        });
        try {
            ServerHandshakePacketListenerImpl handshake = new ServerHandshakePacketListenerImpl(server, connection);
            connection.setListenerForServerboundHandshake(handshake);
            handshake.handleIntention(new ClientIntentionPacket(SharedConstants.getCurrentVersion().protocolVersion(),
                "127.0.0.1", server.getPort(), ClientIntent.TRANSFER));
            if (!connection.isConnected() || !(connection.getPacketListener() instanceof ServerLoginPacketListenerImpl)) {
                throw new IllegalStateException("Native server refused its configured transfer handshake");
            }
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static void proxyDispatch(MinecraftGameTestPlayer joined) {
        Object pending;
        while ((pending = joined.channel().readOutbound()) != null) {
            ReferenceCountUtil.release(pending);
        }
        MinecraftProxyPayload outgoing = MinecraftProxyPayload.connect("fixture-destination");
        joined.player().connection.send(new ClientboundCustomPayloadPacket(outgoing));
        joined.channel().runPendingTasks();
        joined.channel().checkException();
        boolean sent = false;
        while ((pending = joined.channel().readOutbound()) != null) {
            try {
                Object packet = HiddenByteBuf.unpack(pending);
                if (packet instanceof ByteBuf bytes) {
                    packet = GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(joined.runtime().server().registryAccess()))
                        .codec().decode(bytes.duplicate());
                }
                if (packet instanceof ClientboundCustomPayloadPacket payload && payload.payload() instanceof MinecraftProxyPayload proxy
                    && Arrays.equals(proxy.data(), outgoing.data())) {
                    sent = true;
                }
            } finally {
                ReferenceCountUtil.release(pending);
            }
        }
        if (!sent) {
            throw new IllegalStateException("Proxy dispatch did not emit the BungeeCord payload");
        }
    }

    private static void proxyCodec(MinecraftServer server) {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), server.registryAccess());
        try {
            ClientboundCustomPayloadPacket.GAMEPLAY_STREAM_CODEC.encode(buffer, new ClientboundCustomPayloadPacket(MinecraftProxyPayload.connect("fixture-destination")));
            if (!buffer.readIdentifier().equals(MinecraftProxyPayload.TYPE.id())) {
                throw new IllegalStateException("Proxy payload did not keep the BungeeCord channel");
            }
            byte[] body = new byte[buffer.readableBytes()];
            buffer.readBytes(body);
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(body));
            if (!input.readUTF().equals("Connect") || !input.readUTF().equals("fixture-destination") || input.available() != 0) {
                throw new IllegalStateException("Proxy payload did not preserve its wire body");
            }
        } catch (Exception error) {
            throw new IllegalStateException("Native proxy payload codec failed", error);
        } finally {
            buffer.release();
        }
    }

    private static <T> T onServer(MinecraftServer server, Supplier<T> action) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        server.execute(() -> {
            try {
                result.complete(action.get());
            } catch (Throwable error) {
                result.completeExceptionally(error);
            }
        });
        return result.get(10, TimeUnit.SECONDS);
    }

    private static void pollAdmission(NetworkManager client, NetworkConfig.PeerEntry route, Queue<WireMessage> outgoing,
                                      Queue<WireMessage> received, UUID transfer) throws Exception {
        poll(client, route, outgoing);
        for (WireMessage message : received) {
            if (message instanceof WireMessage.HandoffDeny denial && denial.transferId().equals(transfer)) {
                throw new IllegalStateException("Destination denied handoff: " + denial.reason());
            }
        }
    }

    private static void poll(NetworkManager client, NetworkConfig.PeerEntry route, Queue<WireMessage> outgoing) throws Exception {
        WireMessage pending = outgoing.peek();
        List<MinecraftStatusBridge.EncodedMessage> messages = pending == null ? List.of()
            : List.of(new MinecraftStatusBridge.EncodedMessage(pending, WireCodec.encodeFrame(pending)));
        MinecraftStatusBridge.StatusPacket request = client.createStatusBridgePacket(route.name, messages);
        MinecraftStatusBridge.StatusPacket response = client.statusBridge().poll(route, request);
        if (response == null || !client.handleStatusBridgeResponse(route.name, response, 1L)) {
            throw new IllegalStateException("Native handoff response failed authentication");
        }
        if (pending != null && response.ackNonce() == request.nonce()) {
            outgoing.remove(pending);
        }
        Thread.sleep(75L);
    }

    private static void await(BooleanSupplier condition, Step step, String stage) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40L);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException("Timed out waiting for " + stage);
            }
            step.run();
        }
    }

    private static NetworkConfig config(String name) {
        NetworkConfig config = new NetworkConfig();
        config.serverName = name;
        config.enabled = true;
        config.listenEnabled = false;
        config.advertiseHostOverride = "127.0.0.1";
        config.transport.udsEnabled = false;
        config.mesh.enabled = false;
        config.autoAcceptTransfers = true;
        return config;
    }

    @FunctionalInterface
    private interface Step {
        void run() throws Exception;
    }
}
