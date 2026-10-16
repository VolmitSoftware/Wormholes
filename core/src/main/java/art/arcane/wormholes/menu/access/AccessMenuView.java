package art.arcane.wormholes.menu.access;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import art.arcane.wormholes.access.PortalRole;
import art.arcane.wormholes.portal.PortalType;

public record AccessMenuView(
    UUID portalId,
    String portalName,
    PortalType type,
    boolean mirror,
    String routerTitle,
    UUID owner,
    String permissionKey,
    int groupCount,
    boolean listed,
    List<Role> roles
) {
    public AccessMenuView {
        Objects.requireNonNull(portalId, "portalId");
        Objects.requireNonNull(portalName, "portalName");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(routerTitle, "routerTitle");
        Objects.requireNonNull(permissionKey, "permissionKey");
        if (groupCount < 0) {
            throw new IllegalArgumentException("group count must not be negative");
        }
        roles = List.copyOf(Objects.requireNonNull(roles, "roles"));
    }

    public PortalRole role(UUID playerId) {
        for (Role entry : roles) {
            if (entry.playerId().equals(playerId)) {
                return entry.role();
            }
        }
        return null;
    }

    public record Role(UUID playerId, PortalRole role) {
        public Role {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(role, "role");
        }
    }
}
