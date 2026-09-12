package art.arcane.wormholes.ops.webmap;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Keeps the last published marker set so only real changes reach a map plugin. */
public final class MarkerDirtySet {
    /** Markers to draw and markers to erase since the previous update. */
    public record Diff(List<MarkerSnapshot> changed, List<UUID> removed) {
        public boolean isEmpty() {
            return changed.isEmpty() && removed.isEmpty();
        }
    }

    private final Map<UUID, MarkerSnapshot> published = new LinkedHashMap<>();

    public Diff update(Collection<MarkerSnapshot> current) {
        Map<UUID, MarkerSnapshot> incoming = new LinkedHashMap<>();
        for (MarkerSnapshot marker : current) {
            incoming.put(marker.id(), marker);
        }
        List<MarkerSnapshot> changed = new ArrayList<>();
        for (MarkerSnapshot marker : incoming.values()) {
            if (!marker.equals(published.get(marker.id()))) {
                changed.add(marker);
            }
        }
        List<UUID> removed = new ArrayList<>();
        for (UUID id : published.keySet()) {
            if (!incoming.containsKey(id)) {
                removed.add(id);
            }
        }
        published.clear();
        published.putAll(incoming);
        return new Diff(List.copyOf(changed), List.copyOf(removed));
    }

    public void clear() {
        published.clear();
    }

    public int size() {
        return published.size();
    }
}
