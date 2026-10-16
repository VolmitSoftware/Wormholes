package art.arcane.wormholes.menu.access;

import java.util.Objects;
import java.util.UUID;

import art.arcane.wormholes.access.PortalRole;

public sealed interface AccessEdit permits AccessEdit.SetRole, AccessEdit.RemoveRole, AccessEdit.Listed, AccessEdit.AddGroup,
    AccessEdit.ClearGroups {
    record SetRole(UUID playerId, PortalRole role) implements AccessEdit {
        public SetRole {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(role, "role");
        }
    }

    record RemoveRole(UUID playerId) implements AccessEdit {
        public RemoveRole {
            Objects.requireNonNull(playerId, "playerId");
        }
    }

    record Listed(boolean listed) implements AccessEdit {
    }

    record AddGroup(String node) implements AccessEdit {
        public AddGroup {
            Objects.requireNonNull(node, "node");
        }
    }

    record ClearGroups() implements AccessEdit {
    }
}
