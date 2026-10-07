package art.arcane.wormholes.modded.clientview;

import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.mixin.ServerConnectionAccess;
import art.arcane.optics.stream.ViewStreamCapability;
import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.wormholes.render.client.session.ClientViewEmitters;
import art.arcane.wormholes.render.client.session.ClientPreparedTravelServer;
import art.arcane.wormholes.render.client.session.ClientViewEntityFrames;
import art.arcane.optics.stream.ViewStreamInbound;
import art.arcane.wormholes.render.client.session.ClientViewOptions;
import art.arcane.wormholes.render.client.session.ClientViewPlatform;
import art.arcane.wormholes.render.client.session.ClientViewSceneFx;
import art.arcane.wormholes.render.client.session.ClientViewServerSession;
import art.arcane.optics.stream.ViewStreamSessionState;
import art.arcane.wormholes.render.client.session.ClientViewSessionRegistry;
import com.mojang.authlib.GameProfile;
import net.minecraft.SharedConstants;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ConfigurationTask;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import art.arcane.optics.entity.ProjectedEntityEvent;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

public final class MinecraftClientViewService implements AutoCloseable {
    public static final long PLATFORM_CAPS = ViewStreamCapability.of(ViewStreamCapability.PLATES, ViewStreamCapability.BRICK_CACHE,
        ViewStreamCapability.DEST_LIGHT, ViewStreamCapability.ENTITY_FRAMES, ViewStreamCapability.ENTITY_SELF, ViewStreamCapability.ENTITY_EVENTS, ViewStreamCapability.FX_EMITTERS, ViewStreamCapability.ATMOSPHERE,
        ViewStreamCapability.ZERO_COPY, ViewStreamCapability.CONFIG_PHASE, ViewStreamCapability.LINK_UNCOMPRESSED,
        ViewStreamCapability.VIEW_STATS, ViewStreamCapability.CLIENT_MIRROR, ViewStreamCapability.CLIENT_RECURSION, ViewStreamCapability.MESH_RENDER, ViewStreamCapability.LOCAL_MESH, ViewStreamCapability.MESH_REUSE, ViewStreamCapability.PREPARED_TRAVEL, ViewStreamCapability.PREPARED_TRAVEL_CACHE);
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");
    private static final long HANDLE_PURGE_INTERVAL_TICKS = 20L;
    private static final double PARTICLE_RANGE_SQUARED = 32.0D * 32.0D;

    private final WormholesModRuntime runtime;
    private final MinecraftClientViewTransport transport;
    private final MinecraftClientViewPortalAccess portals;
    private final MinecraftPreparedTravel prepared;
    private final Map<UUID, Seamless> seamless = new HashMap<>();
    private volatile ClientViewSessionRegistry<MinecraftClientViewPeer, BlockState> registry;
    private volatile MinecraftClientViewNegotiator negotiator;
    private ClientViewOptions applied;

    public MinecraftClientViewService(WormholesModRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.transport = new MinecraftClientViewTransport();
        this.portals = new MinecraftClientViewPortalAccess(runtime);
        this.prepared = new MinecraftPreparedTravel(runtime, portals);
    }

    public void packets(Function<ClientViewPayload, Packet<?>> factory) {
        transport.packets(factory);
    }

    public void start() {
        runtime.requireServerThread();
        ClientViewOptions options = runtime.configuration().clientViewOptions();
        ClientViewPlatform<MinecraftClientViewPeer, BlockState> platform = new ClientViewPlatform<>(transport, portals,
            new ClientViewEntityFrames<>(portals.scene()), new ClientViewSceneFx<>(portals.scene()),
            new MinecraftClientViewHandoffs(), runtime.projections().lanes(), BlockStateParser::serialize,
            SharedConstants.getCurrentVersion().dataVersion().version(), PLATFORM_CAPS, System::nanoTime,
            (message, failure) -> LOGGER.warn(message, failure));
        ClientViewSessionRegistry<MinecraftClientViewPeer, BlockState> created = new ClientViewSessionRegistry<>(platform, options);
        applied = options;
        negotiator = new MinecraftClientViewNegotiator(created);
        registry = created;
    }

    public void tick(long serverTick, List<ServerPlayer> players, List<MinecraftPortal> candidates) {
        ClientViewSessionRegistry<MinecraftClientViewPeer, BlockState> active = registry;
        MinecraftClientViewNegotiator current = negotiator;
        if (active == null || current == null) {
            return;
        }
        ClientViewOptions options = runtime.configuration().clientViewOptions();
        if (!options.equals(applied)) {
            applied = options;
            if (active.configure(options)) {
                current.reoffer();
            }
        }
        current.prune();
        seamless.entrySet().removeIf(entry -> entry.getValue().until() <= System.currentTimeMillis());
        if (serverTick % HANDLE_PURGE_INTERVAL_TICKS == 0L) {
            LocalPlateHandles.purgeExpired(System.nanoTime());
        }
        if (active.sessions().isEmpty()) {
            return;
        }
        portals.frame(candidates);
        for (int i = 0; i < players.size(); i++) {
            ServerPlayer player = players.get(i);
            ClientViewServerSession<MinecraftClientViewPeer, BlockState> session = active.session(player.getUUID());
            if (session == null) {
                continue;
            }
            try {
                follow(session, player);
                session.player().meshDepth(ViewStreamCapability.MESH_RENDER.in(session.caps())
                    ? Math.clamp(player.requestedViewDistance(), 2, 32) * 16 : 0);
                session.tick(serverTick);
                prepared.tick(session, player);
            } catch (RuntimeException failure) {
                LOGGER.error("Wormholes ClientView tick failed for {}", player.getUUID(), failure);
                session.end(ClientViewMessage.ResetReason.PROTOCOL);
            }
        }
    }

    public void runtimeEnabled(boolean enabled) {
        ClientViewSessionRegistry<MinecraftClientViewPeer, BlockState> active = registry;
        MinecraftClientViewNegotiator current = negotiator;
        if (active != null && current != null && active.runtimeEnabled(enabled)) {
            current.reoffer();
        }
    }

    public boolean owns(UUID player, UUID portal) {
        ClientViewSessionRegistry<MinecraftClientViewPeer, BlockState> active = registry;
        return active != null && active.owns(player, portal);
    }

    public boolean nativeMesh(ServerPlayer player) {
        ClientViewSessionRegistry<MinecraftClientViewPeer, BlockState> active = registry;
        ClientViewServerSession<MinecraftClientViewPeer, BlockState> session = active == null ? null : active.session(player.getUUID());
        return session != null && session.state() == ViewStreamSessionState.CLIENT_VIEW && ViewStreamCapability.MESH_RENDER.in(session.caps());
    }

    public ClientViewMessage.TravelCommit commitTravel(ServerPlayer player, UUID source, ServerLevel destination,
                                                       ClientViewMessage.TravelPose arrival, Vec3d velocity) {
        runtime.requireServerThread();
        ClientViewSessionRegistry<MinecraftClientViewPeer, BlockState> active = registry;
        ClientViewServerSession<MinecraftClientViewPeer, BlockState> session = active == null ? null : active.session(player.getUUID());
        if (session == null || !session.preparedTravelSelected()) {
            return null;
        }
        ClientViewMessage.TravelCommit commit = prepared.commit(session, player, source, destination, arrival, velocity).orElse(null);
        if (commit != null && session.sendTravel(commit)) {
            seamless.put(player.getUUID(), new Seamless(source, commit.token(), commit.generation(), System.currentTimeMillis() + 2_000L));
            return commit;
        }
        if (commit != null) {
            session.sendTravel(new ClientViewMessage.TravelCancel(commit.token(), commit.generation()));
        }
        session.cancelTravel();
        return null;
    }

    public void cancelTravel(ServerPlayer player, ClientViewMessage.TravelCommit commit) {
        Seamless marked = seamless.get(player.getUUID());
        if (commit == null || marked != null && marked.token().equals(commit.token()) && marked.generation() == commit.generation()) {
            seamless.remove(player.getUUID());
        }
        if (commit == null) {
            prepared.complete(player.getUUID());
        } else {
            prepared.complete(player.getUUID(), commit);
        }
        ClientViewSessionRegistry<MinecraftClientViewPeer, BlockState> active = registry;
        ClientViewServerSession<MinecraftClientViewPeer, BlockState> session = active == null ? null : active.session(player.getUUID());
        if (session != null) {
            if (commit == null) {
                session.cancelTravel();
            } else {
                session.travel().preparing().filter(begin -> begin.token().equals(commit.token())
                    && begin.generation() == commit.generation()).ifPresent(begin -> session.cancelTravel());
                session.sendTravel(new ClientViewMessage.TravelCancel(commit.token(), commit.generation()));
            }
        }
    }

    public void cancelPreparation(ServerPlayer player, ClientViewMessage.TravelBegin expected) {
        if (expected == null) {
            return;
        }
        prepared.complete(player.getUUID(), expected.token(), expected.generation());
        ClientViewSessionRegistry<MinecraftClientViewPeer, BlockState> active = registry;
        ClientViewServerSession<MinecraftClientViewPeer, BlockState> session = active == null ? null : active.session(player.getUUID());
        ClientViewMessage.TravelCancel cancel = new ClientViewMessage.TravelCancel(expected.token(), expected.generation());
        if (session != null && session.travel().cancel(cancel)) {
            session.sendTravel(cancel);
        }
    }

    public void completeTravel(ServerPlayer player) {
        prepared.complete(player.getUUID());
    }

    public boolean seamlessTravel(UUID playerId) {
        Seamless active = seamless.get(playerId);
        if (active == null) {
            return false;
        }
        if (active.until() <= System.currentTimeMillis()) {
            seamless.remove(playerId, active);
            return false;
        }
        return true;
    }

    public boolean seamlessTravel(UUID playerId, UUID sourcePortal) {
        if (seamlessTravel(playerId) && seamless.get(playerId).source().equals(sourcePortal)) {
            return true;
        }
        ClientViewSessionRegistry<MinecraftClientViewPeer, BlockState> active = registry;
        ClientViewServerSession<MinecraftClientViewPeer, BlockState> session = active == null ? null : active.session(playerId);
        return session != null && session.preparedTravelSelected() && session.travel().readyRoute(sourcePortal, System.currentTimeMillis());
    }

    public Optional<ClientViewMessage.TravelBegin> preparation(UUID traveler) {
        ClientViewSessionRegistry<MinecraftClientViewPeer, BlockState> active = registry;
        ClientViewServerSession<MinecraftClientViewPeer, BlockState> session = active == null ? null : active.session(traveler);
        return session == null ? Optional.empty() : session.travel().preparing();
    }

    public boolean crossing(UUID traveler, ClientViewMessage.TravelBegin expected) {
        return expected != null && preparation(traveler).filter(begin -> begin.token().equals(expected.token())
            && begin.generation() == expected.generation()).isPresent() && crossing(traveler);
    }

    public boolean crossing(UUID traveler) {
        ClientViewSessionRegistry<MinecraftClientViewPeer, BlockState> active = registry;
        ClientViewServerSession<MinecraftClientViewPeer, BlockState> session = active == null ? null : active.session(traveler);
        return session != null && session.preparedTravelSelected() && session.travel().crossing();
    }

    public boolean deferTravel(UUID traveler, UUID source) {
        ClientViewSessionRegistry<MinecraftClientViewPeer, BlockState> active = registry;
        ClientViewServerSession<MinecraftClientViewPeer, BlockState> session = active == null ? null : active.session(traveler);
        if (session == null || !session.preparedTravelSelected()) {
            return false;
        }
        ClientPreparedTravelServer.AutomaticCross result = session.travel().automaticCross(source, System.currentTimeMillis());
        if (result == ClientPreparedTravelServer.AutomaticCross.FALLBACK) {
            prepared.complete(traveler);
            session.cancelTravel();
        }
        return result == ClientPreparedTravelServer.AutomaticCross.DEFER;
    }

    public boolean receiver(ServerPlayer player) {
        ClientViewSessionRegistry<MinecraftClientViewPeer, BlockState> active = registry;
        return active != null && !active.sessions().isEmpty() && active.effectsReceiver(player.getUUID());
    }

    public void entityEvent(ProjectedEntityEvent event) {
        ClientViewSessionRegistry<MinecraftClientViewPeer, BlockState> active = registry;
        if (active != null) {
            active.entityEvent(event);
        }
    }

    public boolean oneShot(ServerPlayer player, ClientViewMessage.FxEmitter emitter) {
        ClientViewSessionRegistry<MinecraftClientViewPeer, BlockState> active = registry;
        return active != null && active.oneShot(player.getUUID(), emitter);
    }

    public void oneShotNear(ServerLevel level, double x, double y, double z, ClientViewMessage.FxEmitter emitter) {
        if (!hasReceivers(level)) {
            return;
        }
        List<ServerPlayer> players = level.players();
        for (int i = 0; i < players.size(); i++) {
            ServerPlayer player = players.get(i);
            if (player.distanceToSqr(x, y, z) < PARTICLE_RANGE_SQUARED && receiver(player)) {
                oneShot(player, emitter);
            }
        }
    }

    public boolean particles(ServerLevel level, ParticleOptions options, double x, double y, double z, int count, double spreadX, double spreadY,
                             double spreadZ, double speed, ClientViewMessage.FxEmitter clientEmitter) {
        if (!hasReceivers(level)) {
            return false;
        }
        List<ServerPlayer> players = level.players();
        for (int i = 0; i < players.size(); i++) {
            ServerPlayer player = players.get(i);
            if (!receiver(player)) {
                level.sendParticles(player, options, false, false, x, y, z, count, spreadX, spreadY, spreadZ, speed);
            } else if (clientEmitter != null && player.distanceToSqr(x, y, z) < PARTICLE_RANGE_SQUARED) {
                oneShot(player, clientEmitter);
            }
        }
        return true;
    }

    public void burst(ServerLevel level, SimpleParticleType particle, double x, double y, double z, int count, double spreadX, double spreadY,
                      double spreadZ, double speed) {
        ClientViewMessage.FxEmitter emitter = hasReceivers(level)
            ? ClientViewEmitters.burst(BuiltInRegistries.PARTICLE_TYPE.getKey(particle).toString(), x, y, z, count, spreadX, spreadY, speed)
            : null;
        if (emitter == null || !particles(level, particle, x, y, z, count, spreadX, spreadY, spreadZ, speed, emitter)) {
            level.sendParticles(particle, x, y, z, count, spreadX, spreadY, spreadZ, speed);
        }
    }

    public boolean hasReceivers(ServerLevel level) {
        ClientViewSessionRegistry<MinecraftClientViewPeer, BlockState> active = registry;
        if (active == null || active.sessions().isEmpty()) {
            return false;
        }
        for (ClientViewServerSession<MinecraftClientViewPeer, BlockState> session : active.sessions()) {
            ServerPlayer player = session.effectsReceiver() ? session.player().player() : null;
            if (player != null && player.level() == level) {
                return true;
            }
        }
        return false;
    }

    public boolean holdsVanilla(UUID player) {
        ClientViewSessionRegistry<MinecraftClientViewPeer, BlockState> active = registry;
        ClientViewServerSession<MinecraftClientViewPeer, BlockState> session = active == null ? null : active.session(player);
        return session != null && session.holdsVanilla();
    }

    public ConfigurationTask configurationTask(ServerConfigurationPacketListenerImpl listener, BooleanSupplier channelPresent) {
        MinecraftClientViewNegotiator current = negotiator;
        if (current == null) {
            return null;
        }
        GameProfile profile = listener.getOwner();
        return current.configurationTask(profile.id(), profile.name(), ((ServerConnectionAccess) listener).wormholesConnection(), channelPresent);
    }

    public void joined(ServerPlayer player, boolean channelPresent) {
        if (channelPresent) {
            channelRegistered(player);
        }
    }

    public void channelRegistered(ServerPlayer player) {
        MinecraftClientViewNegotiator current = negotiator;
        if (current == null || !(player.connection instanceof ServerConnectionAccess listener)) {
            return;
        }
        current.offerPlay(player.getUUID(), player.getGameProfile().name(), listener.wormholesConnection());
    }

    public ViewStreamInbound receive(Connection connection, byte[] payload) {
        MinecraftClientViewNegotiator current = negotiator;
        if (current == null || connection == null || payload == null) {
            return ViewStreamInbound.IGNORED;
        }
        ViewStreamInbound outcome = current.receive(connection, payload);
        if (outcome == ViewStreamInbound.RESET) {
            MinecraftClientViewPeer peer = current.peer(connection);
            LOGGER.warn("Wormholes ClientView ended the session for {} after repeated protocol violations", peer == null ? "unknown" : peer.name());
        }
        return outcome;
    }

    public ViewStreamInbound receive(ServerCommonPacketListenerImpl listener, byte[] payload) {
        if (!(listener instanceof ServerConnectionAccess access)) {
            return ViewStreamInbound.IGNORED;
        }
        return receive(access.wormholesConnection(), payload);
    }

    public void disconnected(ServerPlayer player) {
        seamless.remove(player.getUUID());
        prepared.complete(player.getUUID());
        portals.scene().removeObserver(player.getUUID());
        MinecraftClientViewNegotiator current = negotiator;
        if (current == null || !(player.connection instanceof ServerConnectionAccess listener)) {
            return;
        }
        current.disconnected(player.getUUID(), listener.wormholesConnection());
    }

    public ClientViewSessionRegistry<MinecraftClientViewPeer, BlockState> registry() {
        return registry;
    }

    @Override
    public void close() {
        prepared.clear();
        seamless.clear();
        portals.scene().close();
        ClientViewSessionRegistry<MinecraftClientViewPeer, BlockState> active = registry;
        MinecraftClientViewNegotiator current = negotiator;
        registry = null;
        negotiator = null;
        applied = null;
        if (active != null) {
            active.shutdown();
        }
        if (current != null) {
            current.clear();
        }
        LocalPlateHandles.clear();
    }

    private void follow(ClientViewServerSession<MinecraftClientViewPeer, BlockState> session, ServerPlayer player) {
        ClientViewMessage.ResetReason reason = session.player().follow(player, runtime);
        if (reason != null) {
            session.reset(reason);
        }
    }
    private record Seamless(UUID source, UUID token, long generation, long until) {
    }

}
