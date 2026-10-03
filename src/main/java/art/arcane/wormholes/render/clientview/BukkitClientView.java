package art.arcane.wormholes.render.clientview;

import java.lang.reflect.Constructor;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import art.arcane.wormholes.render.ProjectedEntityEvent;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.Messenger;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import com.github.retrooper.packetevents.protocol.player.User;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.network.client.ClientViewCapability;
import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.wormholes.network.client.ClientViewProtocol;
import art.arcane.wormholes.portal.ArrivalWarmer;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.rtp.RtpRimRenderer;
import art.arcane.wormholes.render.PortalProjector;
import art.arcane.wormholes.render.client.session.ClientViewEntityFrames;
import art.arcane.wormholes.render.client.session.ClientViewInbound;
import art.arcane.wormholes.render.client.session.ClientViewOptions;
import art.arcane.wormholes.render.client.session.ClientViewPlatform;
import art.arcane.wormholes.render.client.session.ClientViewSceneFx;
import art.arcane.wormholes.render.client.session.ClientViewServerSession;
import art.arcane.wormholes.render.client.session.ClientViewSessionRegistry;
import art.arcane.wormholes.render.client.session.ClientViewSessionState;
import art.arcane.wormholes.render.plate.ViewPlateCache;
import art.arcane.wormholes.render.view.ProjectionWorldViewProvider;

public final class BukkitClientView implements ClientViewRouting {
    public static final long PLATFORM_CAPS = ClientViewCapability.of(ClientViewCapability.PLATES, ClientViewCapability.BRICK_CACHE,
        ClientViewCapability.DEST_LIGHT, ClientViewCapability.ENTITY_FRAMES, ClientViewCapability.ENTITY_EVENTS, ClientViewCapability.FX_EMITTERS, ClientViewCapability.ATMOSPHERE,
        ClientViewCapability.CONFIG_PHASE, ClientViewCapability.LINK_UNCOMPRESSED, ClientViewCapability.VIEW_STATS,
        ClientViewCapability.CLIENT_MIRROR, ClientViewCapability.CLIENT_RECURSION, ClientViewCapability.MESH_RENDER, ClientViewCapability.LOCAL_MESH, ClientViewCapability.MESH_REUSE);
    private static final long SOURCE_STALE_TICKS = 40L;
    private static final double PARTICLE_RANGE_SQUARED = 32.0D * 32.0D;
    private static final String CONFIGURE_EVENT_CLASS = "io.papermc.paper.event.connection.configuration.AsyncPlayerConnectionConfigureEvent";
    private static final String CONFIGURE_LISTENER_CLASS = "art.arcane.wormholes.render.clientview.PaperClientViewConfigureListener";

    private final ClientViewSessionRegistry<ClientViewObserver, BlockData> registry;
    private final ConcurrentHashMap<UUID, ClientViewObserver> observers;
    private final PacketEventsClientViewTransport transport;
    private final ShutdownAwareLanes lanes;
    private final Logger logger;
    private final Consumer<String> verbose;
    private final BukkitClientViewNegotiator negotiator;
    private final BukkitClientViewScene scene;
    private final boolean folia;
    private Plugin plugin;
    private PacketListenerCommon packetListener;

    public BukkitClientView(Options options) {
        Objects.requireNonNull(options, "options");
        this.logger = Objects.requireNonNull(options.logger(), "logger");
        this.observers = new ConcurrentHashMap<UUID, ClientViewObserver>();
        this.lanes = new ShutdownAwareLanes(Objects.requireNonNull(options.workers(), "workers"));
        this.transport = new PacketEventsClientViewTransport(new Inbound());
        long identitySalt = new SecureRandom().nextLong();
        BukkitClientViewPortalAccess portals = new BukkitClientViewPortalAccess(options.views(), options.plates(), options.lookup(),
            options.releaseVanilla(), () -> identitySalt);
        this.scene = portals.scene();
        ClientViewPlatform<ClientViewObserver, BlockData> platform = new ClientViewPlatform<ClientViewObserver, BlockData>(transport, portals,
            new ClientViewEntityFrames<ClientViewObserver>(portals.scene()), new ClientViewSceneFx<ClientViewObserver>(portals.scene()), null, lanes,
            BlockData::getAsString, options.mcDataVersion(), PLATFORM_CAPS, null, this::warn);
        this.registry = new ClientViewSessionRegistry<ClientViewObserver, BlockData>(platform, options.settings());
        this.verbose = Objects.requireNonNull(options.verbose(), "verbose");
        this.negotiator = new BukkitClientViewNegotiator(this, options.users(), options.scheduler(), verbose);
        this.folia = options.folia();
    }

    public void start(Plugin owner) {
        plugin = Objects.requireNonNull(owner, "owner");
        owner.getServer().getPluginManager().registerEvents(negotiator, owner);
        Messenger messenger = owner.getServer().getMessenger();
        messenger.registerOutgoingPluginChannel(owner, ClientViewProtocol.CHANNEL);
        messenger.registerIncomingPluginChannel(owner, ClientViewProtocol.CHANNEL, negotiator);
        packetListener = PacketEvents.getAPI().getEventManager().registerListener(transport);
        registerConfigureListener(owner, negotiator);
    }

    public void stop() {
        shutdown();
        Plugin owner = plugin;
        plugin = null;
        if (owner == null) {
            return;
        }
        HandlerList.unregisterAll(negotiator);
        Messenger messenger = owner.getServer().getMessenger();
        messenger.unregisterIncomingPluginChannel(owner, ClientViewProtocol.CHANNEL, negotiator);
        messenger.unregisterOutgoingPluginChannel(owner, ClientViewProtocol.CHANNEL);
        PacketListenerCommon listener = packetListener;
        packetListener = null;
        if (listener != null && PacketEvents.getAPI() != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(listener);
        }
    }

    public User configurationUser(UUID playerId) {
        ClientViewObserver observer = observers.get(playerId);
        if (observer != null && observer.player() == null && observer.user() != null) {
            return observer.user();
        }
        for (User user : PacketEvents.getAPI().getProtocolManager().getUsers()) {
            if (playerId.equals(user.getUUID()) && user.getEncoderState() == ConnectionState.CONFIGURATION) {
                return user;
            }
        }
        return null;
    }

    public ClientViewSessionRegistry<ClientViewObserver, BlockData> registry() {
        return registry;
    }

    public PacketEventsClientViewTransport transport() {
        return transport;
    }

    public BukkitClientViewNegotiator negotiator() {
        return negotiator;
    }

    public void configure(ClientViewOptions options) {
        if (registry.configure(options)) {
            negotiator.reoffer(Bukkit.getOnlinePlayers());
        }
    }

    public void runtimeEnabled(boolean enabled) {
        if (registry.runtimeEnabled(enabled)) {
            negotiator.reoffer(Bukkit.getOnlinePlayers());
        }
    }

    public boolean reset(Player player) {
        ClientViewServerSession<ClientViewObserver, BlockData> session = registry.session(player.getUniqueId());
        Plugin owner = plugin;
        if (owner == null || session == null || session.state() != ClientViewSessionState.CLIENT_VIEW) {
            return false;
        }
        return FoliaScheduler.runEntity(owner, player, () -> session.reset(ClientViewMessage.ResetReason.TELEPORT));
    }

    public ClientViewObserver observer(UUID playerId) {
        return observers.get(playerId);
    }

    public ClientViewObserver observer(UUID playerId, User user) {
        while (true) {
            ClientViewObserver existing = observers.get(playerId);
            if (existing != null && (user == null || existing.user() == null || existing.user() == user)) {
                existing.user(user);
                return existing;
            }
            ClientViewObserver fresh = new ClientViewObserver(playerId, user);
            if (existing == null) {
                if (observers.putIfAbsent(playerId, fresh) == null) {
                    return fresh;
                }
            } else if (observers.replace(playerId, existing, fresh)) {
                retire(playerId, existing);
                return fresh;
            }
        }
    }

    public void forget(UUID playerId, ClientViewObserver observer) {
        if (observer != null && observers.remove(playerId, observer)) {
            retire(playerId, observer);
        }
    }

    public boolean attending() {
        for (ClientViewObserver observer : observers.values()) {
            if (observer.attending()) {
                return true;
            }
        }
        return false;
    }

    public void attendingObservers(Set<UUID> out) {
        for (ClientViewObserver observer : observers.values()) {
            if (observer.attending()) {
                out.add(observer.id());
            }
        }
    }

    public void projectingObservers(Set<UUID> out) {
        for (ClientViewObserver observer : observers.values()) {
            if (!observer.ownedPortals().isEmpty()) {
                out.add(observer.id());
            }
        }
    }

    public void observersOf(UUID portalId, List<Player> out) {
        for (ClientViewObserver observer : observers.values()) {
            Player player = observer.player();
            if (player != null && observer.ownedPortals().contains(portalId) && player.isOnline()) {
                out.add(player);
            }
        }
    }

    public boolean active() {
        return !registry.sessions().isEmpty();
    }

    public boolean nativeMesh(Player player) {
        ClientViewServerSession<ClientViewObserver, BlockData> session = registry.session(player.getUniqueId());
        return session != null && session.nativeRendererSelected();
    }

    @Override
    public boolean receiver(Player player) {
        return registry.effectsReceiver(player.getUniqueId());
    }

    public void entityEvent(ProjectedEntityEvent event) {
        registry.entityEvent(event);
    }

    public boolean oneShot(Player player, ClientViewMessage.FxEmitter emitter) {
        return registry.oneShot(player.getUniqueId(), emitter);
    }

    public boolean hasReceivers(World world) {
        if (world == null || registry.sessions().isEmpty()) {
            return false;
        }
        for (ClientViewObserver observer : observers.values()) {
            Player player = observer.player();
            if (player != null && world.equals(player.getWorld()) && registry.effectsReceiver(observer.id())) {
                return true;
            }
        }
        return false;
    }

    public void oneShotNear(World world, double x, double y, double z, ClientViewMessage.FxEmitter emitter) {
        if (!hasReceivers(world)) {
            return;
        }
        for (ClientViewObserver observer : observers.values()) {
            Player player = observer.player();
            if (player != null && world.equals(player.getWorld()) && near(player, x, y, z) && registry.effectsReceiver(observer.id())) {
                registry.oneShot(observer.id(), emitter);
            }
        }
    }

    public void touchNear(World world, double x, double y, double z, UUID portalId) {
        if (!hasReceivers(world)) {
            return;
        }
        long now = System.nanoTime();
        for (ClientViewObserver observer : observers.values()) {
            Player player = observer.player();
            if (player != null && world.equals(player.getWorld()) && near(player, x, y, z) && registry.effectsReceiver(observer.id())) {
                observer.touchEffects(portalId, now);
            }
        }
    }

    public boolean particles(World world, double x, double y, double z, Consumer<Player> vanilla, ClientViewMessage.FxEmitter clientEmitter) {
        if (!hasReceivers(world)) {
            return false;
        }
        for (Player player : world.getPlayers()) {
            if (registry.effectsReceiver(player.getUniqueId())) {
                if (clientEmitter != null && near(player, x, y, z)) {
                    registry.oneShot(player.getUniqueId(), clientEmitter);
                }
                continue;
            }
            if ((!folia || FoliaScheduler.isOwnedByCurrentRegion(player)) && near(player, x, y, z)) {
                vanilla.accept(player);
            }
        }
        return true;
    }

    @Override
    public void rim(Player observer, UUID portal, RtpRimRenderer.Sample sample) {
        ClientViewObserver known = observers.get(observer.getUniqueId());
        if (known != null) {
            known.rim(portal, sample);
            known.touchEffects(portal, System.nanoTime());
        }
    }

    @Override
    public boolean holdsVanilla(Player observer, long frameTick) {
        ClientViewServerSession<ClientViewObserver, BlockData> session = registry.session(observer.getUniqueId());
        if (session == null || session.state() != ClientViewSessionState.PENDING) {
            return false;
        }
        return session.expire() == ClientViewSessionState.PENDING;
    }

    @Override
    public void route(Player player, Location eye, List<ILocalPortal> interested, List<ILocalPortal> projectable,
                      Map<UUID, PortalProjector.RtpProjectionTarget> rtpTargets, long frameTick) {
        ClientViewServerSession<ClientViewObserver, BlockData> session = registry.session(player.getUniqueId());
        if (session == null) {
            updateDoorVisibility(player, Set.of());
            return;
        }
        ClientViewObserver observer = session.player();
        if (session.state() != ClientViewSessionState.CLIENT_VIEW) {
            updateDoorVisibility(player, Set.of());
            if (observer.attending()) {
                session.tick(frameTick);
                observer.clearFrame();
            }
            return;
        }
        observer.meshDepth(ClientViewCapability.MESH_RENDER.in(session.caps())
            ? Math.clamp(player.getClientViewDistance(), 2, 32) * 16 : 0);
        observer.beginFrame(player, eye, interested, projectable, rtpTargets, frameTick);
        ArrivalWarmer warmer = Wormholes.arrivalWarmer;
        if (warmer != null) {
            for (int i = 0; i < projectable.size(); i++) {
                warmer.warmDestinationOf(projectable.get(i));
            }
        }
        session.tick(frameTick);
        for (int i = interested.size() - 1; i >= 0; i--) {
            if (session.owns(interested.get(i).getId())) {
                interested.remove(i);
            }
        }
        HashSet<UUID> owned = observer.ownedScratch();
        for (UUID portalId : observer.sourceIds()) {
            if (session.owns(portalId)) {
                owned.add(portalId);
            }
        }
        observer.publishOwned();
        updateDoorVisibility(player, observer.meshDepth() > 0 ? observer.ownedPortals() : Set.of());
        observer.prune(frameTick - SOURCE_STALE_TICKS);
    }

    public void shutdown() {
        for (ClientViewObserver observer : observers.values()) {
            updateDoorVisibility(observer.player(), Set.of());
        }
        scene.close();
        lanes.inline();
        registry.runtimeEnabled(false);
        registry.shutdown();
        observers.clear();
    }

    private void retire(UUID playerId, ClientViewObserver observer) {
        updateDoorVisibility(observer.player(), Set.of());
        scene.removeObserver(playerId);
        ClientViewServerSession<ClientViewObserver, BlockData> session = registry.session(playerId);
        if (session != null && session.player() == observer) {
            registry.forget(playerId);
        }
    }

    private static void updateDoorVisibility(Player player, Set<UUID> ownedPortals) {
        if (player != null && Wormholes.dimensionalDoorManager != null) {
            Wormholes.dimensionalDoorManager.updateNativeProjectionVisibility(player, ownedPortals);
        }
    }

    private void registerConfigureListener(Plugin owner, BukkitClientViewNegotiator active) {
        try {
            Class.forName(CONFIGURE_EVENT_CLASS, false, BukkitClientView.class.getClassLoader());
        } catch (ClassNotFoundException absent) {
            return;
        }
        try {
            Class<?> listenerType = Class.forName(CONFIGURE_LISTENER_CLASS);
            Constructor<?> constructor = listenerType.getDeclaredConstructor(BukkitClientViewNegotiator.class, Function.class);
            constructor.setAccessible(true);
            Function<UUID, User> users = this::configurationUser;
            owner.getServer().getPluginManager().registerEvents((Listener) constructor.newInstance(active, users), owner);
        } catch (ReflectiveOperationException | LinkageError | ClassCastException failure) {
            logger.log(Level.WARNING, "[clientview] could not register the configuration-phase handshake", failure);
        }
    }

    private static boolean near(Player player, double x, double y, double z) {
        Location location = player.getLocation();
        double dx = location.getX() - x;
        double dy = location.getY() - y;
        double dz = location.getZ() - z;
        return dx * dx + dy * dy + dz * dz < PARTICLE_RANGE_SQUARED;
    }

    private void warn(String message, Throwable failure) {
        logger.log(Level.WARNING, "[clientview] " + message, failure);
    }

    private final class Inbound implements PacketEventsClientViewTransport.Inbound {
        @Override
        public void brand(User user, String brand) {
            UUID playerId = user.getUUID();
            ClientViewObserver observer = observer(playerId, user);
            observer.brand(brand);
            ClientViewServerSession<ClientViewObserver, BlockData> session = registry.session(playerId);
            if (session != null) {
                session.brand(brand);
            }
            negotiator.brandArrived(observer);
        }

        @Override
        public void payload(User user, byte[] payload) {
            ClientViewServerSession<ClientViewObserver, BlockData> session = registry.session(user.getUUID());
            if (session == null) {
                return;
            }
            ClientViewInbound result = session.receive(payload, 0, payload.length);
            if (result == ClientViewInbound.HELLO_ACCEPTED || result == ClientViewInbound.HELLO_DECLINED || result == ClientViewInbound.RESET) {
                verbose.accept("[clientview] " + user.getName() + " " + result + " state=" + session.state()
                    + " caps=0x" + Long.toHexString(session.caps()));
            }
        }

        @Override
        public void pong(User user) {
            ClientViewServerSession<ClientViewObserver, BlockData> session = registry.session(user.getUUID());
            if (session != null) {
                session.pong();
            }
        }

        @Override
        public void disconnected(User user) {
            UUID playerId = user.getUUID();
            ClientViewObserver observer = observers.get(playerId);
            if (observer != null && observer.user() == user) {
                forget(playerId, observer);
            }
        }
    }

    private static final class ShutdownAwareLanes implements Executor {
        private final Executor workers;
        private volatile boolean inline;

        private ShutdownAwareLanes(Executor workers) {
            this.workers = workers;
        }

        private void inline() {
            inline = true;
        }

        @Override
        public void execute(Runnable lane) {
            if (inline) {
                lane.run();
                return;
            }
            workers.execute(lane);
        }
    }

    public record Options(ProjectionWorldViewProvider views,
                          ViewPlateCache<BlockData, World> plates,
                          Function<UUID, ILocalPortal> lookup,
                          BiConsumer<UUID, UUID> releaseVanilla,
                          Executor workers,
                          int mcDataVersion,
                          ClientViewOptions settings,
                          Function<Player, User> users,
                          BukkitClientViewNegotiator.Scheduler scheduler,
                          Logger logger,
                          Consumer<String> verbose,
                          boolean folia) {
    }
}
