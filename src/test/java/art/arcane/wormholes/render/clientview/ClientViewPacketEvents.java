package art.arcane.wormholes.render.clientview;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.injector.ChannelInjector;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.manager.protocol.ProtocolManager;
import com.github.retrooper.packetevents.manager.server.ServerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.netty.NettyManager;
import com.github.retrooper.packetevents.netty.buffer.ByteBufAllocationOperator;
import com.github.retrooper.packetevents.netty.buffer.ByteBufOperator;
import com.github.retrooper.packetevents.netty.channel.ChannelOperator;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.common.server.WrapperCommonServerPluginMessage;
import com.github.retrooper.packetevents.wrapper.configuration.server.WrapperConfigServerPing;
import com.github.retrooper.packetevents.wrapper.configuration.server.WrapperConfigServerPluginMessage;

import io.github.retrooper.packetevents.impl.netty.buffer.ByteBufAllocationOperatorImpl;
import io.github.retrooper.packetevents.impl.netty.buffer.ByteBufOperatorImpl;

final class ClientViewPacketEvents extends PacketEventsAPI<Object> implements AutoCloseable {
    private final PacketEventsAPI<?> previous;
    private final List<RecordingUser> users = new ArrayList<RecordingUser>();

    private final PlayerManager playerManager = new PlayerManager() {
        @Override
        public int getPing(Object player) {
            return 0;
        }

        @Override
        public ClientVersion getClientVersion(Object player) {
            return ClientVersion.UNKNOWN;
        }

        @Override
        public Object getChannel(Object player) {
            return null;
        }

        @Override
        public User getUser(Object player) {
            for (RecordingUser user : users) {
                if (user.owner == player) {
                    return user;
                }
            }
            return null;
        }
    };

    private final ServerManager serverManager = new ServerManager() {
        @Override
        public ServerVersion getVersion() {
            return ServerVersion.getLatest();
        }
    };

    private final NettyManager nettyManager = new NettyManager() {
        private final ByteBufOperator byteBufOperator = new ByteBufOperatorImpl();
        private final ByteBufAllocationOperator byteBufAllocationOperator = new ByteBufAllocationOperatorImpl();

        @Override
        public ChannelOperator getChannelOperator() {
            return null;
        }

        @Override
        public ByteBufOperator getByteBufOperator() {
            return byteBufOperator;
        }

        @Override
        public ByteBufAllocationOperator getByteBufAllocationOperator() {
            return byteBufAllocationOperator;
        }
    };

    ClientViewPacketEvents() {
        this.previous = PacketEvents.getAPI();
        PacketEvents.setAPI(this);
    }

    RecordingUser user(UUID id, String name, ConnectionState state) {
        RecordingUser user = new RecordingUser(id, name, state);
        users.add(user);
        return user;
    }

    @Override
    public void close() {
        PacketEvents.setAPI(previous);
    }

    @Override
    public boolean isLoaded() {
        return true;
    }

    @Override
    public void init() {
    }

    @Override
    public boolean isInitialized() {
        return true;
    }

    @Override
    public boolean isTerminated() {
        return false;
    }

    @Override
    public Object getPlugin() {
        return null;
    }

    @Override
    public ServerManager getServerManager() {
        return serverManager;
    }

    @Override
    public ProtocolManager getProtocolManager() {
        return null;
    }

    @Override
    public PlayerManager getPlayerManager() {
        return playerManager;
    }

    @Override
    public NettyManager getNettyManager() {
        return nettyManager;
    }

    @Override
    public ChannelInjector getInjector() {
        return null;
    }

    static final class RecordingUser extends User {
        final List<Sent> sent = new ArrayList<Sent>();
        Object owner;
        int flushes;
        int pings;

        private RecordingUser(UUID id, String name, ConnectionState state) {
            super(null, state, ClientVersion.UNKNOWN, new UserProfile(id, name));
            setEncoderState(state);
            setDecoderState(state);
        }

        @Override
        public synchronized void writePacketSilently(PacketWrapper<?> wrapper) {
            if (wrapper instanceof WrapperConfigServerPing) {
                pings++;
                return;
            }
            WrapperCommonServerPluginMessage<?> message = (WrapperCommonServerPluginMessage<?>) wrapper;
            sent.add(new Sent(message.getChannelName(), message.getData(), wrapper instanceof WrapperConfigServerPluginMessage));
        }

        @Override
        public synchronized void flushPackets() {
            flushes++;
        }

        synchronized List<Sent> drain() {
            List<Sent> copy = new ArrayList<Sent>(sent);
            sent.clear();
            return copy;
        }

        synchronized List<Sent> snapshot() {
            return new ArrayList<Sent>(sent);
        }
    }

    record Sent(String channel, byte[] data, boolean configuration) {
    }
}
