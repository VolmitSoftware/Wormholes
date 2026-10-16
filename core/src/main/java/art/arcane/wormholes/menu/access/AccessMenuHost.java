package art.arcane.wormholes.menu.access;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import art.arcane.wormholes.menu.MenuViewer;

public interface AccessMenuHost {
    Optional<AccessMenuView> access(UUID portalId);

    boolean canManage(MenuViewer viewer, UUID portalId);

    Optional<String> playerName(UUID playerId);

    CompletableFuture<Optional<UUID>> resolvePlayer(String name);

    boolean edit(MenuViewer viewer, UUID portalId, AccessEdit edit);

    AccessKeyOutcome setPermissionKey(MenuViewer viewer, UUID portalId, String key);
}
