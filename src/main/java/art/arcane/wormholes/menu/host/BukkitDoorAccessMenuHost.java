package art.arcane.wormholes.menu.host;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.logging.Level;

import org.bukkit.plugin.Plugin;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.door.DimensionalDoorManager;
import art.arcane.wormholes.door.DoorAccessPolicy;
import art.arcane.wormholes.door.DoorAccessRecord;
import art.arcane.wormholes.door.DoorItemService;
import art.arcane.wormholes.door.PlacedDoorEndpoint;
import art.arcane.wormholes.menu.MenuScope;
import art.arcane.wormholes.menu.MenuService;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.door.DoorAccessEdit;
import art.arcane.wormholes.menu.door.DoorAccessMenuHost;
import art.arcane.wormholes.menu.door.DoorAccessMenuView;

public final class BukkitDoorAccessMenuHost implements DoorAccessMenuHost {
    private final Plugin plugin;
    private final Supplier<DimensionalDoorManager> doors;
    private final BukkitPlayerNames names;

    public BukkitDoorAccessMenuHost(Plugin plugin, Supplier<DimensionalDoorManager> doors) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.doors = Objects.requireNonNull(doors, "doors");
        this.names = new BukkitPlayerNames(plugin.getServer());
    }

    @Override
    public Optional<DoorAccessMenuView> door(UUID doorId) {
        DimensionalDoorManager manager = doors.get();
        if (manager == null) {
            return Optional.empty();
        }
        Optional<PlacedDoorEndpoint> endpoint = manager.endpoint(doorId);
        Optional<DoorAccessRecord> record = manager.accessRecord(doorId);
        if (endpoint.isEmpty() || record.isEmpty()) {
            return Optional.empty();
        }
        PlacedDoorEndpoint door = endpoint.get();
        String placard = DoorItemService.defaultMaterial(door.identity().kind(), door.identity().form()).getKey().toString();
        return Optional.of(new DoorAccessMenuView(doorId, door.identity().kind(), placard, door.openState(), door.projection(), record.get()));
    }

    @Override
    public boolean canManage(MenuViewer viewer, UUID doorId) {
        DimensionalDoorManager manager = doors.get();
        return manager != null && manager.accessRecord(doorId)
            .map(record -> DoorAccessPolicy.canManage(record, viewer.id(), viewer.administrator()))
            .orElse(false);
    }

    @Override
    public boolean projectionEnabled() {
        WormholesSettings settings = Wormholes.settings;
        return settings != null && settings.getDoors().projectionEnabled;
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
        DimensionalDoorManager manager = doors.get();
        if (manager == null) {
            return CompletableFuture.completedFuture(false);
        }
        boolean changed;
        try {
            changed = apply(manager, doorId, edit);
        } catch (IOException | RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
        if (changed) {
            MenuService menus = Wormholes.menus;
            if (menus != null) {
                menus.invalidate(new MenuScope.Door(doorId));
            }
        }
        return CompletableFuture.completedFuture(changed);
    }

    @Override
    public void saveFailed(UUID doorId, Throwable cause) {
        plugin.getLogger().log(Level.SEVERE, "Could not save a dimensional-door access change for " + doorId, cause);
    }

    private static boolean apply(DimensionalDoorManager manager, UUID doorId, DoorAccessEdit edit) throws IOException {
        return switch (edit) {
            case DoorAccessEdit.OpenState open -> {
                Optional<PlacedDoorEndpoint> endpoint = manager.endpoint(doorId);
                yield endpoint.isPresent() && manager.applyOpenState(endpoint.get(), open.state());
            }
            case DoorAccessEdit.Projection projection -> {
                Optional<PlacedDoorEndpoint> endpoint = manager.endpoint(doorId);
                yield endpoint.isPresent() && manager.applyProjectionState(endpoint.get(), projection.state());
            }
            case DoorAccessEdit.AddPlayer add -> manager.addAccessPlayer(doorId, add.playerId());
            case DoorAccessEdit.PlayerState state -> manager.applyAccessState(doorId, state.playerId(), state.state());
            case DoorAccessEdit.RemovePlayer remove -> manager.removeAccessPlayer(doorId, remove.playerId());
        };
    }
}
