package art.arcane.wormholes.ops.webmap;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** Diffs the portal markers once a second and hands each installed map plugin only what changed. */
public final class WebMapService {
    private final Supplier<List<MarkerSnapshot>> markers;
    private final List<WebMapPublisher> publishers;
    private final MarkerDirtySet dirty = new MarkerDirtySet();

    public WebMapService(Supplier<List<MarkerSnapshot>> markers, List<WebMapPublisher> publishers) {
        this.markers = Objects.requireNonNull(markers, "markers");
        this.publishers = List.copyOf(publishers);
    }

    /**
     * Publishes the difference and returns how many markers were drawn. With no map plugin installed
     * nothing reads the markers, so the per-second scan over every portal is skipped entirely.
     */
    public int tick(boolean respectListed, boolean linkLines) {
        if (!anyPublisherAvailable()) {
            return 0;
        }
        List<MarkerSnapshot> current = new ArrayList<>();
        for (MarkerSnapshot marker : markers.get()) {
            if (!respectListed || marker.listed()) {
                current.add(marker);
            }
        }
        MarkerDirtySet.Diff diff = dirty.update(current);
        if (diff.isEmpty()) {
            return 0;
        }
        for (WebMapPublisher publisher : publishers) {
            if (publisher.available()) {
                publisher.publish(diff.changed(), diff.removed(), linkLines);
            }
        }
        return diff.changed().size();
    }

    public void clear() {
        dirty.clear();
    }

    private boolean anyPublisherAvailable() {
        for (WebMapPublisher publisher : publishers) {
            if (publisher.available()) {
                return true;
            }
        }
        return false;
    }

    public List<WebMapPublisher> publishers() {
        return publishers;
    }
}
