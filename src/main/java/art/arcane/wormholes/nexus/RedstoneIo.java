package art.arcane.wormholes.nexus;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.config.toml.NexusConfig;
import art.arcane.wormholes.hook.TraversalAttempt;
import art.arcane.wormholes.hook.TraversalObserver;
import art.arcane.wormholes.nexus.FrameIo.ComparatorOutput;
import art.arcane.wormholes.nexus.FrameIo.RedstoneAction;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.LocalPortal;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.RedstoneWire;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockRedstoneEvent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Redstone control of frame portals. A rising edge on a portal's control block runs its configured
 * action; a comparator beside the portal reads either the open state or how busy it has been.
 */
public final class RedstoneIo implements Listener, TraversalObserver {
    private static final int MAX_COMPARATOR_LEVEL = 15;
    private static final long TRAVERSAL_WINDOW_MILLIS = 60_000L;

    private final RedstoneIoIndex index;
    private final Dialer dialer;
    private final Supplier<NexusConfig> config;
    private final Supplier<List<LocalPortal>> portals;
    private final TraversalCounter traversals = new TraversalCounter();

    public RedstoneIo(RedstoneIoIndex index, Dialer dialer, Supplier<NexusConfig> config,
                      Supplier<List<LocalPortal>> portals) {
        this.index = Objects.requireNonNull(index, "index");
        this.dialer = Objects.requireNonNull(dialer, "dialer");
        this.config = Objects.requireNonNull(config, "config");
        this.portals = Objects.requireNonNull(portals, "portals");
    }

    public RedstoneIoIndex index() {
        return index;
    }

    TraversalCounter traversals() {
        return traversals;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void on(BlockRedstoneEvent event) {
        if (!config.get().redstoneEnabled || !isRisingEdge(event.getOldCurrent(), event.getNewCurrent())) {
            return;
        }
        Block block = event.getBlock();
        World world = block.getWorld();
        UUID portalId = index.portalAt(world.getUID(), block.getX(), block.getY(), block.getZ());
        if (portalId == null) {
            return;
        }
        ILocalPortal found = Wormholes.portalManager == null ? null : Wormholes.portalManager.getLocalPortal(portalId);
        if (!(found instanceof LocalPortal portal) || portal.isDestroyed()) {
            index.remove(portalId);
            return;
        }
        NexusPortalExtension state = portal.extension(NexusPortalExtension.class);
        if (state != null) {
            apply(portal, state.frameIo().action());
        }
    }

    /** Only a portal whose comparator actually reads traversals keeps a window; the rest record nothing. */
    @Override
    public void onDeparted(TraversalAttempt attempt) {
        LocalPortal portal = attempt.portal();
        NexusPortalExtension state = portal.extension(NexusPortalExtension.class);
        if (state == null || state.frameIo().comparator() != ComparatorOutput.TRAVERSALS) {
            return;
        }
        traversals.record(portal.getId(), attempt.nowMillis());
    }

    /** Rebuilds the control-block entry for one portal. Called on load, on edit and on destroy. */
    public void sync(LocalPortal portal) {
        NexusPortalExtension state = portal == null ? null : portal.extension(NexusPortalExtension.class);
        if (state == null) {
            return;
        }
        FrameIo io = state.frameIo();
        Location center = portal.isDestroyed() ? null : portal.getCenter();
        if (!io.isWired() || center == null || center.getWorld() == null) {
            index.remove(portal.getId());
            return;
        }
        index.put(portal.getId(), center.getWorld().getUID(),
                center.getBlockX() + io.offsetX(), center.getBlockY() + io.offsetY(), center.getBlockZ() + io.offsetZ());
    }

    public void forget(UUID portalId) {
        index.remove(portalId);
        traversals.forget(portalId);
    }

    /** Drops every traversal window. Called on reload so a restart does not inherit stale counts. */
    public void clear() {
        traversals.clear();
    }

    /** Pushes comparator power for every wired portal. Runs on the one-second nexus task. */
    public void tickComparators(long nowMillis) {
        if (!config.get().redstoneEnabled) {
            return;
        }
        for (LocalPortal portal : portals.get()) {
            NexusPortalExtension state = portal == null ? null : portal.extension(NexusPortalExtension.class);
            if (state == null) {
                continue;
            }
            FrameIo io = state.frameIo();
            if (io.comparator() == ComparatorOutput.NONE || portal.isDestroyed()) {
                continue;
            }
            Location center = portal.getCenter();
            if (center == null || center.getWorld() == null) {
                continue;
            }
            World world = center.getWorld();
            int controlX = center.getBlockX() + io.offsetX();
            int controlY = center.getBlockY() + io.offsetY();
            int controlZ = center.getBlockZ() + io.offsetZ();
            if (!world.isChunkLoaded(controlX >> 4, controlZ >> 4)) {
                continue;
            }
            int level = comparatorLevel(io.comparator(), portal.isOpen(),
                    traversals.inLastMinute(portal.getId(), nowMillis));
            writeOutput(new Location(world, controlX, controlY, controlZ), level);
        }
    }

    /**
     * The comparator tick runs on the global scheduler, so the block read and write are dispatched to
     * the region that owns the control block rather than done from under it.
     */
    private static void writeOutput(Location control, int level) {
        Wormholes plugin = Wormholes.instance;
        if (plugin == null || !FoliaScheduler.runRegion(plugin, control, () -> driveOutput(control.getBlock(), level))) {
            driveOutput(control.getBlock(), level);
        }
    }

    /** A signal that was off and is now on. A signal that was already on is not a new edge. */
    public static boolean isRisingEdge(int oldCurrent, int newCurrent) {
        return oldCurrent <= 0 && newCurrent > 0;
    }

    public static int comparatorLevel(ComparatorOutput output, boolean open, int traversalsLastMinute) {
        return switch (output) {
            case NONE -> 0;
            case STATE -> open ? MAX_COMPARATOR_LEVEL : 0;
            case TRAVERSALS -> Math.min(MAX_COMPARATOR_LEVEL, Math.max(0, traversalsLastMinute));
        };
    }

    private void apply(LocalPortal portal, RedstoneAction action) {
        switch (action) {
            case NONE -> {
            }
            case OPEN -> {
                portal.setOutgoingTraversalsEnabled(true);
                portal.setIncomingTraversalsEnabled(true);
            }
            case CLOSE -> {
                portal.setOutgoingTraversalsEnabled(false);
                portal.setIncomingTraversalsEnabled(false);
            }
            case LOCK -> portal.setOutgoingTraversalsEnabled(false);
            case DIAL_NEXT -> dialer.next(portal, 1, null, System.currentTimeMillis());
            case DIAL_PREV -> dialer.next(portal, -1, null, System.currentTimeMillis());
        }
    }

    /**
     * Drives the redstone dust the operator placed at the control offset. Bukkit cannot force a
     * comparator's own output, so the portal writes a signal strength the operator reads with a
     * comparator or repeater next to that dust. Any other block there is left untouched.
     */
    private static void driveOutput(Block block, int level) {
        BlockData data = block.getBlockData();
        if (!(data instanceof RedstoneWire wire) || wire.getPower() == level) {
            return;
        }
        wire.setPower(level);
        block.setBlockData(wire, false);
    }

    /**
     * Departures per portal inside a sliding one-minute window, bounded: the output saturates at
     * {@link #MAX_COMPARATOR_LEVEL}, so one stamp past that is all the counter ever needs to keep.
     * Departures arrive on the traveler's region thread, so every touch of a deque takes its lock.
     */
    public static final class TraversalCounter {
        private static final int MAX_STAMPS = MAX_COMPARATOR_LEVEL + 1;

        private final ConcurrentHashMap<UUID, Deque<Long>> departures = new ConcurrentHashMap<>();

        public void record(UUID portalId, long atMillis) {
            Deque<Long> stamps = departures.computeIfAbsent(portalId, ignored -> new ArrayDeque<>(MAX_STAMPS));
            synchronized (stamps) {
                stamps.addLast(Long.valueOf(atMillis));
                while (stamps.size() > MAX_STAMPS) {
                    stamps.removeFirst();
                }
            }
        }

        public int inLastMinute(UUID portalId, long nowMillis) {
            Deque<Long> stamps = departures.get(portalId);
            if (stamps == null) {
                return 0;
            }
            synchronized (stamps) {
                Iterator<Long> iterator = stamps.iterator();
                while (iterator.hasNext() && nowMillis - iterator.next().longValue() >= TRAVERSAL_WINDOW_MILLIS) {
                    iterator.remove();
                }
                return stamps.size();
            }
        }

        public void forget(UUID portalId) {
            departures.remove(portalId);
        }

        public void clear() {
            departures.clear();
        }
    }
}
