package art.arcane.wormholes.modded.clientview;

import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.seamless.MinecraftSeamlessMove;
import net.minecraft.world.entity.Entity;
import art.arcane.wormholes.modded.mixin.ServerConnectionAccess;
import art.arcane.optics.stream.ViewStreamCapability;
import art.arcane.optics.stream.ViewStreamMessage;
import art.arcane.wormholes.render.client.session.ClientViewEmitters;
import art.arcane.wormholes.render.client.session.ClientPreparedTravelServer;
import art.arcane.wormholes.render.client.session.ClientViewTravel;
import art.arcane.wormholes.network.client.ClientViewExtensions;
import art.arcane.wormholes.network.client.FxExtension;
import art.arcane.optics.stream.EntityFrames;
import art.arcane.optics.stream.ViewStreamInbound;
import art.arcane.optics.stream.ViewStreamOptions;
import art.arcane.optics.stream.ViewStreamPlatform;
import art.arcane.wormholes.render.client.session.ClientViewSceneFx;
import art.arcane.optics.stream.ViewStreamSession;
import art.arcane.optics.stream.ViewStreamSessionState;
import art.arcane.optics.stream.ViewStreamSessionRegistry;
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
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import art.arcane.optics.entity.ProjectedEntityEvent;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import art.arcane.wormholes.network.client.TravelMessage;
import art.arcane.wormholes.network.client.FxMessage;

public final class MinecraftClientViewService implements AutoCloseable {
    public static final long PLATFORM_CAPS = ViewStreamCapability.of(ViewStreamCapability.PLATES, ViewStreamCapability.BRICK_CACHE,
        ViewStreamCapability.DEST_LIGHT, ViewStreamCapability.ENTITY_FRAMES, ViewStreamCapability.ENTITY_SELF, ViewStreamCapability.ENTITY_EVENTS, ViewStreamCapability.ATMOSPHERE,
        ViewStreamCapability.ZERO_COPY, ViewStreamCapability.CONFIG_PHASE, ViewStreamCapability.LINK_UNCOMPRESSED,
        ViewStreamCapability.VIEW_STATS, ViewStreamCapability.CLIENT_MIRROR, ViewStreamCapability.CLIENT_RECURSION, ViewStreamCapability.MESH_RENDER, ViewStreamCapability.LOCAL_MESH, ViewStreamCapability.MESH_REUSE)
        | ClientViewExtensions.FX_EMITTERS | ClientViewExtensions.PREPARED_TRAVEL | ClientViewExtensions.PREPARED_TRAVEL_CACHE
        | ClientViewExtensions.REMOTE_VIEW | ClientViewExtensions.SEAMLESS_TRAVEL;
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");
    private static final long HANDLE_PURGE_INTERVAL_TICKS = 20L;
    private static final double PARTICLE_RANGE_SQUARED = 32.0D * 32.0D;

    private final WormholesModRuntime runtime;
    private final MinecraftClientViewTransport transport;
    private final MinecraftClientViewPortalAccess portals;
    private final MinecraftPreparedTravel prepared;
    private final Map<UUID, Seamless> seamless = new HashMap<>();
    private final Set<UUID> levelHandoffs = new HashSet<>();
    private volatile ViewStreamSessionRegistry<MinecraftClientViewPeer, BlockState> registry;
    private volatile MinecraftClientViewNegotiator negotiator;
    private ViewStreamOptions applied;

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
        ViewStreamOptions options = runtime.configuration().clientViewOptions();
        ViewStreamPlatform<MinecraftClientViewPeer, BlockState> platform = new ViewStreamPlatform<>(transport, portals,
            new EntityFrames<>(portals.scene()), new ClientViewSceneFx<>(portals.scene()),
            new MinecraftClientViewHandoffs(), runtime.projections().lanes(), BlockStateParser::serialize,
            SharedConstants.getCurrentVersion().dataVersion().version(), PLATFORM_CAPS, System::nanoTime,
            (message, failure) -> LOGGER.warn(message, failure), MinecraftClientViewExtensions.ALL, ClientViewTravel::new);
        ViewStreamSessionRegistry<MinecraftClientViewPeer, BlockState> created = new ViewStreamSessionRegistry<>(platform, options);
        applied = options;
        negotiator = new MinecraftClientViewNegotiator(created);
        registry = created;
    }

    public void tick(long serverTick, List<ServerPlayer> players, List<MinecraftPortal> candidates) {
        ViewStreamSessionRegistry<MinecraftClientViewPeer, BlockState> active = registry;
        MinecraftClientViewNegotiator current = negotiator;
        if (active == null || current == null) {
            return;
        }
        ViewStreamOptions options = runtime.configuration().clientViewOptions();
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
            ViewStreamSession<MinecraftClientViewPeer, BlockState> session = active.session(player.getUUID());
            if (session == null) {
                continue;
            }
            try {
                if (!ClientViewTravel.of(session).seamlessSelected()) {
                    runtime.remoteRoutes().forget(player.getUUID(), false);
                }
                follow(session, player);
                session.player().meshDepth(ViewStreamCapability.MESH_RENDER.in(session.caps())
                    ? Math.clamp(player.requestedViewDistance(), 2, 32) * 16 : 0);
                session.tick(serverTick);
                prepared.tick(ClientViewTravel.of(session), player);
            } catch (RuntimeException failure) {
                LOGGER.error("Wormholes ClientView tick failed for {}", player.getUUID(), failure);
                session.end(ViewStreamMessage.ResetReason.PROTOCOL);
            }
        }
    }

    public void runtimeEnabled(boolean enabled) {
        ViewStreamSessionRegistry<MinecraftClientViewPeer, BlockState> active = registry;
        MinecraftClientViewNegotiator current = negotiator;
        if (active != null && current != null && active.runtimeEnabled(enabled)) {
            current.reoffer();
        }
    }

    public boolean owns(UUID player, UUID portal) {
        ViewStreamSessionRegistry<MinecraftClientViewPeer, BlockState> active = registry;
        return active != null && active.owns(player, portal);
    }

    public boolean nativeMesh(ServerPlayer player) {
        ViewStreamSessionRegistry<MinecraftClientViewPeer, BlockState> active = registry;
        ViewStreamSession<MinecraftClientViewPeer, BlockState> session = active == null ? null : active.session(player.getUUID());
        return session != null && session.state() == ViewStreamSessionState.CLIENT_VIEW && ViewStreamCapability.MESH_RENDER.in(session.caps());
    }

    public TravelMessage.TravelCommit commitTravel(ServerPlayer player, UUID source, ServerLevel destination,
                                                       TravelMessage.TravelPose arrival, Vec3d velocity) {
        runtime.requireServerThread();
        ClientViewTravel<MinecraftClientViewPeer> travel = travel(player.getUUID());
        if (travel == null || !travel.preparedTravelSelected() || prepared.seamlessPreparation(player.getUUID())) {
            return null;
        }
        TravelMessage.TravelCommit commit = prepared.commit(travel, player, source, destination, arrival, velocity).orElse(null);
        if (commit != null && travel.sendTravel(commit)) {
            seamless.put(player.getUUID(), new Seamless(source, commit.token(), commit.generation(), System.currentTimeMillis() + 2_000L, false));
            return commit;
        }
        if (commit != null) {
            travel.sendTravel(new TravelMessage.TravelCancel(commit.token(), commit.generation()));
        }
        travel.cancelTravel();
        return null;
    }

    public boolean seamlessCrossing(ServerPlayer player) {
        ClientViewTravel<MinecraftClientViewPeer> travel = travel(player.getUUID());
        return travel != null && travel.seamlessSelected() && travel.server().crossing() && prepared.seamlessPreparation(player.getUUID());
    }

    public SeamlessTicket seamlessArrival(ServerPlayer player, UUID source, ServerLevel destination,
                                          TravelMessage.TravelPose arrival, Vec3d velocity) {
        runtime.requireServerThread();
        ClientViewTravel<MinecraftClientViewPeer> travel = travel(player.getUUID());
        if (travel == null || !travel.seamlessSelected()) {
            return null;
        }
        MinecraftSeamlessMove.Context context = prepared.seamlessArrival(travel, player, source, destination, arrival, velocity);
        if (context == null) {
            return null;
        }
        seamless.put(player.getUUID(), new Seamless(source, context.accept().token(), context.accept().generation(),
            System.currentTimeMillis() + 2_000L, false));
        return new SeamlessTicket(context, travel, source);
    }

    public Entity seamlessMove(SeamlessTicket ticket) {
        runtime.requireServerThread();
        MinecraftSeamlessMove.Context context = ticket.context();
        ServerPlayer player = context.player();
        boolean changed = context.destination() != player.level();
        if (!MinecraftSeamlessMove.run(context)) {
            seamless.remove(player.getUUID());
            ticket.travel().sendTravel(new TravelMessage.TravelCancel(context.accept().token(), context.accept().generation()));
            return null;
        }
        ticket.travel().server().seamlessCrossed(context.tick(), System.currentTimeMillis());
        seamless.put(player.getUUID(), new Seamless(ticket.source(), context.accept().token(), context.accept().generation(),
            System.currentTimeMillis() + 2_000L, true));
        if (changed) {
            levelHandoffs.add(player.getUUID());
        }
        prepared.complete(player.getUUID());
        return player;
    }

    public boolean seamlessAccepted(UUID playerId, UUID token) {
        Seamless active = seamless.get(playerId);
        return active != null && active.accepted() && active.token().equals(token);
    }

    public void cancelTravel(ServerPlayer player, TravelMessage.TravelCommit commit) {
        Seamless marked = seamless.get(player.getUUID());
        if (commit == null || marked != null && marked.token().equals(commit.token()) && marked.generation() == commit.generation()) {
            seamless.remove(player.getUUID());
        }
        if (commit == null) {
            prepared.complete(player.getUUID());
        } else {
            prepared.complete(player.getUUID(), commit);
        }
        ClientViewTravel<MinecraftClientViewPeer> travel = travel(player.getUUID());
        if (travel != null) {
            if (commit == null) {
                travel.cancelTravel();
            } else {
                travel.server().preparing().filter(begin -> begin.token().equals(commit.token())
                    && begin.generation() == commit.generation()).ifPresent(begin -> travel.cancelTravel());
                travel.sendTravel(new TravelMessage.TravelCancel(commit.token(), commit.generation()));
            }
        }
    }

    public void cancelPreparation(ServerPlayer player, TravelMessage.TravelBegin expected) {
        if (expected == null) {
            return;
        }
        prepared.complete(player.getUUID(), expected.token(), expected.generation());
        ClientViewTravel<MinecraftClientViewPeer> travel = travel(player.getUUID());
        TravelMessage.TravelCancel cancel = new TravelMessage.TravelCancel(expected.token(), expected.generation());
        if (travel != null && travel.server().cancel(cancel)) {
            travel.sendTravel(cancel);
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
        ClientViewTravel<MinecraftClientViewPeer> travel = travel(playerId);
        return travel != null && travel.preparedTravelSelected() && travel.server().readyRoute(sourcePortal, System.currentTimeMillis());
    }

    public Optional<TravelMessage.TravelBegin> preparation(UUID traveler) {
        ClientViewTravel<MinecraftClientViewPeer> travel = travel(traveler);
        return travel == null ? Optional.empty() : travel.server().preparing();
    }

    public boolean crossing(UUID traveler, TravelMessage.TravelBegin expected) {
        return expected != null && preparation(traveler).filter(begin -> begin.token().equals(expected.token())
            && begin.generation() == expected.generation()).isPresent() && crossing(traveler);
    }

    public boolean crossing(UUID traveler) {
        ClientViewTravel<MinecraftClientViewPeer> travel = travel(traveler);
        return travel != null && travel.preparedTravelSelected() && travel.server().crossing();
    }

    public boolean deferTravel(UUID traveler, UUID source) {
        ClientViewTravel<MinecraftClientViewPeer> travel = travel(traveler);
        if (travel == null || !travel.preparedTravelSelected()) {
            return false;
        }
        ClientPreparedTravelServer.AutomaticCross result = travel.server().automaticCross(source, System.currentTimeMillis());
        if (result == ClientPreparedTravelServer.AutomaticCross.FALLBACK) {
            prepared.complete(traveler);
            travel.cancelTravel();
        }
        return result == ClientPreparedTravelServer.AutomaticCross.DEFER;
    }

    public boolean receiver(ServerPlayer player) {
        ViewStreamSessionRegistry<MinecraftClientViewPeer, BlockState> active = registry;
        return active != null && !active.sessions().isEmpty() && active.effectsReceiver(player.getUUID());
    }

    public void entityEvent(ProjectedEntityEvent event) {
        ViewStreamSessionRegistry<MinecraftClientViewPeer, BlockState> active = registry;
        if (active != null) {
            active.entityEvent(event);
        }
    }

    public boolean oneShot(ServerPlayer player, FxMessage.FxEmitter emitter) {
        ViewStreamSessionRegistry<MinecraftClientViewPeer, BlockState> active = registry;
        return active != null && active.burst(player.getUUID(), FxExtension.burst(emitter));
    }

    public void oneShotNear(ServerLevel level, double x, double y, double z, FxMessage.FxEmitter emitter) {
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
                             double spreadZ, double speed, FxMessage.FxEmitter clientEmitter) {
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
        FxMessage.FxEmitter emitter = hasReceivers(level)
            ? ClientViewEmitters.burst(BuiltInRegistries.PARTICLE_TYPE.getKey(particle).toString(), x, y, z, count, spreadX, spreadY, speed)
            : null;
        if (emitter == null || !particles(level, particle, x, y, z, count, spreadX, spreadY, spreadZ, speed, emitter)) {
            level.sendParticles(particle, x, y, z, count, spreadX, spreadY, spreadZ, speed);
        }
    }

    public boolean hasReceivers(ServerLevel level) {
        ViewStreamSessionRegistry<MinecraftClientViewPeer, BlockState> active = registry;
        if (active == null || active.sessions().isEmpty()) {
            return false;
        }
        for (ViewStreamSession<MinecraftClientViewPeer, BlockState> session : active.sessions()) {
            ServerPlayer player = session.effectsReceiver() ? session.player().player() : null;
            if (player != null && player.level() == level) {
                return true;
            }
        }
        return false;
    }

    private ClientViewTravel<MinecraftClientViewPeer> travel(UUID playerId) {
        ViewStreamSessionRegistry<MinecraftClientViewPeer, BlockState> active = registry;
        ViewStreamSession<MinecraftClientViewPeer, BlockState> session = active == null ? null : active.session(playerId);
        return session == null ? null : ClientViewTravel.of(session);
    }

    public boolean holdsVanilla(UUID player) {
        ViewStreamSessionRegistry<MinecraftClientViewPeer, BlockState> active = registry;
        ViewStreamSession<MinecraftClientViewPeer, BlockState> session = active == null ? null : active.session(player);
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

    public ViewStreamInbound receivePlay(ServerPlayer player, byte[] payload) {
        runtime.requireServerThread();
        ViewStreamInbound outcome = receive(player.connection, payload);
        ClientViewTravel<MinecraftClientViewPeer> travel = travel(player.getUUID());
        if (travel != null && travel.seamlessSelected()) {
            prepared.settleCross(travel, player);
        }
        return outcome;
    }

    public void disconnected(ServerPlayer player) {
        seamless.remove(player.getUUID());
        levelHandoffs.remove(player.getUUID());
        runtime.remoteRoutes().forget(player.getUUID(), false);
        prepared.complete(player.getUUID());
        portals.scene().removeObserver(player.getUUID());
        MinecraftClientViewNegotiator current = negotiator;
        if (current == null || !(player.connection instanceof ServerConnectionAccess listener)) {
            return;
        }
        current.disconnected(player.getUUID(), listener.wormholesConnection());
    }

    public ViewStreamSessionRegistry<MinecraftClientViewPeer, BlockState> registry() {
        return registry;
    }

    @Override
    public void close() {
        prepared.clear();
        seamless.clear();
        levelHandoffs.clear();
        portals.scene().close();
        ViewStreamSessionRegistry<MinecraftClientViewPeer, BlockState> active = registry;
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

    private void follow(ViewStreamSession<MinecraftClientViewPeer, BlockState> session, ServerPlayer player) {
        ViewStreamMessage.ResetReason reason = session.player().follow(player, runtime, levelHandoffs.remove(player.getUUID()));
        if (reason != null) {
            session.reset(reason);
        }
    }
    private record Seamless(UUID source, UUID token, long generation, long until, boolean accepted) {
    }

    public record SeamlessTicket(MinecraftSeamlessMove.Context context, ClientViewTravel<MinecraftClientViewPeer> travel, UUID source) {
    }

}
