package art.arcane.wormholes.menu.destination;

import java.util.Objects;
import java.util.UUID;

import art.arcane.optics.math.Face;
import art.arcane.wormholes.portal.LocalPortalDestinationModel;

public record DestinationMenuView(UUID id, String name, String world, int x, int y, int z, Face direction, double distanceSquared,
                                  boolean linked, String server, boolean open) {
    public DestinationMenuView {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(direction, "direction");
    }

    public boolean remote() {
        return server != null;
    }

    public LocalPortalDestinationModel.Entry entry() {
        return new LocalPortalDestinationModel.Entry(name, remote() ? server : world, distanceSquared, linked, remote(), open);
    }
}
