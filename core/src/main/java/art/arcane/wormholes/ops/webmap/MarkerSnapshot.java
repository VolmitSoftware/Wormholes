package art.arcane.wormholes.ops.webmap;

import java.util.UUID;

/** One portal as a web map sees it. Equality is the dirty test, so every rendered field is a component. */
public record MarkerSnapshot(UUID id, String name, String type, String world, double x, double y, double z,
                             boolean open, String destination, boolean listed, int rtpRadius,
                             boolean pocketEntrance) {
}
