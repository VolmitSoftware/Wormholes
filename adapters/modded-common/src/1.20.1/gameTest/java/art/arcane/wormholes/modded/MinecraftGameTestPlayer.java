package art.arcane.wormholes.modded;

import com.mojang.authlib.GameProfile;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

public record MinecraftGameTestPlayer(WormholesModRuntime runtime, ServerPlayer player, EmbeddedChannel channel) implements AutoCloseable {
    private static final Set<MinecraftGameTestPlayer> CONNECTED = new LinkedHashSet<>();
    private static Consumer<Connection> connectionSetup = connection -> { };
    private static UnaryOperator<Packet<?>> clientboundPackets = UnaryOperator.identity();

    public static void configureConnections(Consumer<Connection> setup) {
        connectionSetup = Objects.requireNonNull(setup);
    }

    static void prepare(Connection connection) {
        connectionSetup.accept(connection);
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
        GameProfile profile = new GameProfile(UUIDUtil.createOfflinePlayerUUID(name), name);
        ServerPlayer player = new TestPlayer(level, profile);
        player.setNoGravity(true);
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelInitializer<EmbeddedChannel>() {
            @Override
            protected void initChannel(EmbeddedChannel channel) {
                channel.pipeline().addLast("packet_handler", connection);
            }
        });
        try {
            connection.setProtocol(ConnectionProtocol.PLAY);
            prepare(connection);
            runtime.server().getPlayerList().placeNewPlayer(connection, player);
            MinecraftGameTestPlayer connected = new MinecraftGameTestPlayer(runtime, player, channel);
            connected.acknowledgePosition();
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
            if (pending instanceof ClientboundPlayerPositionPacket position) {
                latest = position;
                packets.remove();
                ReferenceCountUtil.release(pending);
            }
        }
        if (latest == null) {
            return false;
        }
        MinecraftGameTestAccess.acknowledge(player, latest);
        return true;
    }

    public List<Object> drainPackets() {
        channel.runPendingTasks();
        channel.flushOutbound();
        List<Object> packets = new ArrayList<>();
        Iterator<Object> pending = channel.outboundMessages().iterator();
        while (pending.hasNext()) {
            Object message = pending.next();
            if (message instanceof ClientboundBundlePacket bundle) {
                for (Packet<?> packet : bundle.subPackets()) {
                    packets.add(clientboundPackets.apply(packet));
                }
            } else {
                packets.add(message instanceof Packet<?> decoded ? clientboundPackets.apply(decoded) : message);
            }
            pending.remove();
            ReferenceCountUtil.release(message);
        }
        return packets;
    }

    public void leavePlayerList() {
        CONNECTED.remove(this);
        runtime.server().getPlayerList().remove(player);
    }

    @Override
    public void close() {
        CONNECTED.remove(this);
        runtime.playerDisconnected(player);
        runtime.server().getPlayerList().remove(player);
        channel.finishAndReleaseAll();
    }

    private static final class TestPlayer extends ServerPlayer {
        private final List<Component> messages = new ArrayList<>();

        private TestPlayer(ServerLevel level, GameProfile profile) {
            super(level.getServer(), level, profile);
        }

        @Override
        public void sendSystemMessage(Component message, boolean overlay) {
            messages.add(message);
            super.sendSystemMessage(message, overlay);
        }
    }
}
