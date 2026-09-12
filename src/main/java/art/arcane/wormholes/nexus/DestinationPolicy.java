package art.arcane.wormholes.nexus;

import art.arcane.volmlib.util.json.JSONArray;
import art.arcane.volmlib.util.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * The destination rule attached to one portal. {@link #choose} is pure: it reads only its arguments
 * and this record, so the resolver, the scheduler, and the tests all see the same answer.
 */
public record DestinationPolicy(DestinationMode mode, List<DestinationEntry> entries, SelectionRule selection) {
    private static final DestinationPolicy EMPTY =
            new DestinationPolicy(DestinationMode.SINGLE, List.of(), SelectionRule.ROUND_ROBIN);

    public DestinationPolicy {
        mode = mode == null ? DestinationMode.SINGLE : mode;
        entries = entries == null ? List.of() : List.copyOf(entries);
        selection = selection == null ? SelectionRule.ROUND_ROBIN : selection;
    }

    public static DestinationPolicy empty() {
        return EMPTY;
    }

    /** True when this policy, not the ordinary tunnel, decides where a traveler lands. */
    public boolean isActive() {
        return mode != DestinationMode.SINGLE && !entries.isEmpty();
    }

    /**
     * True when the choice needs the traveler or a fresh draw, so it cannot be baked into the
     * projected tunnel and is decided by the resolver at traversal time instead.
     */
    public boolean isPerTraveler() {
        return mode == DestinationMode.PER_PLAYER || mode == DestinationMode.RETURN
                || mode == DestinationMode.WEIGHTED
                || selection == SelectionRule.ENTRY_SIDE || selection == SelectionRule.SNEAK
                || selection == SelectionRule.RANDOM;
    }

    /** True when the scheduler owns this portal's destination: a deterministic, window-driven rotation. */
    public boolean isScheduleDriven() {
        return isActive() && !isPerTraveler();
    }

    public DestinationEntry choose(long worldTime, UUID playerId, boolean frontSide, boolean sneaking, Random random) {
        if (!isActive()) {
            return null;
        }
        if (selection == SelectionRule.ENTRY_SIDE && entries.size() > 1) {
            return entries.get(frontSide ? 0 : 1);
        }
        if (selection == SelectionRule.SNEAK && entries.size() > 1) {
            return entries.get(sneaking ? 1 : 0);
        }

        List<DestinationEntry> open = openEntries(worldTime);
        if (open.isEmpty()) {
            return null;
        }
        if (selection == SelectionRule.RANDOM) {
            return open.get(random.nextInt(open.size()));
        }

        return switch (mode) {
            case ORDERED -> open.getFirst();
            case SCHEDULED -> scheduledEntry(worldTime);
            case WEIGHTED -> weighted(open, random);
            case PER_PLAYER -> open.get(Math.floorMod(playerHash(playerId), open.size()));
            case RETURN, SINGLE -> null;
        };
    }

    public List<DestinationEntry> openEntries(long worldTime) {
        List<DestinationEntry> open = new ArrayList<>(entries.size());
        for (DestinationEntry entry : entries) {
            if (entry.windowContains(worldTime)) {
                open.add(entry);
            }
        }
        return open;
    }

    public DestinationPolicy withMode(DestinationMode newMode) {
        return new DestinationPolicy(newMode, entries, selection);
    }

    public DestinationPolicy withEntries(List<DestinationEntry> newEntries) {
        return new DestinationPolicy(mode, newEntries, selection);
    }

    public DestinationPolicy withSelection(SelectionRule newSelection) {
        return new DestinationPolicy(mode, entries, newSelection);
    }

    public DestinationPolicy withEntry(DestinationEntry entry) {
        List<DestinationEntry> copy = new ArrayList<>(entries);
        copy.add(entry);
        return withEntries(copy);
    }

    public DestinationPolicy withoutEntry(int index) {
        if (index < 0 || index >= entries.size()) {
            return this;
        }
        List<DestinationEntry> copy = new ArrayList<>(entries);
        copy.remove(index);
        return withEntries(copy);
    }

    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put("mode", mode.name());
        json.put("selection", selection.name());
        JSONArray encoded = new JSONArray();
        for (DestinationEntry entry : entries) {
            encoded.put(entry.toJSON());
        }
        json.put("entries", encoded);
        return json;
    }

    public static DestinationPolicy fromJSON(JSONObject json) {
        if (json == null) {
            return EMPTY;
        }
        List<DestinationEntry> entries = new ArrayList<>();
        JSONArray encoded = json.optJSONArray("entries");
        if (encoded != null) {
            for (int index = 0; index < encoded.length(); index++) {
                DestinationEntry entry = DestinationEntry.fromJSON(encoded.optJSONObject(index));
                if (entry != null) {
                    entries.add(entry);
                }
            }
        }
        return new DestinationPolicy(DestinationMode.parse(json.optString("mode", ""), DestinationMode.SINGLE),
                entries, SelectionRule.parse(json.optString("selection", ""), SelectionRule.ROUND_ROBIN));
    }

    private DestinationEntry scheduledEntry(long worldTime) {
        for (DestinationEntry entry : entries) {
            if (entry.hasWindow() && entry.windowContains(worldTime)) {
                return entry;
            }
        }
        return null;
    }

    private static DestinationEntry weighted(List<DestinationEntry> open, Random random) {
        int total = 0;
        for (DestinationEntry entry : open) {
            total += entry.weight();
        }
        int roll = random.nextInt(total);
        for (DestinationEntry entry : open) {
            roll -= entry.weight();
            if (roll < 0) {
                return entry;
            }
        }
        return open.getLast();
    }

    private static int playerHash(UUID playerId) {
        if (playerId == null) {
            return 0;
        }
        long mixed = playerId.getMostSignificantBits() ^ playerId.getLeastSignificantBits();
        return (int) (mixed ^ (mixed >>> 32));
    }
}
