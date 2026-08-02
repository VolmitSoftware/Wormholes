package art.arcane.wormholes.door;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record DoorAccessRecord(UUID itemId, DoorAccessMode mode, UUID ownerId, List<UUID> players) {
    public DoorAccessRecord {
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(ownerId, "ownerId");
        players = List.copyOf(Objects.requireNonNull(players, "players"));
        Set<UUID> unique = new HashSet<>(players.size());
        for (UUID player : players) {
            if (!unique.add(player)) {
                throw new IllegalArgumentException("duplicate access player " + player);
            }
        }
    }

    public DoorAccessRecord withMode(DoorAccessMode updated) {
        Objects.requireNonNull(updated, "mode");
        if (updated == mode) {
            return this;
        }
        return new DoorAccessRecord(itemId, updated, ownerId, players);
    }

    public DoorAccessRecord withPlayerAdded(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        if (players.contains(playerId)) {
            return this;
        }
        ArrayList<UUID> updated = new ArrayList<>(players.size() + 1);
        updated.addAll(players);
        updated.add(playerId);
        return new DoorAccessRecord(itemId, mode, ownerId, updated);
    }

    public DoorAccessRecord withPlayerRemoved(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        if (!players.contains(playerId)) {
            return this;
        }
        ArrayList<UUID> updated = new ArrayList<>(players.size() - 1);
        for (UUID player : players) {
            if (!player.equals(playerId)) {
                updated.add(player);
            }
        }
        return new DoorAccessRecord(itemId, mode, ownerId, updated);
    }
}
