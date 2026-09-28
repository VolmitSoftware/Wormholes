package art.arcane.wormholes.modded;

import com.mojang.authlib.GameProfile;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import net.minecraft.network.Connection;
import net.minecraft.network.HiddenByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
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
import net.minecraft.network.chat.Component;
import java.util.function.Consumer;

public record MinecraftGameTestPlayer(WormholesModRuntime runtime, ServerPlayer player, EmbeddedChannel channel) implements AutoCloseable {
    private static Consumer<Connection> connectionSetup = connection -> { };

    public static void configureConnections(Consumer<Connection> setup) {
        connectionSetup = Objects.requireNonNull(setup);
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
            }
        });
        try {
            connectionSetup.accept(connection);
            connection.setupOutboundProtocol(GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(runtime.server().registryAccess())));
            runtime.server().getPlayerList().placeNewPlayer(connection, player, cookie);
            MinecraftGameTestPlayer connected = new MinecraftGameTestPlayer(runtime, player, channel);
            connected.acknowledgePosition();
            player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
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
        player.connection.handleAcceptTeleportPacket(new ServerboundAcceptTeleportationPacket(latest.id()));
        return true;
    }

    @Override
    public void close() {
        runtime.playerDisconnected(player);
        runtime.server().getPlayerList().remove(player);
        channel.finishAndReleaseAll();
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
