package art.arcane.wormholes.menu.door;

import java.util.Objects;
import java.util.UUID;

import art.arcane.wormholes.door.DoorAccessRecord;
import art.arcane.wormholes.door.DoorKind;
import art.arcane.wormholes.door.DoorOpenState;
import art.arcane.wormholes.door.DoorProjectionState;

public record DoorAccessMenuView(
    UUID doorId,
    DoorKind kind,
    String placardItemId,
    DoorOpenState openState,
    DoorProjectionState projection,
    DoorAccessRecord record
) {
    public DoorAccessMenuView {
        Objects.requireNonNull(doorId, "doorId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(placardItemId, "placardItemId");
        Objects.requireNonNull(openState, "openState");
        Objects.requireNonNull(projection, "projection");
        Objects.requireNonNull(record, "record");
    }
}
