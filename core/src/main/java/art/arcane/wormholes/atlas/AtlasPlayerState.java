package art.arcane.wormholes.atlas;

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

    public synchronized void setGuideTarget(UUID portalId) {
        if (Objects.equals(guideTarget, portalId)) {
            return;
        }
        guideTarget = portalId;
        dirty = true;
    }

    public boolean isDirty() {
        return dirty;
    }

    public synchronized void markClean(Snapshot saved) {
        if (snapshot().equals(saved)) {
            dirty = false;
        }
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

    public synchronized Snapshot snapshot() {
        return new Snapshot(playerId, List.copyOf(discovered), List.copyOf(favorites), List.copyOf(recents), guideTarget);
    }

    public static AtlasPlayerState restore(Snapshot snapshot) {
        AtlasPlayerState state = new AtlasPlayerState(snapshot.playerId());
        state.discovered.addAll(snapshot.discovered());
        state.favorites.addAll(snapshot.favorites());
        state.recents.addAll(snapshot.recents());
        state.guideTarget = snapshot.guideTarget();
        return state;
    }

    public record Snapshot(UUID playerId, List<UUID> discovered, List<UUID> favorites, List<UUID> recents, UUID guideTarget) {
        public Snapshot {
            Objects.requireNonNull(playerId, "playerId");
            discovered = List.copyOf(discovered);
            favorites = List.copyOf(favorites);
            recents = List.copyOf(recents);
        }
    }
}
