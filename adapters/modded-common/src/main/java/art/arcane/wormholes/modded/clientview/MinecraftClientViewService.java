package art.arcane.wormholes.modded.clientview;

import art.arcane.optics.crossing.PlaneCrossing;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.mixin.ProjectionEntityMapAccess;
import art.arcane.wormholes.modded.mixin.RemoteTrackedEntityAccess;
import art.arcane.wormholes.modded.seamless.MinecraftSeamlessMove;
import art.arcane.wormholes.modded.seamless.RemoteViewerConnection;
import net.minecraft.world.entity.Entity;
import art.arcane.wormholes.modded.mixin.ServerConnectionAccess;
import art.arcane.optics.stream.ViewStreamCapability;
import art.arcane.optics.stream.ViewStreamMessage;
import art.arcane.wormholes.render.client.session.ClientViewEmitters;
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
import net.minecraft.server.network.ServerPlayerConnection;
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
import art.arcane.optics.entity.EntityAnimation;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import art.arcane.wormholes.network.client.TravelMessage;
import art.arcane.wormholes.network.client.FxMessage;

public final class MinecraftClientViewService implements AutoCloseable {
    public static final long PLATFORM_CAPS = ViewStreamCapability.of(ViewStreamCapability.PLATES, ViewStreamCapability.BRICK_CACHE,
        ViewStreamCapability.DEST_LIGHT, ViewStreamCapability.ENTITY_FRAMES, ViewStreamCapability.ENTITY_SELF, ViewStreamCapability.ENTITY_EVENTS, ViewStreamCapability.ATMOSPHERE,
        ViewStreamCapability.ZERO_COPY, ViewStreamCapability.CONFIG_PHASE, ViewStreamCapability.LINK_UNCOMPRESSED,
        ViewStreamCapability.VIEW_STATS, ViewStreamCapability.CLIENT_MIRROR, ViewStreamCapability.CLIENT_RECURSION, ViewStreamCapability.MESH_RENDER, ViewStreamCapability.LOCAL_MESH, ViewStreamCapability.MESH_REUSE)
        | ClientViewExtensions.FX_EMITTERS | ClientViewExtensions.REMOTE_VIEW | ClientViewExtensions.SEAMLESS_TRAVEL;
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");
    private static final long HANDLE_PURGE_INTERVAL_TICKS = 20L;
    private static final double PARTICLE_RANGE_SQUARED = 32.0D * 32.0D;

    private final WormholesModRuntime runtime;
    private final MinecraftClientViewTransport transport;
    private final MinecraftClientViewPortalAccess portals;
    private final MinecraftSeamlessTravel seamlessTravel;
    private final Map<UUID, Seamless> seamless = new HashMap<>();
    private final Set<UUID> levelHandoffs = new HashSet<>();
    private final Map<UUID, String> fallbacks = new HashMap<>();
    private volatile ViewStreamSessionRegistry<MinecraftClientViewPeer, BlockState> registry;
    private volatile MinecraftClientViewNegotiator negotiator;
    private ViewStreamOptions applied;

    public MinecraftClientViewService(WormholesModRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.transport = new MinecraftClientViewTransport();
        this.portals = new MinecraftClientViewPortalAccess(runtime);
        this.seamlessTravel = new MinecraftSeamlessTravel(runtime, portals);
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
                ClientViewTravel<MinecraftClientViewPeer> travel = ClientViewTravel.of(session);
                if (!travel.seamlessSelected()) {
                    runtime.remoteRoutes().forget(player.getUUID(), false);
                    seamlessTravel.forget(player.getUUID());
                }
                follow(session, player);
                session.player().meshDepth(ViewStreamCapability.MESH_RENDER.in(session.caps())
                    ? Math.clamp(player.requestedViewDistance(), 2, 32) * 16 : 0);
                session.tick(serverTick);
                if (travel.seamlessSelected()) {
                    seamlessTravel.tick(travel, player);
                }
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

    public boolean seamlessCrossing(ServerPlayer player) {
        ClientViewTravel<MinecraftClientViewPeer> travel = travel(player.getUUID());
        return travel != null && travel.seamlessSelected() && seamlessTravel.crossing(player.getUUID());
    }

    public SeamlessTicket seamlessArrival(ServerPlayer player, UUID source, ServerLevel destination,
                                          TravelMessage.TravelPose arrival, Vec3d velocity) {
        runtime.requireServerThread();
        ClientViewTravel<MinecraftClientViewPeer> travel = travel(player.getUUID());
        if (travel == null || !travel.seamlessSelected()) {
            return null;
        }
        MinecraftSeamlessMove.Context context = seamlessTravel.arrival(travel, player, source, destination, arrival, velocity);
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
            seamlessTravel.moved(player, false);
            return null;
        }
        seamlessTravel.moved(player, true);
        seamless.put(player.getUUID(), new Seamless(ticket.source(), context.accept().token(), context.accept().generation(),
            System.currentTimeMillis() + 2_000L, true));
        if (changed) {
            levelHandoffs.add(player.getUUID());
        }
        return player;
    }

    public void cancelTravel(ServerPlayer player) {
        seamless.remove(player.getUUID());
    }

    public void cancelPreparation(ServerPlayer player, TravelMessage.TravelBegin expected) {
        ClientViewTravel<MinecraftClientViewPeer> travel = travel(player.getUUID());
        if (expected != null && travel != null && seamlessTravel.owns(player.getUUID(), expected)) {
            seamlessTravel.finish(travel, player, expected);
        }
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
        return travel != null && travel.seamlessSelected() && seamlessTravel.armed(playerId, sourcePortal);
    }

    public Optional<TravelMessage.TravelBegin> preparation(UUID traveler) {
        ClientViewTravel<MinecraftClientViewPeer> travel = travel(traveler);
        return travel != null && travel.seamlessSelected() ? Optional.ofNullable(seamlessTravel.attempted(traveler)) : Optional.empty();
    }

    public boolean crossing(UUID traveler, TravelMessage.TravelBegin expected) {
        return expected != null && preparation(traveler).filter(begin -> begin.token().equals(expected.token())
            && begin.generation() == expected.generation()).isPresent() && crossing(traveler);
    }

    public boolean crossing(UUID traveler) {
        ClientViewTravel<MinecraftClientViewPeer> travel = travel(traveler);
        return travel != null && travel.seamlessSelected() && seamlessTravel.crossing(traveler);
    }

    public boolean seamlessEye(UUID traveler, UUID source) {
        ClientViewTravel<MinecraftClientViewPeer> travel = travel(traveler);
        return travel != null && travel.seamlessSelected() && seamlessTravel.armed(traveler, source);
    }

    public boolean deferTravel(UUID traveler, UUID source) {
        ClientViewTravel<MinecraftClientViewPeer> travel = travel(traveler);
        if (travel == null || !travel.seamlessSelected()) {
            fallbacks.put(traveler, travel == null ? "no ClientView session" : "seamless travel not negotiated");
            return false;
        }
        ServerPlayer player = runtime.server().getPlayerList().getPlayer(traveler);
        boolean deferred = player != null && seamlessTravel.defer(player, source);
        if (!deferred && !seamlessTravel.crossing(traveler)) {
            fallbacks.put(traveler, "no seamless route armed for this portal");
        }
        return deferred;
    }

    public void crossed(ServerPlayer player, ServerLevel origin, ServerLevel destination, boolean seamlessMove) {
        seamlessTravel.relocated(player.getUUID());
        String reason = fallbacks.remove(player.getUUID());
        if (seamlessMove) {
            LOGGER.info("Crossing seamless {} {} -> {}", player.getScoreboardName(), origin.dimension().identifier(), destination.dimension().identifier());
        } else {
            LOGGER.info("Crossing teleport {} {} -> {}: {}", player.getScoreboardName(), origin.dimension().identifier(),
                destination.dimension().identifier(), reason == null ? "not predicted" : reason);
        }
    }

    public void entityCrossed(Entity entity, PlaneCrossing crossing, OpticTransform toward, Vec3d velocity) {
        runtime.requireServerThread();
        if (!(entity.level() instanceof ServerLevel level)
            || !(((ProjectionEntityMapAccess) level.getChunkSource().chunkMap).wormholesEntityMap().get(entity.getId()) instanceof RemoteTrackedEntityAccess tracked)) {
            return;
        }
        for (ServerPlayerConnection connection : tracked.wormholesSeenBy()) {
            int handle = observerHandle(connection, level);
            ServerPlayer observer = connection.getPlayer();
            ClientViewTravel<MinecraftClientViewPeer> travel = handle < 0 || observer == entity ? null : travel(observer.getUUID());
            if (travel != null && travel.seamlessSelected()) {
                travel.sendTravel(new TravelMessage.EntityCrossed(handle, entity.getId(), toward, crossing.origin(), crossing.frame().getNormal(),
                    velocity));
            }
        }
    }

    static int observerHandle(ServerPlayerConnection connection, ServerLevel level) {
        if (connection instanceof RemoteViewerConnection remote) {
            return remote.route().resident() && remote.route().level() == level ? remote.route().handle() : -1;
        }
        return connection.getPlayer().level() == level ? 0 : -1;
    }

    public boolean receiver(ServerPlayer player) {
        ViewStreamSessionRegistry<MinecraftClientViewPeer, BlockState> active = registry;
        return active != null && !active.sessions().isEmpty() && active.effectsReceiver(player.getUUID());
    }

    public void entityEvent(EntityAnimation event) {
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
            seamlessTravel.settle(travel, player);
        }
        return outcome;
    }

    public void disconnected(ServerPlayer player) {
        seamless.remove(player.getUUID());
        levelHandoffs.remove(player.getUUID());
        fallbacks.remove(player.getUUID());
        seamlessTravel.forget(player.getUUID());
        runtime.remoteRoutes().forget(player.getUUID(), false);
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
        seamlessTravel.clear();
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

    private ClientViewTravel<MinecraftClientViewPeer> travel(UUID playerId) {
        ViewStreamSessionRegistry<MinecraftClientViewPeer, BlockState> active = registry;
        ViewStreamSession<MinecraftClientViewPeer, BlockState> session = active == null ? null : active.session(playerId);
        return session == null ? null : ClientViewTravel.of(session);
    }

    private void follow(ViewStreamSession<MinecraftClientViewPeer, BlockState> session, ServerPlayer player) {
        ViewStreamMessage.ResetReason reason = session.player().follow(player, runtime, levelHandoffs.remove(player.getUUID()));
        if (reason != null) {
            runtime.remoteRoutes().forget(player.getUUID(), false);
            seamlessTravel.forget(player.getUUID());
            session.reset(reason);
        }
    }

    private record Seamless(UUID source, UUID token, long generation, long until, boolean accepted) {
    }

    public record SeamlessTicket(MinecraftSeamlessMove.Context context, ClientViewTravel<MinecraftClientViewPeer> travel, UUID source) {
    }

}
