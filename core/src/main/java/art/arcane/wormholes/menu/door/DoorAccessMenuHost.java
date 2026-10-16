package art.arcane.wormholes.menu.door;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import art.arcane.wormholes.menu.MenuViewer;

public interface DoorAccessMenuHost {
    Optional<DoorAccessMenuView> door(UUID doorId);

    boolean canManage(MenuViewer viewer, UUID doorId);

    boolean projectionEnabled();

    Optional<String> playerName(UUID playerId);

    CompletableFuture<Optional<UUID>> resolvePlayer(String name);

    CompletableFuture<Boolean> edit(MenuViewer viewer, UUID doorId, DoorAccessEdit edit);

    void saveFailed(UUID doorId, Throwable cause);
}
