package art.arcane.wormholes.door;

import java.util.Objects;

/** A dimensional-door identity bound to the lower half of a physical door. */
public record PlacedDoorEndpoint(
    DoorPosition position,
    DoorItemIdentity identity,
    DoorOpenState openState,
    DoorProjectionState projection
) {
    public PlacedDoorEndpoint {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(openState, "openState");
        Objects.requireNonNull(projection, "projection");
    }

    public PlacedDoorEndpoint(DoorPosition position, DoorItemIdentity identity) {
        this(position, identity, DoorOpenState.OPEN);
    }

    public PlacedDoorEndpoint(DoorPosition position, DoorItemIdentity identity, DoorOpenState openState) {
        this(position, identity, openState, DoorProjectionState.INHERIT);
    }

    public PlacedDoorEndpoint withOpenState(DoorOpenState state) {
        Objects.requireNonNull(state, "state");
        return state == openState ? this : new PlacedDoorEndpoint(position, identity, state, projection);
    }

    public PlacedDoorEndpoint withProjection(DoorProjectionState state) {
        Objects.requireNonNull(state, "state");
        return state == projection ? this : new PlacedDoorEndpoint(position, identity, openState, state);
    }
}
