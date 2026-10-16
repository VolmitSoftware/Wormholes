package art.arcane.wormholes.menu;

import java.util.Objects;
import java.util.UUID;

public sealed interface MenuScope permits MenuScope.Portal, MenuScope.Door, MenuScope.Network, MenuScope.Viewer {
    record Portal(UUID portalId) implements MenuScope {
        public Portal {
            Objects.requireNonNull(portalId, "portalId");
        }
    }

    record Door(UUID doorId) implements MenuScope {
        public Door {
            Objects.requireNonNull(doorId, "doorId");
        }
    }

    record Network(UUID networkId) implements MenuScope {
        public Network {
            Objects.requireNonNull(networkId, "networkId");
        }
    }

    record Viewer(UUID viewerId) implements MenuScope {
        public Viewer {
            Objects.requireNonNull(viewerId, "viewerId");
        }
    }
}
