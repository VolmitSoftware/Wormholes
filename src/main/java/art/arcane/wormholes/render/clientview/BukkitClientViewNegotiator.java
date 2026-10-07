package art.arcane.wormholes.render.clientview;

import art.arcane.wormholes.network.client.ClientViewChannel;
import java.util.Collection;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

import org.bukkit.Location;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;

import com.github.retrooper.packetevents.protocol.player.User;

import art.arcane.optics.stream.ViewStreamHandshake;
import art.arcane.optics.stream.ViewStreamMessage;
import art.arcane.optics.stream.ViewStreamLimits;
import art.arcane.optics.stream.ViewStreamPhase;
import art.arcane.optics.stream.ViewStreamSession;
import art.arcane.optics.stream.ViewStreamSessionRegistry;
import art.arcane.optics.stream.ViewStreamSessionState;

public final class BukkitClientViewNegotiator implements Listener, PluginMessageListener {
    public static final long PLAY_EXPIRY_TICKS = ViewStreamLimits.PLAY_PHASE_PENDING_TICKS + 1L;
    private static final double TELEPORT_RESET_DISTANCE_SQUARED = 16.0D * 16.0D;

    private final BukkitClientView view;
    private final Function<Player, User> users;
    private final Scheduler scheduler;
    private final Consumer<String> verbose;

    BukkitClientViewNegotiator(BukkitClientView view, Function<Player, User> users, Scheduler scheduler, Consumer<String> verbose) {
        this.view = Objects.requireNonNull(view, "view");
        this.users = Objects.requireNonNull(users, "users");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.verbose = Objects.requireNonNull(verbose, "verbose");
    }

    public ViewStreamSessionState configure(UUID playerId, User user) {
        ViewStreamSessionRegistry<ClientViewObserver, BlockData> registry = view.registry();
        if (user == null || !registry.enabled() || !registry.options().configurationHandshake()) {
            return ViewStreamSessionState.VANILLA;
        }
        ClientViewObserver observer = view.observer(playerId, user);
        if (brand(observer) == ViewStreamHandshake.Brand.VANILLA) {
            return ViewStreamSessionState.VANILLA;
        }
        long started = System.nanoTime();
        ViewStreamSession<ClientViewObserver, BlockData> session = registry.open(playerId, observer, 0L);
        String brand = observer.brand();
        if (brand != null) {
            session.brand(brand);
        }
        view.transport().register(observer);
        if (!session.offer(ViewStreamPhase.CONFIGURATION)) {
            return session.state();
        }
        observer.markOffered();
        view.transport().ping(observer);
        ViewStreamSessionState settled;
        try {
            settled = session.awaitHandshake();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            settled = session.expire();
        }
        verbose.accept("[clientview] " + user.getName() + " configuration handshake " + settled + " after "
            + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) + "ms brand=" + observer.brand());
        return settled;
    }

    public boolean offerPlay(Player player) {
        ViewStreamSessionRegistry<ClientViewObserver, BlockData> registry = view.registry();
        if (!registry.enabled()) {
            return false;
        }
        UUID playerId = player.getUniqueId();
        ClientViewObserver observer = view.observer(playerId, users.apply(player));
        observer.player(player);
        if (observer.offered() || observer.user() == null) {
            return false;
        }
        ViewStreamSession<ClientViewObserver, BlockData> session = registry.session(playerId);
        if (session == null || session.player() != observer) {
            session = registry.open(playerId, observer, 0L);
        }
        return offer(player, observer, session);
    }

    public int reoffer(Collection<? extends Player> online) {
        ViewStreamSessionRegistry<ClientViewObserver, BlockData> registry = view.registry();
        if (!registry.enabled()) {
            return 0;
        }
        int offered = 0;
        for (Player player : online) {
            UUID playerId = player.getUniqueId();
            ClientViewObserver observer = view.observer(playerId, users.apply(player));
            observer.player(player);
            ViewStreamSession<ClientViewObserver, BlockData> session = registry.session(playerId);
            if (observer.user() == null || !capable(player, observer) || (session != null && session.state() != ViewStreamSessionState.VANILLA)) {
                continue;
            }
            if (offer(player, observer, registry.open(playerId, observer, 0L))) {
                offered++;
            }
        }
        return offered;
    }

    void brandArrived(ClientViewObserver observer) {
        Player player = observer.player();
        if (player == null || observer.offered() || brand(observer) != ViewStreamHandshake.Brand.MODDED || !view.registry().enabled()) {
            return;
        }
        scheduler.later(player, () -> offerPlay(player), 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void on(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        ClientViewObserver observer = view.observer(player.getUniqueId(), users.apply(player));
        observer.player(player);
        if (!observer.offered() && brand(observer) == ViewStreamHandshake.Brand.MODDED) {
            offerPlay(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void on(PlayerRegisterChannelEvent event) {
        if (!ClientViewChannel.CHANNEL.equals(event.getChannel())) {
            return;
        }
        offerPlay(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void on(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        ClientViewObserver observer = view.observer(player.getUniqueId());
        if (observer != null && observer.player() == player) {
            view.forget(player.getUniqueId(), observer);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void on(PlayerChangedWorldEvent event) {
        reset(event.getPlayer(), ViewStreamMessage.ResetReason.DIMENSION);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void on(PlayerRespawnEvent event) {
        reset(event.getPlayer(), ViewStreamMessage.ResetReason.RESPAWN);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void on(PlayerTeleportEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || from.getWorld() == null || !from.getWorld().equals(to.getWorld())) {
            return;
        }
        if (from.distanceSquared(to) > TELEPORT_RESET_DISTANCE_SQUARED) {
            reset(event.getPlayer(), ViewStreamMessage.ResetReason.TELEPORT);
        }
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!ClientViewChannel.CHANNEL.equals(channel) || message == null) {
            return;
        }
        ViewStreamSession<ClientViewObserver, BlockData> session = view.registry().session(player.getUniqueId());
        if (session != null) {
            session.receive(message, 0, message.length);
        }
    }

    private boolean offer(Player player, ClientViewObserver observer, ViewStreamSession<ClientViewObserver, BlockData> session) {
        String brand = observer.brand();
        if (brand != null) {
            session.brand(brand);
        }
        view.transport().register(observer);
        if (!session.offer(ViewStreamPhase.PLAY)) {
            return false;
        }
        observer.markOffered();
        verbose.accept("[clientview] " + player.getName() + " offered in play brand=" + brand);
        scheduler.later(player, () -> verbose.accept("[clientview] " + player.getName() + " play handshake " + session.expire()),
            PLAY_EXPIRY_TICKS);
        return true;
    }

    private static boolean capable(Player player, ClientViewObserver observer) {
        return brand(observer) == ViewStreamHandshake.Brand.MODDED || player.getListeningPluginChannels().contains(ClientViewChannel.CHANNEL);
    }

    private static ViewStreamHandshake.Brand brand(ClientViewObserver observer) {
        return ViewStreamHandshake.classifyBrand(observer.brand());
    }

    private void reset(Player player, ViewStreamMessage.ResetReason reason) {
        ViewStreamSession<ClientViewObserver, BlockData> session = view.registry().session(player.getUniqueId());
        if (session != null && session.state() == ViewStreamSessionState.CLIENT_VIEW) {
            session.reset(reason);
        }
    }

    @FunctionalInterface
    public interface Scheduler {
        void later(Player player, Runnable task, long delayTicks);
    }
}
