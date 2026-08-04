package art.arcane.wormholes.door;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

final class DoorStoreSnapshotTest {
    private static final UUID WORLD_ID = new UUID(0, 900);

    @Test
    void bothDoorFormsMayBeActiveWhileClosed() {
        PlacedDoorEndpoint door = new PlacedDoorEndpoint(
            position(1),
            DoorItemIdentity.publicDoor(new UUID(0, 901)),
            DoorOpenState.CLOSED
        );
        PlacedDoorEndpoint trapdoor = new PlacedDoorEndpoint(
            position(2),
            DoorItemIdentity.publicDoor(new UUID(0, 902), DoorForm.TRAPDOOR),
            DoorOpenState.CLOSED
        );

        DoorStoreSnapshot stored = snapshot(door, trapdoor);

        assertEquals(DoorOpenState.CLOSED, stored.endpoints().get(0).openState());
        assertEquals(DoorOpenState.CLOSED, stored.endpoints().get(1).openState());
    }

    @Test
    void everyEndpointDefaultsToOpen() {
        PlacedDoorEndpoint door = new PlacedDoorEndpoint(
            position(3),
            DoorItemIdentity.personal(new UUID(0, 903))
        );

        assertEquals(DoorOpenState.OPEN, door.openState());
        assertEquals(door, door.withOpenState(DoorOpenState.OPEN));
        assertEquals(DoorOpenState.CLOSED, door.withOpenState(DoorOpenState.CLOSED).openState());
        assertEquals(1, snapshot(door).endpoints().size());
        assertThrows(NullPointerException.class, () -> door.withOpenState(null));
    }

    private static DoorStoreSnapshot snapshot(PlacedDoorEndpoint... endpoints) {
        return new DoorStoreSnapshot(
            DoorStoreSnapshot.CURRENT_SCHEMA, 0, List.of(), List.of(endpoints),
            List.of(), List.of(), List.of()
        );
    }

    private static DoorPosition position(int x) {
        return new DoorPosition(WORLD_ID, "minecraft:overworld", x, 64, 2);
    }
}
