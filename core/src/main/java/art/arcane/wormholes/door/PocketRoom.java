package art.arcane.wormholes.door;

import java.util.UUID;

/**
 * One cell of a grown pocket. Room 0 is the pocket's original room at the slot centre; every later
 * room sits at a fixed offset inside the same slot and is reached through an internal door pair.
 *
 * <p>{@code doorItemId} is the door the player placed on the parent room's wall and
 * {@code linkedDoorItemId} its automatically placed mate on this room's wall.</p>
 */
public record PocketRoom(int index, int offsetX, int offsetZ, UUID doorItemId, UUID linkedDoorItemId) {
    public PocketRoom {
        if (index < 0) {
            throw new IllegalArgumentException("room index cannot be negative");
        }
    }
}
