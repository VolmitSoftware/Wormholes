package art.arcane.wormholes.modded.clientview;

import art.arcane.wormholes.modded.MinecraftClientProfiles;
import art.arcane.optics.stream.ViewStreamInbound;
import art.arcane.optics.stream.ViewStreamPhase;
import art.arcane.wormholes.render.client.session.ClientViewServerSession;
import art.arcane.wormholes.render.client.session.ClientViewSessionRegistry;
import art.arcane.optics.stream.ViewStreamSessionState;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ConfigurationTask;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public final class MinecraftClientViewNegotiator {
    public static final ConfigurationTask.Type TASK_TYPE = new ConfigurationTask.Type("wormholes:client_view");

    private final ClientViewSessionRegistry<MinecraftClientViewPeer, BlockState> registry;
    private final ConcurrentHashMap<Connection, MinecraftClientViewPeer> peers;

    public MinecraftClientViewNegotiator(ClientViewSessionRegistry<MinecraftClientViewPeer, BlockState> registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.peers = new ConcurrentHashMap<>();
    }

    public ConfigurationTask configurationTask(UUID id, String name, Connection connection, BooleanSupplier channelPresent) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(channelPresent, "channelPresent");
        if (!registry.enabled() || !registry.options().configurationHandshake()) {
            return null;
        }
        return new Task(id, name, connection, channelPresent);
    }

    public boolean offerPlay(UUID id, String name, Connection connection) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(connection, "connection");
        if (!registry.enabled()) {
            peers.putIfAbsent(connection, new MinecraftClientViewPeer(id, name, connection));
            return false;
        }
        ClientViewServerSession<MinecraftClientViewPeer, BlockState> session = registry.session(id);
        if (session != null && session.player().connection() == connection && session.player().offered()) {
            return false;
        }
        if (session == null || session.player().connection() != connection) {
            session = open(id, name, connection);
            if (session == null) {
                return false;
            }
        }
        return offer(session, ViewStreamPhase.PLAY);
    }

    public int reoffer() {
        if (!registry.enabled()) {
            return 0;
        }
        int offered = 0;
        for (MinecraftClientViewPeer peer : List.copyOf(peers.values())) {
            ClientViewServerSession<MinecraftClientViewPeer, BlockState> session = registry.session(peer.id());
            if (!peer.connected() || (session != null && session.state() != ViewStreamSessionState.VANILLA)) {
                continue;
            }
            ClientViewServerSession<MinecraftClientViewPeer, BlockState> opened = open(peer.id(), peer.name(), peer.connection());
            if (opened != null && offer(opened, ViewStreamPhase.PLAY)) {
                offered++;
            }
        }
        return offered;
    }

    public ViewStreamInbound receive(Connection connection, byte[] payload) {
        MinecraftClientViewPeer peer = peers.get(connection);
        if (peer == null) {
            return ViewStreamInbound.IGNORED;
        }
        ClientViewServerSession<MinecraftClientViewPeer, BlockState> session = registry.session(peer.id());
        if (session == null || session.player() != peer) {
            return ViewStreamInbound.IGNORED;
        }
        return session.receive(payload, 0, payload.length);
    }

    public ClientViewServerSession<MinecraftClientViewPeer, BlockState> session(UUID id) {
        return registry.session(id);
    }

    public MinecraftClientViewPeer peer(Connection connection) {
        return peers.get(connection);
    }

    public void disconnected(UUID id, Connection connection) {
        MinecraftClientViewPeer peer = peers.remove(connection);
        ClientViewServerSession<MinecraftClientViewPeer, BlockState> session = registry.session(id);
        if (session != null && (session.player() == peer || session.player().connection() == connection)) {
            registry.forget(id);
        }
    }

    public int prune() {
        int pruned = 0;
        Iterator<Map.Entry<Connection, MinecraftClientViewPeer>> iterator = peers.entrySet().iterator();
        while (iterator.hasNext()) {
            MinecraftClientViewPeer peer = iterator.next().getValue();
            if (peer.connected()) {
                continue;
            }
            iterator.remove();
            ClientViewServerSession<MinecraftClientViewPeer, BlockState> session = registry.session(peer.id());
            if (session != null && session.player() == peer) {
                registry.forget(peer.id());
            }
            pruned++;
        }
        return pruned;
    }

    public int peers() {
        return peers.size();
    }

    public void clear() {
        peers.clear();
    }

    private ClientViewServerSession<MinecraftClientViewPeer, BlockState> open(UUID id, String name, Connection connection) {
        ClientViewServerSession<MinecraftClientViewPeer, BlockState> existing = registry.session(id);
        if (existing != null && existing.player().connection() != connection && existing.player().connected()) {
            return null;
        }
        if (existing != null) {
            peers.remove(existing.player().connection(), existing.player());
        }
        MinecraftClientViewPeer peer = new MinecraftClientViewPeer(id, name, connection);
        long nonce = connection.isMemoryConnection() ? LocalPlateHandles.nonce() : 0L;
        ClientViewServerSession<MinecraftClientViewPeer, BlockState> session = registry.open(id, peer, nonce);
        peers.put(connection, peer);
        return session;
    }

    private static boolean offer(ClientViewServerSession<MinecraftClientViewPeer, BlockState> session, ViewStreamPhase phase) {
        String brand = MinecraftClientProfiles.brand(session.player().connection());
        if (brand != null) {
            session.brand(brand);
        }
        if (!session.offer(phase)) {
            return false;
        }
        session.player().markOffered();
        return true;
    }

    private final class Task implements ConfigurationTask {
        private final UUID id;
        private final String name;
        private final Connection connection;
        private final BooleanSupplier channelPresent;
        private volatile ClientViewServerSession<MinecraftClientViewPeer, BlockState> session;

        private Task(UUID id, String name, Connection connection, BooleanSupplier channelPresent) {
            this.id = id;
            this.name = name;
            this.connection = connection;
            this.channelPresent = channelPresent;
        }

        @Override
        public void start(Consumer<Packet<?>> sender) {
            if (!registry.enabled() || !channelPresent.getAsBoolean()) {
                return;
            }
            ClientViewServerSession<MinecraftClientViewPeer, BlockState> opened = open(id, name, connection);
            if (opened != null && offer(opened, ViewStreamPhase.CONFIGURATION)) {
                session = opened;
            }
        }

        @Override
        public boolean tick() {
            ClientViewServerSession<MinecraftClientViewPeer, BlockState> current = session;
            return current == null || current.expire() != ViewStreamSessionState.PENDING;
        }

        @Override
        public Type type() {
            return TASK_TYPE;
        }
    }
}
