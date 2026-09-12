package art.arcane.wormholes.door;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Who may build in a pocket.
 *
 * <p>Entry is still the door's own access record; the roster only governs what someone may do once
 * inside. The owner is derived from the binding rather than stored, so it cannot drift: a personal
 * pocket belongs to its traveler and a public one to whoever owns its door.</p>
 */
public final class PocketRosterService {
    private final Supplier<DoorStateService> state;
    private final AccessLookup access;

    /** The state service is looked up per call because the door store opens after the manager. */
    public PocketRosterService(Supplier<DoorStateService> state, AccessLookup access) {
        this.state = Objects.requireNonNull(state, "state");
        this.access = Objects.requireNonNull(access, "access");
    }

    public static Optional<UUID> ownerOf(PocketSpace space, AccessLookup access) {
        Objects.requireNonNull(space, "space");
        Objects.requireNonNull(access, "access");
        PocketBinding binding = space.binding();
        return switch (binding.kind()) {
            case PERSONAL -> Optional.of(binding.bindingId());
            case IRON -> access.record(binding.bindingId()).map(DoorAccessRecord::ownerId);
        };
    }

    /** The owner outranks the roster, so an owner listed as a visitor still owns the room. */
    public static PocketRole roleOf(PocketSpace space, UUID ownerId, UUID playerId) {
        Objects.requireNonNull(space, "space");
        Objects.requireNonNull(playerId, "playerId");
        return playerId.equals(ownerId) ? PocketRole.OWNER : space.roster().role(playerId);
    }

    public Optional<UUID> ownerOf(PocketSpace space) {
        return ownerOf(space, access);
    }

    public PocketRole roleOf(PocketSpace space, UUID playerId) {
        return roleOf(space, ownerOf(space).orElse(null), playerId);
    }

    /** @return true when the roster actually changed */
    public boolean assign(UUID spaceId, UUID playerId, PocketRole role) throws IOException {
        Objects.requireNonNull(spaceId, "spaceId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(role, "role");
        DoorStateService store = state.get();
        PocketSpace space = store.findPocketById(spaceId).orElse(null);
        if (space == null) {
            return false;
        }
        PocketRoster updated = space.roster().with(playerId, role);
        if (updated.equals(space.roster())) {
            return false;
        }
        store.replacePocket(space.withRoster(updated));
        return true;
    }

    public boolean remove(UUID spaceId, UUID playerId) throws IOException {
        Objects.requireNonNull(spaceId, "spaceId");
        Objects.requireNonNull(playerId, "playerId");
        DoorStateService store = state.get();
        PocketSpace space = store.findPocketById(spaceId).orElse(null);
        if (space == null || !space.roster().contains(playerId)) {
            return false;
        }
        store.replacePocket(space.withRoster(space.roster().without(playerId)));
        return true;
    }

    /** The door access record for one public-door pocket, which carries its owner. */
    @FunctionalInterface
    public interface AccessLookup {
        Optional<DoorAccessRecord> record(UUID doorItemId);
    }
}
