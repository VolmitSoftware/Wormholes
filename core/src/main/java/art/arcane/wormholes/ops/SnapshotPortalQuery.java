package art.arcane.wormholes.ops;

import art.arcane.wormholes.api.portal.PortalQuery;
import art.arcane.wormholes.api.portal.PortalSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** Serves {@link PortalQuery} from the newest published snapshot list. Every read is lock-free. */
public final class SnapshotPortalQuery implements PortalQuery {
    private final AtomicReference<List<PortalSnapshot>> current = new AtomicReference<>(List.of());

    public void publish(List<PortalSnapshot> snapshots) {
        current.set(snapshots == null ? List.of() : List.copyOf(snapshots));
    }

    @Override
    public List<PortalSnapshot> all() {
        return current.get();
    }

    @Override
    public Optional<PortalSnapshot> byId(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        for (PortalSnapshot snapshot : current.get()) {
            if (id.equals(snapshot.id())) {
                return Optional.of(snapshot);
            }
        }
        return Optional.empty();
    }

    @Override
    public List<PortalSnapshot> byWorld(String worldKey) {
        return matching(snapshot -> snapshot.worldKey().equalsIgnoreCase(worldKey), worldKey != null);
    }

    @Override
    public List<PortalSnapshot> byOwner(UUID owner) {
        return matching(snapshot -> owner.equals(snapshot.owner()), owner != null);
    }

    @Override
    public List<PortalSnapshot> byName(String name) {
        return matching(snapshot -> snapshot.name() != null && snapshot.name().equalsIgnoreCase(name), name != null);
    }

    private List<PortalSnapshot> matching(Match match, boolean queryPresent) {
        if (!queryPresent) {
            return List.of();
        }
        List<PortalSnapshot> matches = new ArrayList<>();
        for (PortalSnapshot snapshot : current.get()) {
            if (match.test(snapshot)) {
                matches.add(snapshot);
            }
        }
        return List.copyOf(matches);
    }

    private interface Match {
        boolean test(PortalSnapshot snapshot);
    }
}
