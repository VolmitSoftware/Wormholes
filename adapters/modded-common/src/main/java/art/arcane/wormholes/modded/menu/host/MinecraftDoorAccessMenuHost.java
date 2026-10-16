package art.arcane.wormholes.modded.menu.host;

import art.arcane.wormholes.door.DoorAccessRecord;
import art.arcane.wormholes.door.PlacedDoorEndpoint;
import art.arcane.wormholes.menu.MenuScope;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.door.DoorAccessEdit;
import art.arcane.wormholes.menu.door.DoorAccessMenuHost;
import art.arcane.wormholes.menu.door.DoorAccessMenuView;
import art.arcane.wormholes.modded.MinecraftDoorItems;
import art.arcane.wormholes.modded.MinecraftDoorService;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.menu.MinecraftMenuViewer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public final class MinecraftDoorAccessMenuHost implements DoorAccessMenuHost {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");

    private final WormholesModRuntime runtime;
    private final MinecraftPlayerNames names;

    public MinecraftDoorAccessMenuHost(WormholesModRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.names = new MinecraftPlayerNames(runtime);
    }

    @Override
    public Optional<DoorAccessMenuView> door(UUID doorId) {
        MinecraftDoorService doors = runtime.doors();
        if (!doors.enabled()) {
            return Optional.empty();
        }
        Optional<PlacedDoorEndpoint> endpoint = doors.state().findEndpointByItem(doorId);
        Optional<DoorAccessRecord> record = doors.state().accessRecord(doorId);
        if (endpoint.isEmpty() || record.isEmpty()) {
            return Optional.empty();
        }
        PlacedDoorEndpoint door = endpoint.get();
        String placard = BuiltInRegistries.ITEM.getKey(MinecraftDoorItems.material(door.identity())).toString();
        return Optional.of(new DoorAccessMenuView(doorId, door.identity().kind(), placard, door.openState(), door.projection(), record.get()));
    }

    @Override
    public boolean canManage(MenuViewer viewer, UUID doorId) {
        return runtime.doors().canManage(MinecraftMenuViewer.player(viewer), doorId);
    }

    @Override
    public boolean projectionEnabled() {
        return runtime.doors().projectionEnabled();
    }

    @Override
    public Optional<String> playerName(UUID playerId) {
        return names.name(playerId);
    }

    @Override
    public CompletableFuture<Optional<UUID>> resolvePlayer(String name) {
        return names.resolve(name);
    }

    @Override
    public CompletableFuture<Boolean> edit(MenuViewer viewer, UUID doorId, DoorAccessEdit edit) {
        MinecraftDoorService doors = runtime.doors();
        ServerPlayer player = MinecraftMenuViewer.player(viewer);
        CompletableFuture<Boolean> saved = switch (edit) {
            case DoorAccessEdit.OpenState open -> doors.updateOpenState(player, doorId, open.state());
            case DoorAccessEdit.Projection projection -> doors.updateProjection(player, doorId, projection.state());
            case DoorAccessEdit.AddPlayer add -> doors.addAccessPlayer(player, doorId, add.playerId());
            case DoorAccessEdit.PlayerState state -> doors.updateAccessState(player, doorId,
                new MinecraftDoorService.AccessChange(state.playerId(), state.state()));
            case DoorAccessEdit.RemovePlayer remove -> doors.removeAccessPlayer(player, doorId, remove.playerId());
        };
        return saved.handleAsync((changed, failure) -> {
            if (failure != null) {
                throw failure instanceof CompletionException completion ? completion : new CompletionException(failure);
            }
            if (Boolean.TRUE.equals(changed)) {
                runtime.menus().invalidate(new MenuScope.Door(doorId));
            }
            return changed;
        }, runtime.server());
    }

    @Override
    public void saveFailed(UUID doorId, Throwable cause) {
        LOGGER.error("Could not save a dimensional-door access change for {}", doorId, cause);
    }
}
