package art.arcane.wormholes.door;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Who is on a pocket's roster and in what standing. Anyone absent is a {@link PocketRole#VISITOR};
 * the roster only records the players who were given a standing.
 */
public record PocketRoster(Map<UUID, PocketRole> members) {
    private static final PocketRoster EMPTY = new PocketRoster(Map.of());

    public PocketRoster {
        members = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(members, "members")));
    }

    public static PocketRoster empty() {
        return EMPTY;
    }

    public PocketRole role(UUID playerId) {
        return members.getOrDefault(Objects.requireNonNull(playerId, "playerId"), PocketRole.VISITOR);
    }

    public boolean contains(UUID playerId) {
        return members.containsKey(Objects.requireNonNull(playerId, "playerId"));
    }

    public PocketRoster with(UUID playerId, PocketRole role) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(role, "role");
        if (role == members.get(playerId)) {
            return this;
        }
        LinkedHashMap<UUID, PocketRole> updated = new LinkedHashMap<>(members);
        updated.put(playerId, role);
        return new PocketRoster(updated);
    }

    public PocketRoster without(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        if (!members.containsKey(playerId)) {
            return this;
        }
        LinkedHashMap<UUID, PocketRole> updated = new LinkedHashMap<>(members);
        updated.remove(playerId);
        return new PocketRoster(updated);
    }
}
