package art.arcane.wormholes.door;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record DoorAccessRecord(UUID itemId, UUID ownerId, Map<UUID, DoorAccessState> players) {
    public DoorAccessRecord {
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(ownerId, "ownerId");
        // insertion order is the menu's row order, so a LinkedHashMap copy is load bearing
        LinkedHashMap<UUID, DoorAccessState> ordered =
            new LinkedHashMap<>(Objects.requireNonNull(players, "players"));
        for (Map.Entry<UUID, DoorAccessState> entry : ordered.entrySet()) {
            Objects.requireNonNull(entry.getKey(), "playerId");
            Objects.requireNonNull(entry.getValue(), "state");
        }
        players = Collections.unmodifiableMap(ordered);
    }

    public static DoorAccessRecord unrestricted(UUID itemId, UUID ownerId) {
        return new DoorAccessRecord(itemId, ownerId, Map.of());
    }

    public List<UUID> listedPlayers() {
        return List.copyOf(players.keySet());
    }

    public DoorAccessState stateOf(UUID playerId) {
        return playerId == null ? null : players.get(playerId);
    }

    public boolean isListed(UUID playerId) {
        return stateOf(playerId) != null;
    }

    public boolean hasWhitelist() {
        return players.containsValue(DoorAccessState.WHITELIST);
    }

    public DoorAccessRecord withPlayerState(UUID playerId, DoorAccessState state) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(state, "state");
        if (state == players.get(playerId)) {
            return this;
        }
        LinkedHashMap<UUID, DoorAccessState> updated = new LinkedHashMap<>(players);
        updated.put(playerId, state);
        return new DoorAccessRecord(itemId, ownerId, updated);
    }

    public DoorAccessRecord withPlayerRemoved(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        if (!players.containsKey(playerId)) {
            return this;
        }
        LinkedHashMap<UUID, DoorAccessState> updated = new LinkedHashMap<>(players);
        updated.remove(playerId);
        return new DoorAccessRecord(itemId, ownerId, updated);
    }
}
