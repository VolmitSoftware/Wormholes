package art.arcane.wormholes.ops;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Filtering and paging for {@code /wormholes admin portals list}, with no Bukkit in sight. */
public final class PortalListModel {
    public static final int PAGE_SIZE = 8;
    public static final String STATE_OPEN = "open";
    public static final String STATE_CLOSED = "closed";
    public static final String STATE_LINKED = "linked";
    public static final String STATE_UNLINKED = "unlinked";

    /** One portal as the list renders it. */
    public record PortalRow(UUID id, String name, String world, String type, boolean open, boolean linked,
                            String destination, UUID owner, String ownerName) {
    }

    /** Blank fields match everything. */
    public record Filters(String world, String type, String owner, String state) {
        public static Filters none() {
            return new Filters("", "", "", "");
        }
    }

    /** One rendered page; {@code page} and {@code pages} are one-based. */
    public record Page(List<PortalRow> rows, int page, int pages, int total) {
    }

    private PortalListModel() {
    }

    public static Page page(List<PortalRow> rows, Filters filters, int page) {
        List<PortalRow> matched = new ArrayList<>();
        for (PortalRow row : rows) {
            if (matches(row, filters)) {
                matched.add(row);
            }
        }
        int pages = Math.max(1, (matched.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int clamped = Math.min(Math.max(1, page), pages);
        int from = (clamped - 1) * PAGE_SIZE;
        int to = Math.min(matched.size(), from + PAGE_SIZE);
        return new Page(List.copyOf(matched.subList(from, to)), clamped, pages, matched.size());
    }

    static boolean matches(PortalRow row, Filters filters) {
        if (!blankOrEquals(filters.world(), row.world())) {
            return false;
        }
        if (!blankOrEquals(filters.type(), row.type())) {
            return false;
        }
        if (!filters.owner().isBlank() && !ownerMatches(row, filters.owner())) {
            return false;
        }
        return stateMatches(row, filters.state());
    }

    private static boolean ownerMatches(PortalRow row, String owner) {
        String wanted = owner.trim();
        return wanted.equalsIgnoreCase(row.ownerName())
            || (row.owner() != null && row.owner().toString().equalsIgnoreCase(wanted));
    }

    private static boolean stateMatches(PortalRow row, String state) {
        if (state == null || state.isBlank()) {
            return true;
        }
        return switch (state.trim().toLowerCase(Locale.ROOT)) {
            case STATE_OPEN -> row.open();
            case STATE_CLOSED -> !row.open();
            case STATE_LINKED -> row.linked();
            case STATE_UNLINKED -> !row.linked();
            default -> false;
        };
    }

    private static boolean blankOrEquals(String filter, String value) {
        return filter == null || filter.isBlank() || filter.trim().equalsIgnoreCase(value);
    }
}
