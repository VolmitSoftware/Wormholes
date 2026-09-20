package art.arcane.wormholes.render;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

final class ProjectedEntityPacketRecorder extends PacketEventsAPI<Object> {
    private final List<PacketWrapper<?>> sent = new ArrayList<PacketWrapper<?>>();
    private final PacketEventsAPI<?> previous;
    private final boolean recordBatchWrites;
    private ServerVersion serverVersion = ServerVersion.getLatest();
    private User batchUser;
    private int batchLookups;
    private int batchFlushes;
    private int packetsAtLastFlush;
    private int failedFlushesRemaining;
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
            return serverVersion;
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

    void serverVersion(ServerVersion version) {
        serverVersion = version;
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

    void failNextFlush() {
        failedFlushesRemaining++;
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
            if (failedFlushesRemaining > 0) {
                failedFlushesRemaining--;
                throw new IllegalStateException("injected packet flush failure");
            }
            packetsAtLastFlush = sent.size();
        }
    }

    static Player player(boolean online) {
        Player observer = mock(VisiblePlayer.class, withSettings().useConstructor());
        when(observer.isOnline()).thenReturn(online);
        when(observer.getUniqueId()).thenReturn(UUID.randomUUID());
        when(observer.getName()).thenReturn("Observer");
        return observer;
    }

    abstract static class VisiblePlayer implements Player {
        private final Map<UUID, Set<WeakReference<Plugin>>> invertedVisibilityEntities =
            new HashMap<UUID, Set<WeakReference<Plugin>>>();
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
