package art.arcane.wormholes.render.clientview;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.UserDisconnectEvent;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.configuration.client.WrapperConfigClientPluginMessage;
import com.github.retrooper.packetevents.wrapper.configuration.client.WrapperConfigClientPong;
import com.github.retrooper.packetevents.wrapper.configuration.server.WrapperConfigServerPing;
import com.github.retrooper.packetevents.wrapper.configuration.server.WrapperConfigServerPluginMessage;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPluginMessage;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPluginMessage;

import art.arcane.wormholes.network.client.ClientViewProtocol;
import art.arcane.wormholes.network.client.ClientViewTransport;

public final class PacketEventsClientViewTransport extends PacketListenerAbstract implements ClientViewTransport<ClientViewObserver> {
    public static final int PING_ID = 0x57484356;
    public static final String REGISTER_CHANNEL = "minecraft:register";
    public static final String BRAND_CHANNEL = "minecraft:brand";
    private static final int MAX_BRAND_BYTES = ClientViewProtocol.MAX_STRING_BYTES;
    private static final byte[] REGISTER_PAYLOAD = ClientViewProtocol.CHANNEL.getBytes(StandardCharsets.UTF_8);

    private final Inbound inbound;

    public PacketEventsClientViewTransport(Inbound inbound) {
        super(PacketListenerPriority.LOW);
        this.inbound = Objects.requireNonNull(inbound, "inbound");
    }

    @Override
    public void send(ClientViewObserver observer, byte[] payload) {
        User user = observer.user();
        if (user == null) {
            return;
        }
        user.writePacketSilently(pluginMessage(user, ClientViewProtocol.CHANNEL, payload));
    }

    @Override
    public void flush(ClientViewObserver observer) {
        User user = observer.user();
        if (user != null) {
            user.flushPackets();
        }
    }

    public void register(ClientViewObserver observer) {
        User user = observer.user();
        if (user == null) {
            return;
        }
        user.writePacketSilently(pluginMessage(user, REGISTER_CHANNEL, REGISTER_PAYLOAD.clone()));
        user.flushPackets();
    }

    public boolean ping(ClientViewObserver observer) {
        User user = observer.user();
        if (user == null || user.getEncoderState() != ConnectionState.CONFIGURATION) {
            return false;
        }
        user.writePacketSilently(new WrapperConfigServerPing(PING_ID));
        user.flushPackets();
        return true;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        PacketTypeCommon type = event.getPacketType();
        if (type == PacketType.Configuration.Client.PLUGIN_MESSAGE) {
            WrapperConfigClientPluginMessage message = new WrapperConfigClientPluginMessage(event);
            receive(event, message.getChannelName(), message.getData());
        } else if (type == PacketType.Play.Client.PLUGIN_MESSAGE) {
            WrapperPlayClientPluginMessage message = new WrapperPlayClientPluginMessage(event);
            receive(event, message.getChannelName(), message.getData());
        } else if (type == PacketType.Configuration.Client.PONG) {
            WrapperConfigClientPong pong = new WrapperConfigClientPong(event);
            if (pong.getId() == PING_ID) {
                event.setCancelled(true);
                inbound.pong(event.getUser());
            }
        }
    }

    @Override
    public void onUserDisconnect(UserDisconnectEvent event) {
        User user = event.getUser();
        if (user != null && user.getUUID() != null) {
            inbound.disconnected(user);
        }
    }

    static String readBrand(byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }
        int length = 0;
        int shift = 0;
        int index = 0;
        while (true) {
            if (index >= data.length || shift > 28) {
                return null;
            }
            int next = data[index++] & 0xFF;
            length |= (next & 0x7F) << shift;
            if ((next & 0x80) == 0) {
                break;
            }
            shift += 7;
        }
        if (length < 0 || length > MAX_BRAND_BYTES || index + length > data.length) {
            return null;
        }
        return new String(data, index, length, StandardCharsets.UTF_8);
    }

    private void receive(PacketReceiveEvent event, String channel, byte[] data) {
        User user = event.getUser();
        UUID playerId = user == null ? null : user.getUUID();
        if (ClientViewProtocol.CHANNEL.equals(channel)) {
            event.setCancelled(true);
            if (playerId != null && data != null) {
                inbound.payload(user, data);
            }
            return;
        }
        if (playerId != null && BRAND_CHANNEL.equals(channel)) {
            String brand = readBrand(data);
            if (brand != null) {
                inbound.brand(user, brand);
            }
        }
    }

    private static PacketWrapper<?> pluginMessage(User user, String channel, byte[] payload) {
        if (user.getEncoderState() == ConnectionState.CONFIGURATION) {
            return new WrapperConfigServerPluginMessage(channel, payload);
        }
        return new WrapperPlayServerPluginMessage(channel, payload);
    }

    public interface Inbound {
        void brand(User user, String brand);

        void payload(User user, byte[] payload);

        void pong(User user);

        void disconnected(User user);
    }
}
