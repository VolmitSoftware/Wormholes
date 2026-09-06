package art.arcane.wormholes.network;

import java.lang.reflect.Proxy;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.injector.ChannelInjector;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.manager.protocol.ProtocolManager;
import com.github.retrooper.packetevents.manager.server.ServerManager;
import com.github.retrooper.packetevents.netty.NettyManager;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;

final class ClientProtocolFixture extends PacketEventsAPI<Object> implements AutoCloseable {
    private final PacketEventsAPI<?> previous;
    private ClientVersion version;
    private final PlayerManager playerManager = (PlayerManager) Proxy.newProxyInstance(
        PlayerManager.class.getClassLoader(), new Class<?>[] {PlayerManager.class},
        (proxy, method, arguments) -> {
            if (method.getName().equals("getClientVersion")) {
                return version;
            }
            throw new UnsupportedOperationException(method.getName());
        });

    ClientProtocolFixture(ClientVersion version) {
        this.previous = PacketEvents.getAPI();
        this.version = version;
        PacketEvents.setAPI(this);
    }

    void version(ClientVersion version) {
        this.version = version;
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
        return null;
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

