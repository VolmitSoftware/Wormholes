package art.arcane.wormholes;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.RandomAccess;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.World;

import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.util.AxisAlignedBB;

public final class PortalCandidateSnapshot {
    private static final double CELL_SIZE = 128.0D;
    private static final long MAX_INDEXED_CELLS_PER_PORTAL = 256L;
    private static final PortalCandidateSnapshot EMPTY = new PortalCandidateSnapshot(Map.of());

    private final Map<UUID, WorldGrid> worlds;

    private PortalCandidateSnapshot(Map<UUID, WorldGrid> worlds) {
        this.worlds = worlds;
    }

    public static PortalCandidateSnapshot captureProjection(List<ILocalPortal> portals) {
        return capture(portals, true);
    }

    public static PortalCandidateSnapshot capturePortalWorld(List<ILocalPortal> portals) {
        return capture(portals, false, 0.0D);
    }

    static PortalCandidateSnapshot capturePortalWorld(List<ILocalPortal> portals, double minimumPadding) {
        return capture(portals, false, minimumPadding);
    }

    public List<ILocalPortal> candidates(World world, Location location) {
        if (world == null || location == null) {
            return List.of();
        }
        WorldGrid grid = worlds.get(world.getUID());
        if (grid == null) {
            return List.of();
        }
        return grid.candidates(location.getX(), location.getZ());
    }

    private static PortalCandidateSnapshot capture(List<ILocalPortal> portals, boolean centerWorld) {
        return capture(portals, centerWorld, 0.0D);
    }

    private static PortalCandidateSnapshot capture(List<ILocalPortal> portals, boolean centerWorld, double minimumPadding) {
        if (portals == null || portals.isEmpty()) {
            return EMPTY;
        }
        Map<UUID, MutableWorldGrid> mutableWorlds = new HashMap<UUID, MutableWorldGrid>();
        for (int order = 0; order < portals.size(); order++) {
            ILocalPortal portal = portals.get(order);
            if (portal == null) {
                continue;
            }
            World world = resolveWorld(portal, centerWorld);
            AxisAlignedBB view = indexedView(portal, minimumPadding);
            if (world == null || view == null) {
                continue;
            }
            MutableWorldGrid grid = mutableWorlds.computeIfAbsent(world.getUID(), ignored -> new MutableWorldGrid());
            grid.add(new Entry(portal, order), view);
        }
        if (mutableWorlds.isEmpty()) {
            return EMPTY;
        }
        Map<UUID, WorldGrid> frozenWorlds = new HashMap<UUID, WorldGrid>(mutableWorlds.size() * 2);
        for (Map.Entry<UUID, MutableWorldGrid> entry : mutableWorlds.entrySet()) {
            frozenWorlds.put(entry.getKey(), entry.getValue().freeze());
        }
        return new PortalCandidateSnapshot(Map.copyOf(frozenWorlds));
    }

    private static AxisAlignedBB indexedView(ILocalPortal portal, double minimumPadding) {
        AxisAlignedBB view = portal.getView();
        if (!Double.isFinite(minimumPadding) || minimumPadding <= 0.0D) {
            return view;
        }
        if (view != null && !finite(view)) {
            return view;
        }
        AxisAlignedBB area = portal.getArea();
        if (area == null || !finite(area)) {
            return view;
        }
        AxisAlignedBB padded = new AxisAlignedBB(
            area.getXa() - minimumPadding,
            area.getXb() + minimumPadding,
            area.getYa() - minimumPadding,
            area.getYb() + minimumPadding,
            area.getZa() - minimumPadding,
            area.getZb() + minimumPadding);
        if (view == null) {
            return padded;
        }
        return new AxisAlignedBB(
            Math.min(view.getXa(), padded.getXa()),
            Math.max(view.getXb(), padded.getXb()),
            Math.min(view.getYa(), padded.getYa()),
            Math.max(view.getYb(), padded.getYb()),
            Math.min(view.getZa(), padded.getZa()),
            Math.max(view.getZb(), padded.getZb()));
    }

    private static boolean finite(AxisAlignedBB view) {
        return Double.isFinite(view.getXa()) && Double.isFinite(view.getXb())
            && Double.isFinite(view.getYa()) && Double.isFinite(view.getYb())
            && Double.isFinite(view.getZa()) && Double.isFinite(view.getZb());
    }

    private static World resolveWorld(ILocalPortal portal, boolean centerWorld) {
        if (!centerWorld) {
            return portal.getWorld();
        }
        Location center = portal.getCenter();
        return center == null ? null : center.getWorld();
    }

    private static int cell(double coordinate) {
        return (int) Math.floor(coordinate / CELL_SIZE);
    }

    private static long cellKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private static final class MutableWorldGrid {
        private final Map<Long, List<Entry>> cells;
        private final List<Entry> overflow;

        private MutableWorldGrid() {
            cells = new HashMap<Long, List<Entry>>();
            overflow = new ArrayList<Entry>();
        }

        private void add(Entry entry, AxisAlignedBB view) {
            if (!finite(view)) {
                overflow.add(entry);
                return;
            }
            int minX = cell(view.getXa());
            int maxX = cell(view.getXb());
            int minZ = cell(view.getZa());
            int maxZ = cell(view.getZb());
            long width = (long) maxX - minX + 1L;
            long depth = (long) maxZ - minZ + 1L;
            if (width <= 0L || depth <= 0L || width > MAX_INDEXED_CELLS_PER_PORTAL
                || depth > MAX_INDEXED_CELLS_PER_PORTAL
                || width * depth > MAX_INDEXED_CELLS_PER_PORTAL) {
                overflow.add(entry);
                return;
            }
            for (int x = minX; ; x++) {
                for (int z = minZ; ; z++) {
                    cells.computeIfAbsent(cellKey(x, z), ignored -> new ArrayList<Entry>()).add(entry);
                    if (z == maxZ) {
                        break;
                    }
                }
                if (x == maxX) {
                    break;
                }
            }
        }

        private WorldGrid freeze() {
            Map<Long, PortalEntryList> frozenCells = new HashMap<Long, PortalEntryList>(cells.size() * 2);
            for (Map.Entry<Long, List<Entry>> entry : cells.entrySet()) {
                frozenCells.put(entry.getKey(), PortalEntryList.copyOf(entry.getValue()));
            }
            return new WorldGrid(Map.copyOf(frozenCells), PortalEntryList.copyOf(overflow));
        }

    }

    private static final class WorldGrid {
        private final Map<Long, PortalEntryList> cells;
        private final PortalEntryList overflow;

        private WorldGrid(Map<Long, PortalEntryList> cells, PortalEntryList overflow) {
            this.cells = cells;
            this.overflow = overflow;
        }

        private List<ILocalPortal> candidates(double x, double z) {
            PortalEntryList local = cells.get(cellKey(cell(x), cell(z)));
            if (local == null || local.isEmpty()) {
                return overflow;
            }
            if (overflow.isEmpty()) {
                return local;
            }
            return merge(local.entries, overflow.entries);
        }

        private static List<ILocalPortal> merge(List<Entry> local, List<Entry> overflow) {
            List<ILocalPortal> merged = new ArrayList<ILocalPortal>(local.size() + overflow.size());
            int localIndex = 0;
            int overflowIndex = 0;
            while (localIndex < local.size() || overflowIndex < overflow.size()) {
                if (overflowIndex >= overflow.size()
                    || (localIndex < local.size() && local.get(localIndex).order() < overflow.get(overflowIndex).order())) {
                    merged.add(local.get(localIndex++).portal());
                } else {
                    merged.add(overflow.get(overflowIndex++).portal());
                }
            }
            return List.copyOf(merged);
        }
    }

    private record Entry(ILocalPortal portal, int order) {
    }

    private static final class PortalEntryList extends AbstractList<ILocalPortal> implements RandomAccess {
        private static final PortalEntryList EMPTY = new PortalEntryList(List.of());

        private final List<Entry> entries;

        private PortalEntryList(List<Entry> entries) {
            this.entries = entries;
        }

        private static PortalEntryList copyOf(List<Entry> source) {
            return source.isEmpty() ? EMPTY : new PortalEntryList(List.copyOf(source));
        }

        @Override
        public ILocalPortal get(int index) {
            return entries.get(index).portal();
        }

        @Override
        public int size() {
            return entries.size();
        }
    }
}
