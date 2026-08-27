package art.arcane.wormholes.render;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.bukkit.entity.Player;

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
import io.github.retrooper.packetevents.impl.netty.buffer.ByteBufAllocationOperatorImpl;
import io.github.retrooper.packetevents.impl.netty.buffer.ByteBufOperatorImpl;

final class ProjectedEntityPacketRecorder extends PacketEventsAPI<Object> {
    private final List<PacketWrapper<?>> sent = new ArrayList<PacketWrapper<?>>();
    private final PacketEventsAPI<?> previous;
    private final boolean recordBatchWrites;
    private User batchUser;
    private int batchLookups;
    private int batchFlushes;
    private int packetsAtLastFlush;
    private int failedSendsRemaining;

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
            batchLookups++;
            if (batchUser == null && recordBatchWrites) {
                batchUser = new RecordingUser();
            }
            return batchUser;
        }

        @Override
        public void sendPacket(Object player, PacketWrapper<?> wrapper) {
            failSendIfRequested();
            sent.add(wrapper);
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

    private ProjectedEntityPacketRecorder(PacketEventsAPI<?> previous, boolean recordBatchWrites) {
        this.previous = previous;
        this.recordBatchWrites = recordBatchWrites;
        this.batchUser = null;
    }

    static ProjectedEntityPacketRecorder install() {
        PacketEventsAPI<?> previous = PacketEvents.getAPI();
        ProjectedEntityPacketRecorder recorder = new ProjectedEntityPacketRecorder(previous, false);
        PacketEvents.setAPI(recorder);
        return recorder;
    }

    static ProjectedEntityPacketRecorder installWithBatchUser() {
        PacketEventsAPI<?> previous = PacketEvents.getAPI();
        ProjectedEntityPacketRecorder recorder = new ProjectedEntityPacketRecorder(previous, true);
        PacketEvents.setAPI(recorder);
        return recorder;
    }

    void uninstall() {
        PacketEvents.setAPI(previous);
    }

    List<PacketWrapper<?>> sent() {
        return sent;
    }

    int batchLookups() {
        return batchLookups;
    }

    int batchFlushes() {
        return batchFlushes;
    }

    int packetsAtLastFlush() {
        return packetsAtLastFlush;
    }

    void failNextSend() {
        failedSendsRemaining++;
    }

    void failNextSends(int count) {
        failedSendsRemaining += Math.max(0, count);
    }

    <T extends PacketWrapper<?>> List<T> sentOfType(Class<T> type) {
        List<T> matches = new ArrayList<T>();
        for (PacketWrapper<?> wrapper : sent) {
            if (type.isInstance(wrapper)) {
                matches.add(type.cast(wrapper));
            }
        }
        return matches;
    }

    private void failSendIfRequested() {
        if (failedSendsRemaining <= 0) {
            return;
        }
        failedSendsRemaining--;
        throw new IllegalStateException("injected packet send failure");
    }

    private final class RecordingUser extends User {
        private RecordingUser() {
            super(null, ConnectionState.PLAY, ClientVersion.UNKNOWN,
                new UserProfile(UUID.randomUUID(), "Observer"));
        }

        @Override
        public void writePacket(PacketWrapper<?> wrapper) {
            failSendIfRequested();
            sent.add(wrapper);
        }

        @Override
        public void flushPackets() {
            batchFlushes++;
            packetsAtLastFlush = sent.size();
        }
    }

    static Player player(boolean online) {
        UUID id = UUID.randomUUID();
        InvocationHandler handler = (proxy, method, args) -> {
            String name = method.getName();
            if ("isOnline".equals(name)) {
                return Boolean.valueOf(online);
            }
            if ("getUniqueId".equals(name)) {
                return id;
            }
            if ("getName".equals(name)) {
                return "Observer";
            }
            if ("toString".equals(name)) {
                return "Observer";
            }
            if ("hashCode".equals(name)) {
                return Integer.valueOf(id.hashCode());
            }
            if ("equals".equals(name)) {
                return Boolean.valueOf(proxy == args[0]);
            }
            return null;
        };
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] { Player.class }, handler);
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
}
