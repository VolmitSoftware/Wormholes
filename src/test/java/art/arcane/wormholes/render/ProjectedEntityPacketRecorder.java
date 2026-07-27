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
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;

final class ProjectedEntityPacketRecorder extends PacketEventsAPI<Object> {
    private final List<PacketWrapper<?>> sent = new ArrayList<PacketWrapper<?>>();
    private final PacketEventsAPI<?> previous;
    private int batchLookups;
    private boolean failNextSend;

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
            return null;
        }

        @Override
        public void sendPacket(Object player, PacketWrapper<?> wrapper) {
            if (failNextSend) {
                failNextSend = false;
                throw new IllegalStateException("injected packet send failure");
            }
            sent.add(wrapper);
        }
    };

    private final ServerManager serverManager = new ServerManager() {
        @Override
        public ServerVersion getVersion() {
            return ServerVersion.getLatest();
        }
    };

    private ProjectedEntityPacketRecorder(PacketEventsAPI<?> previous) {
        this.previous = previous;
    }

    static ProjectedEntityPacketRecorder install() {
        PacketEventsAPI<?> previous = PacketEvents.getAPI();
        ProjectedEntityPacketRecorder recorder = new ProjectedEntityPacketRecorder(previous);
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

    void failNextSend() {
        failNextSend = true;
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
        return null;
    }

    @Override
    public ChannelInjector getInjector() {
        return null;
    }
}
