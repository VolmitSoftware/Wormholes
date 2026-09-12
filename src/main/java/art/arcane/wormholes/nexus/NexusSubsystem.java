package art.arcane.wormholes.nexus;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.WormholesSubsystems;
import art.arcane.wormholes.atlas.AtlasRuntime;
import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.config.toml.NexusConfig;
import art.arcane.wormholes.hook.WormholesRegistrar;
import art.arcane.wormholes.hook.WormholesSubsystem;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.ITunnel;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.util.J;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Lifecycle entry point for the nexus lane: networks, dialing, destinations, redstone, and the atlas. */
public final class NexusSubsystem implements WormholesSubsystem, NexusPortalListener {
    private static final int COMPARATOR_INTERVAL_TICKS = 20;

    private final ReturnAddresses returns = new ReturnAddresses();
    private final ReciprocalLinks links = new ReciprocalLinks(new PortalManagerLinker());

    private final RedstoneIoIndex redstoneIndex = new RedstoneIoIndex();

    private volatile NetworkRegistry registry;
    private volatile Dialer dialer;
    private volatile DestinationScheduler scheduler;
    private volatile RedstoneIo redstoneIo;
    private volatile DialMenu dialMenu;
    private volatile DialGestures dialGestures;
    private volatile AtlasRuntime atlas;
    private volatile int schedulerTask = -1;
    private volatile int comparatorTask = -1;
    private volatile int schedulerIntervalTicks;

    @Override
    public String id() {
        return "nexus";
    }

    public NetworkRegistry registry() {
        return registry;
    }

    public Dialer dialer() {
        return dialer;
    }

    public ReturnAddresses returnAddresses() {
        return returns;
    }

    public ReciprocalLinks reciprocalLinks() {
        return links;
    }

    public RedstoneIo redstoneIo() {
        return redstoneIo;
    }

    public AtlasRuntime atlas() {
        return atlas;
    }

    /** The live lane instance, or null before enable and after teardown. */
    public static NexusSubsystem active() {
        WormholesSubsystems subsystems = Wormholes.subsystems;
        return subsystems == null ? null : subsystems.get(NexusSubsystem.class);
    }

    @Override
    public void register(WormholesRegistrar registrar) {
        Path networks = Wormholes.instance.getDataFolder().toPath().resolve("atlas").resolve("networks");
        NetworkRegistry created = new NetworkRegistry(networks);
        registry = created;
        dialer = new Dialer(created, NexusSubsystem::applyTunnel, NexusSubsystem::config);
        RedstoneIo io = new RedstoneIo(redstoneIndex, dialer, NexusSubsystem::config, NexusSubsystem::loadedPortals);
        redstoneIo = io;
        DialMenu menu = new DialMenu(created, dialer);
        dialMenu = menu;
        dialGestures = new DialGestures(created, dialer, menu);
        registrar.portalMenuEntry(new NetworkMenuEntry(created, new NetworkMenu(created, menu, io)));
        registrar.portalExtension(new NexusExtensionFactory(this));
        registrar.traversalObserver(returns);
        registrar.traversalObserver(io);
        registrar.destinationResolver(new NexusDestinationResolver(created, returns,
                new NexusDestinationResolver.BukkitTunnels(), NexusSubsystem::worldTime));
        AtlasRuntime runtime = new AtlasRuntime(
                Wormholes.instance.getDataFolder().toPath().resolve("atlas").resolve("players"), created, menu);
        atlas = runtime;
        registrar.traversalObserver(runtime.service());
    }

    @Override
    public void start(Wormholes plugin) {
        NetworkRegistry active = registry;
        active.load();
        scheduler = new DestinationScheduler(active, dialer, NexusSubsystem::loadedPortals,
                NexusSubsystem::worldTime, NexusSubsystem::config);
        RedstoneIo io = redstoneIo;
        plugin.registerListener(io);
        plugin.registerListener(dialGestures);
        for (LocalPortal portal : loadedPortals()) {
            io.sync(portal);
        }
        comparatorTask = J.sr(() -> io.tickComparators(System.currentTimeMillis()), COMPARATOR_INTERVAL_TICKS);
        atlas.start(plugin);
        startScheduler(config().schedulerIntervalTicks);
    }

    @Override
    public void stop() {
        stopScheduler();
        if (comparatorTask != -1) {
            J.csr(comparatorTask);
            comparatorTask = -1;
        }
        RedstoneIo io = redstoneIo;
        if (Wormholes.instance != null) {
            if (io != null) {
                Wormholes.instance.unregisterListener(io);
            }
            if (dialGestures != null) {
                Wormholes.instance.unregisterListener(dialGestures);
            }
        }
        redstoneIndex.clear();
        if (io != null) {
            io.clear();
        }
        if (atlas != null) {
            atlas.stop();
        }
        returns.clear();
        scheduler = null;
    }

    /**
     * Only the task cadence needs rescheduling: the address alphabet, length, debounce and hold are
     * read live from {@link #config()} on every use, so a reload takes effect on the next call.
     */
    @Override
    public void onSettingsReloaded(WormholesSettings settings) {
        int interval = Math.max(1, settings.getNexus().schedulerIntervalTicks);
        if (scheduler != null && interval != schedulerIntervalTicks) {
            stopScheduler();
            startScheduler(interval);
        }
    }

    @Override
    public void onPortalDestroyed(LocalPortal portal, NexusPortalExtension extension) {
        RedstoneIo io = redstoneIo;
        if (io != null) {
            io.forget(portal.getId());
        }
        AtlasRuntime runtime = atlas;
        if (runtime != null) {
            runtime.service().forgetPortal(portal.getId());
        }
        links.onPortalDestroyed(portal, extension);
        NetworkRegistry activeRegistry = registry;
        if (activeRegistry == null || extension.networkId() == null) {
            return;
        }
        PortalNetwork network = activeRegistry.byId(extension.networkId());
        if (network == null || !network.members().containsKey(portal.getId())) {
            return;
        }
        try {
            activeRegistry.save(network.withoutMember(portal.getId()));
        } catch (IOException failure) {
            Wormholes.instance.getLogger().warning("nexus could not drop " + portal.getId() + " from " + network.name());
        }
    }

    private void startScheduler(int intervalTicks) {
        int interval = Math.max(1, intervalTicks);
        schedulerIntervalTicks = interval;
        schedulerTask = J.sr(() -> {
            DestinationScheduler active = scheduler;
            if (active != null) {
                active.tick(System.currentTimeMillis());
            }
        }, interval);
    }

    private void stopScheduler() {
        if (schedulerTask != -1) {
            J.csr(schedulerTask);
            schedulerTask = -1;
        }
    }

    /** Live nexus settings, or defaults before the plugin has loaded any. */
    public static NexusConfig config() {
        WormholesSettings settings = Wormholes.settings;
        return settings == null ? new NexusConfig() : settings.getNexus();
    }

    static List<LocalPortal> loadedPortals() {
        if (Wormholes.portalManager == null) {
            return List.of();
        }
        List<ILocalPortal> all = Wormholes.portalManager.getLocalPortals();
        List<LocalPortal> local = new ArrayList<>(all.size());
        for (ILocalPortal portal : all) {
            if (portal instanceof LocalPortal localPortal) {
                local.add(localPortal);
            }
        }
        return local;
    }

    private static long worldTime(LocalPortal portal) {
        return portal.getWorld() == null ? 0L : portal.getWorld().getFullTime();
    }

    /** Reciprocal linking through the live portal manager. */
    private static final class PortalManagerLinker implements ReciprocalLinks.Linker {
        @Override
        public LocalPortal portal(UUID portalId) {
            ILocalPortal found = Wormholes.portalManager == null ? null : Wormholes.portalManager.getLocalPortal(portalId);
            return found instanceof LocalPortal local ? local : null;
        }

        @Override
        public UUID destinationOf(LocalPortal portal) {
            ITunnel tunnel = portal.getTunnel();
            return tunnel == null ? null : tunnel.getDestinationId();
        }

        @Override
        public boolean link(LocalPortal from, LocalPortal to) {
            return from.setDestination(to);
        }

        @Override
        public void unlink(LocalPortal portal) {
            portal.unlink();
        }
    }

    private static boolean applyTunnel(LocalPortal portal, NetworkMember member) {
        if (!member.isLocal()) {
            return portal.linkRemote(member.serverName(), member.portalId());
        }
        ILocalPortal destination = Wormholes.portalManager == null
                ? null : Wormholes.portalManager.getLocalPortal(member.portalId());
        return destination != null && portal.setDestination(destination);
    }
}
