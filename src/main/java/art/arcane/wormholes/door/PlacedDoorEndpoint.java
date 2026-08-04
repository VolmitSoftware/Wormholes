package art.arcane.wormholes.door;

import java.util.Objects;

/** A dimensional-door identity bound to the lower half of a physical door. */
public record PlacedDoorEndpoint(DoorPosition position, DoorItemIdentity identity, DoorOpenState openState) {
    public PlacedDoorEndpoint {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(openState, "openState");
    }

    public PlacedDoorEndpoint(DoorPosition position, DoorItemIdentity identity) {
        this(position, identity, DoorOpenState.OPEN);
    }

    public PlacedDoorEndpoint withOpenState(DoorOpenState state) {
        Objects.requireNonNull(state, "state");
        return state == openState ? this : new PlacedDoorEndpoint(position, identity, state);
    }
}
