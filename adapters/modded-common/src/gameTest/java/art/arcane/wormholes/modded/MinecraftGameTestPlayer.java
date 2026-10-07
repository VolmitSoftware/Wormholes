package art.arcane.wormholes.modded;

import com.mojang.authlib.GameProfile;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import net.minecraft.network.Connection;
import net.minecraft.network.HiddenByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundChunkBatchReceivedPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.NameAndId;

import java.util.Objects;
import java.util.ArrayList;
import java.util.List;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.network.chat.Component;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

public record MinecraftGameTestPlayer(WormholesModRuntime runtime, ServerPlayer player, EmbeddedChannel channel) implements AutoCloseable {
    private static final float CHUNKS_PER_TICK = 64.0F;
    private static final Set<MinecraftGameTestPlayer> CONNECTED = new LinkedHashSet<>();
    private static Consumer<Connection> connectionSetup = connection -> { };
    private static UnaryOperator<Packet<?>> clientboundPackets = UnaryOperator.identity();

    public static void configureConnections(Consumer<Connection> setup) {
        connectionSetup = Objects.requireNonNull(setup);
    }

    public static void configureClientboundPackets(UnaryOperator<Packet<?>> translation) {
        clientboundPackets = Objects.requireNonNull(translation);
    }

    public static void closeConnected() {
        for (MinecraftGameTestPlayer connected : List.copyOf(CONNECTED)) {
            connected.close();
        }
    }

    public static MinecraftGameTestPlayer connect(WormholesModRuntime runtime, ServerLevel level, String name) {
        NameAndId identity = NameAndId.createOffline(name);
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(new GameProfile(identity.id(), identity.name()), false);
        ServerPlayer player = new TestPlayer(level, cookie);
        player.setNoGravity(true);
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelInitializer<EmbeddedChannel>() {
            @Override
            protected void initChannel(EmbeddedChannel channel) {
                Connection.configureInMemoryPipeline(channel.pipeline(), PacketFlow.SERVERBOUND);
                connection.configurePacketHandler(channel.pipeline());
                channel.pipeline().addLast("wormholes-chunk-batches", new ChunkBatchReceipts(runtime, player));
            }
        });
        try {
            connectionSetup.accept(connection);
            connection.setupOutboundProtocol(GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(runtime.server().registryAccess())));
            runtime.server().getPlayerList().placeNewPlayer(connection, player, cookie);
            MinecraftGameTestPlayer connected = new MinecraftGameTestPlayer(runtime, player, channel);
            connected.acknowledgePosition();
            player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
            CONNECTED.add(connected);
            return connected;
        } catch (RuntimeException error) {
            runtime.server().getPlayerList().remove(player);
            channel.finishAndReleaseAll();
            throw error;
        }
    }

    public List<Component> messages() {
        return List.copyOf(((TestPlayer) player).messages);
    }

    public boolean acknowledgePosition() {
        channel.runPendingTasks();
        ClientboundPlayerPositionPacket latest = null;
        Iterator<Object> packets = channel.outboundMessages().iterator();
        while (packets.hasNext()) {
            Object pending = packets.next();
            Object packet = HiddenByteBuf.unpack(pending);
            if (packet instanceof ByteBuf bytes) {
                packet = GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(runtime.server().registryAccess()))
                    .codec().decode(bytes.duplicate());
            }
            if (packet instanceof ClientboundPlayerPositionPacket position) {
                latest = position;
                packets.remove();
                ReferenceCountUtil.release(pending);
            }
        }
        if (latest == null) {
            return false;
        }
        player.connection.handleAcceptTeleportPacket(new ServerboundAcceptTeleportationPacket(latest.id(),
            player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot()));
        return true;
    }

    public List<Object> drainPackets() {
        channel.runPendingTasks();
        channel.flushOutbound();
        List<Object> packets = new ArrayList<>();
        Iterator<Object> pending = channel.outboundMessages().iterator();
        while (pending.hasNext()) {
            Object message = pending.next();
            Object packet = HiddenByteBuf.unpack(message);
            if (packet instanceof ByteBuf bytes) {
                packet = GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(runtime.server().registryAccess()))
                    .codec().decode(bytes.duplicate());
            }
            packets.add(packet instanceof Packet<?> decoded ? clientboundPackets.apply(decoded) : packet);
            pending.remove();
            ReferenceCountUtil.release(message);
        }
        return packets;
    }

    @Override
    public void close() {
        CONNECTED.remove(this);
        runtime.playerDisconnected(player);
        runtime.server().getPlayerList().remove(player);
        channel.finishAndReleaseAll();
    }

    private static final class ChunkBatchReceipts extends ChannelOutboundHandlerAdapter {
        private final WormholesModRuntime runtime;
        private final ServerPlayer player;

        private ChunkBatchReceipts(WormholesModRuntime runtime, ServerPlayer player) {
            this.runtime = runtime;
            this.player = player;
        }

        @Override
        public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
            if (message instanceof ClientboundChunkBatchFinishedPacket) {
                runtime.schedule(this::received, 1L);
            }
            context.write(message, promise);
        }

        private void received() {
            if (!player.hasDisconnected()) {
                player.connection.handleChunkBatchReceived(new ServerboundChunkBatchReceivedPacket(CHUNKS_PER_TICK));
            }
        }
    }

    private static final class TestPlayer extends ServerPlayer {
        private final List<Component> messages = new ArrayList<>();

        @Override
        public void sendSystemMessage(Component message, boolean overlay) {
            messages.add(message);
            super.sendSystemMessage(message, overlay);
        }

        private TestPlayer(ServerLevel level, CommonListenerCookie cookie) {
            super(level.getServer(), level, cookie.gameProfile(), cookie.clientInformation());
        }
    }
}
