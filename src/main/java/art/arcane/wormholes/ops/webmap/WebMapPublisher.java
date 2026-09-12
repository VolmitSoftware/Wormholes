package art.arcane.wormholes.ops.webmap;

import java.util.Collection;
import java.util.UUID;

/** A map plugin Wormholes can draw on. Adapters are reflective; runtime never requires the plugin. */
public interface WebMapPublisher {
    String id();

    /** False when the map plugin is absent or the adapter gave up after a failure. */
    boolean available();

    void publish(Collection<MarkerSnapshot> changed, Collection<UUID> removed, boolean linkLines);
}
