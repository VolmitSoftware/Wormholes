package art.arcane.wormholes.menu.door;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import art.arcane.wormholes.door.DoorAccessRecord;
import art.arcane.wormholes.door.DoorAccessState;
import art.arcane.wormholes.door.DoorKind;
import art.arcane.wormholes.door.DoorOpenState;
import art.arcane.wormholes.door.DoorProjectionState;
import art.arcane.wormholes.menu.MenuViewer;

final class FakeDoorHost implements DoorAccessMenuHost {
    static final UUID DOOR = UUID.fromString("00000000-0000-0000-0000-0000000000d0");
    static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000a0");
    static final UUID ADA = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    static final UUID BEN = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    static final UUID CY = UUID.fromString("c0ffee00-0000-0000-0000-0000000000a3");
    static final UUID EVE = UUID.fromString("00000000-0000-0000-0000-0000000000a5");

    final Set<UUID> managers = new HashSet<>();
    final Map<UUID, DoorAccessState> players = new LinkedHashMap<>();
    final Map<UUID, String> names = new HashMap<>();
    final Map<String, UUID> known = new HashMap<>();
    final List<String> edits = new ArrayList<>();
    final List<String> lookups = new ArrayList<>();
    final List<UUID> saveFailureDoors = new ArrayList<>();
    final List<Throwable> saveFailures = new ArrayList<>();
    DoorOpenState openState = DoorOpenState.OPEN;
    DoorProjectionState projection = DoorProjectionState.INHERIT;
    boolean present = true;
    boolean projectionEnabled = true;
    boolean failEdits;
    CompletableFuture<Boolean> pendingEdit;
    CompletableFuture<Optional<UUID>> pendingLookup;

    FakeDoorHost() {
        players.put(ADA, DoorAccessState.NEUTRAL);
        players.put(BEN, DoorAccessState.WHITELIST);
        players.put(CY, DoorAccessState.BLACKLIST);
        names.put(OWNER, "Olga");
        names.put(ADA, "Ada");
        names.put(BEN, "Ben");
        names.put(EVE, "Eve");
        known.put("Olga", OWNER);
        known.put("Ben", BEN);
        known.put("Eve", EVE);
    }

    @Override
    public Optional<DoorAccessMenuView> door(UUID doorId) {
        if (!present || !doorId.equals(DOOR)) {
            return Optional.empty();
        }
        return Optional.of(new DoorAccessMenuView(DOOR, DoorKind.PAIR, "minecraft:oak_door", openState, projection,
            new DoorAccessRecord(DOOR, OWNER, players)));
    }

    @Override
    public boolean canManage(MenuViewer viewer, UUID doorId) {
        return viewer.administrator() || managers.contains(viewer.id());
    }

    @Override
    public boolean projectionEnabled() {
        return projectionEnabled;
    }

    @Override
    public Optional<String> playerName(UUID playerId) {
        return Optional.ofNullable(names.get(playerId));
    }

    @Override
    public CompletableFuture<Optional<UUID>> resolvePlayer(String name) {
        lookups.add(name);
        if (pendingLookup != null) {
            return pendingLookup;
        }
        return CompletableFuture.completedFuture(Optional.ofNullable(known.get(name)));
    }

    @Override
    public CompletableFuture<Boolean> edit(MenuViewer viewer, UUID doorId, DoorAccessEdit edit) {
        edits.add(edit.toString());
        if (failEdits) {
            return CompletableFuture.failedFuture(new IOException("disk full"));
        }
        boolean changed = switch (edit) {
            case DoorAccessEdit.OpenState state -> {
                boolean differs = openState != state.state();
                openState = state.state();
                yield differs;
            }
            case DoorAccessEdit.Projection state -> {
                boolean differs = projection != state.state();
                projection = state.state();
                yield differs;
            }
            case DoorAccessEdit.AddPlayer add -> players.putIfAbsent(add.playerId(), DoorAccessState.NEUTRAL) == null;
            case DoorAccessEdit.PlayerState state -> players.put(state.playerId(), state.state()) != state.state();
            case DoorAccessEdit.RemovePlayer remove -> players.remove(remove.playerId()) != null;
        };
        if (pendingEdit != null) {
            return pendingEdit;
        }
        return CompletableFuture.completedFuture(Boolean.valueOf(changed));
    }

    @Override
    public void saveFailed(UUID doorId, Throwable cause) {
        saveFailureDoors.add(doorId);
        saveFailures.add(cause);
    }
}
