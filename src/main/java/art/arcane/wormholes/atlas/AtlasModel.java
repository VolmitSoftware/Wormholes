package art.arcane.wormholes.atlas;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Rows, filtering, sorting and paging for the atlas. Pure: the service builds the candidate rows from
 * live portals, and everything the player sees is decided here.
 */
public final class AtlasModel {
    public static final int ENTRIES_PER_PAGE = 45;

    private AtlasModel() {
    }

    /**
     * One portal as the atlas shows it. {@code listed} marks a portal anyone may see without
     * discovering it first: a public network, or a portal an access rule published.
     */
    public record Row(UUID portalId, String name, String world, String destination, String address,
                      double distanceSquared, boolean open, boolean remote, boolean listed) {
        public Row {
            Objects.requireNonNull(portalId, "portalId");
            name = name == null ? "" : name;
            world = world == null ? "" : world;
            destination = destination == null ? "" : destination;
            address = address == null ? "" : address;
        }

        public boolean isNetworked() {
            return !address.isEmpty();
        }
    }

    public enum SortMode {
        SMART,
        NAME,
        WORLD,
        DISTANCE;

        public SortMode next() {
            SortMode[] values = values();
            return values[(ordinal() + 1) % values.length];
        }
    }

    public enum Filter {
        ALL,
        FAVORITES,
        RECENTS;

        public Filter next() {
            Filter[] values = values();
            return values[(ordinal() + 1) % values.length];
        }
    }

    /** Applies discovery and the active filter. Recents come back newest first. */
    public static List<Row> visible(List<Row> candidates, AtlasPlayerState state, boolean discoveryRequired,
                                    Filter filter) {
        List<Row> allowed = new ArrayList<>(candidates.size());
        for (Row row : candidates) {
            if (discoveryRequired && !row.listed() && !state.isDiscovered(row.portalId())) {
                continue;
            }
            allowed.add(row);
        }
        return switch (filter) {
            case ALL -> allowed;
            case FAVORITES -> allowed.stream().filter(row -> state.isFavorite(row.portalId())).toList();
            case RECENTS -> inRecentOrder(allowed, state.recents());
        };
    }

    public static List<Row> sorted(List<Row> rows, AtlasPlayerState state, SortMode mode) {
        List<Row> ordered = new ArrayList<>(rows);
        ordered.sort(comparator(state, mode));
        return ordered;
    }

    public static Comparator<Row> comparator(AtlasPlayerState state, SortMode mode) {
        Comparator<Row> byName = Comparator.comparing(Row::name, String.CASE_INSENSITIVE_ORDER);
        Comparator<Row> byWorld = Comparator.comparing(Row::world, String.CASE_INSENSITIVE_ORDER);
        return switch (mode) {
            case SMART -> Comparator
                    .comparing((Row row) -> !state.isFavorite(row.portalId()))
                    .thenComparing(row -> !row.open())
                    .thenComparingDouble(Row::distanceSquared)
                    .thenComparing(byWorld)
                    .thenComparing(byName);
            case NAME -> byName.thenComparing(byWorld);
            case WORLD -> byWorld.thenComparing(byName);
            case DISTANCE -> Comparator.comparingDouble(Row::distanceSquared).thenComparing(byName).thenComparing(byWorld);
        };
    }

    public static int pageCount(int entryCount) {
        return Math.max(1, (entryCount + ENTRIES_PER_PAGE - 1) / ENTRIES_PER_PAGE);
    }

    public static int clampPage(int page, int pageCount) {
        return Math.max(0, Math.min(pageCount - 1, page));
    }

    public static int pageStart(int page) {
        return page * ENTRIES_PER_PAGE;
    }

    public static int pageEnd(int entryCount, int page) {
        return Math.min(entryCount, pageStart(page) + ENTRIES_PER_PAGE);
    }

    private static List<Row> inRecentOrder(List<Row> allowed, List<UUID> recents) {
        Map<UUID, Row> byId = new HashMap<>();
        for (Row row : allowed) {
            byId.put(row.portalId(), row);
        }
        List<Row> ordered = new ArrayList<>();
        for (UUID portalId : recents) {
            Row row = byId.get(portalId);
            if (row != null) {
                ordered.add(row);
            }
        }
        return ordered;
    }
}
