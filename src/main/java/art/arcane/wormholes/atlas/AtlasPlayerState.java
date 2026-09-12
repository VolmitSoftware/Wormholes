package art.arcane.wormholes.atlas;

import art.arcane.volmlib.util.json.JSONArray;
import art.arcane.volmlib.util.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** One player's atlas: the portals they found, the ones they pinned, the ones they used, and the guide. */
public final class AtlasPlayerState {
    private final UUID playerId;
    private final Set<UUID> discovered = new LinkedHashSet<>();
    private final List<UUID> favorites = new ArrayList<>();
    private final List<UUID> recents = new ArrayList<>();
    private volatile UUID guideTarget;
    private volatile boolean dirty;

    public AtlasPlayerState(UUID playerId) {
        this.playerId = Objects.requireNonNull(playerId, "playerId");
    }

    public enum FavoriteResult {
        ADDED,
        REMOVED,
        FULL
    }

    public UUID playerId() {
        return playerId;
    }

    public synchronized Set<UUID> discovered() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(discovered));
    }

    public synchronized boolean isDiscovered(UUID portalId) {
        return discovered.contains(portalId);
    }

    /** True when this is the first time the player stood at the portal. */
    public synchronized boolean discover(UUID portalId) {
        if (portalId == null || !discovered.add(portalId)) {
            return false;
        }
        dirty = true;
        return true;
    }

    public synchronized List<UUID> favorites() {
        return List.copyOf(favorites);
    }

    public synchronized boolean isFavorite(UUID portalId) {
        return favorites.contains(portalId);
    }

    public synchronized FavoriteResult toggleFavorite(UUID portalId, int limit) {
        if (favorites.remove(portalId)) {
            dirty = true;
            return FavoriteResult.REMOVED;
        }
        if (favorites.size() >= Math.max(0, limit)) {
            return FavoriteResult.FULL;
        }
        favorites.add(portalId);
        dirty = true;
        return FavoriteResult.ADDED;
    }

    /** Newest first. */
    public synchronized List<UUID> recents() {
        return List.copyOf(recents);
    }

    public synchronized void recordRecent(UUID portalId, int limit) {
        if (portalId == null) {
            return;
        }
        recents.remove(portalId);
        recents.addFirst(portalId);
        while (recents.size() > Math.max(0, limit)) {
            recents.removeLast();
        }
        dirty = true;
    }

    public UUID guideTarget() {
        return guideTarget;
    }

    public void setGuideTarget(UUID portalId) {
        if (Objects.equals(guideTarget, portalId)) {
            return;
        }
        guideTarget = portalId;
        dirty = true;
    }

    public boolean isDirty() {
        return dirty;
    }

    void markClean() {
        dirty = false;
    }

    /** Drops a portal that no longer exists from every list. */
    public synchronized boolean forget(UUID portalId) {
        boolean changed = discovered.remove(portalId) | favorites.remove(portalId) | recents.remove(portalId);
        if (portalId != null && portalId.equals(guideTarget)) {
            guideTarget = null;
            changed = true;
        }
        dirty |= changed;
        return changed;
    }

    public synchronized JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put("player", playerId.toString());
        json.put("discovered", ids(discovered));
        json.put("favorites", ids(favorites));
        json.put("recents", ids(recents));
        if (guideTarget != null) {
            json.put("guide", guideTarget.toString());
        }
        return json;
    }

    public static AtlasPlayerState fromJSON(UUID playerId, JSONObject json) {
        AtlasPlayerState state = new AtlasPlayerState(playerId);
        if (json == null) {
            return state;
        }
        state.discovered.addAll(readIds(json.optJSONArray("discovered")));
        state.favorites.addAll(readIds(json.optJSONArray("favorites")));
        state.recents.addAll(readIds(json.optJSONArray("recents")));
        String guide = json.optString("guide", "");
        state.guideTarget = guide.isBlank() ? null : parse(guide);
        return state;
    }

    private static JSONArray ids(Iterable<UUID> values) {
        JSONArray array = new JSONArray();
        for (UUID value : values) {
            array.put(value.toString());
        }
        return array;
    }

    private static List<UUID> readIds(JSONArray array) {
        List<UUID> values = new ArrayList<>();
        if (array == null) {
            return values;
        }
        for (int index = 0; index < array.length(); index++) {
            UUID parsed = parse(array.optString(index, ""));
            if (parsed != null && !values.contains(parsed)) {
                values.add(parsed);
            }
        }
        return values;
    }

    private static UUID parse(String value) {
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException | NullPointerException notAUuid) {
            return null;
        }
    }
}
