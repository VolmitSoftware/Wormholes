package art.arcane.wormholes.door;

import org.bukkit.block.BlockFace;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Sub-allocator for the rooms of one pocket.
 *
 * <p>A pocket owns an 8192-block slot but only ever built one room in the middle of it. Rooms grow
 * on a 3x3 grid inside that same slot, so a pocket never reaches into its neighbour's: the stride is
 * four times the largest room a pocket may have, which keeps even a maximum-size pocket's outermost
 * room well inside its own half-slot.</p>
 *
 * <p>Room 0 is the original room at the slot centre and is never allocated; it always exists.</p>
 */
public final class PocketRooms {
    /** Four times the largest room, so neighbouring rooms never touch. */
    public static final int ROOM_STRIDE = PocketShell.MAX_SIZE * 4;
    /** The 3x3 grid: one base room and eight neighbours. */
    public static final int GRID_ROOMS = 9;

    private static final PocketRoom BASE = new PocketRoom(0, 0, 0, null, null);
    private static final int[][] GRID_ORDER = {
        {0, -1}, {0, 1}, {1, 0}, {-1, 0},
        {1, -1}, {1, 1}, {-1, -1}, {-1, 1}
    };

    private PocketRooms() {
    }

    public static PocketRoom baseRoom() {
        return BASE;
    }

    /**
     * Picks the cell for a new room, preferring the one straight through the wall the door went on.
     *
     * @param wall the facing of the wall the door was placed against
     * @param maxRooms the configured cap, base room included
     * @return the new room, or empty when the pocket is already at its cap or the grid is full
     */
    public static Optional<PocketRoom> allocate(PocketSpace space, BlockFace wall, int maxRooms) {
        Objects.requireNonNull(space, "space");
        int[] preferred = cellOf(Objects.requireNonNull(wall, "wall"));
        List<PocketRoom> rooms = space.rooms();
        if (rooms.size() + 1 >= Math.min(maxRooms, GRID_ROOMS)) {
            return Optional.empty();
        }
        int index = nextIndex(rooms);
        if (!taken(rooms, preferred[0], preferred[1])) {
            return Optional.of(new PocketRoom(index, preferred[0] * ROOM_STRIDE, preferred[1] * ROOM_STRIDE, null, null));
        }
        for (int[] cell : GRID_ORDER) {
            if (!taken(rooms, cell[0], cell[1])) {
                return Optional.of(new PocketRoom(index, cell[0] * ROOM_STRIDE, cell[1] * ROOM_STRIDE, null, null));
            }
        }
        return Optional.empty();
    }

    /**
     * Whether a pocket door may be used from where the traveler is standing.
     *
     * <p>Outside the void world every pocket door works. Inside it, only a door that leads back into
     * the same pocket does: a pocket inside another pocket has no way home.</p>
     *
     * @param standingIn the pocket the traveler is in, or null when they are in the void world but
     *                   outside every allocated pocket
     */
    public static boolean allowsPocketEntry(
        boolean insidePocketWorld,
        PocketSpace standingIn,
        PocketSpace destination
    ) {
        if (!insidePocketWorld) {
            return true;
        }
        return standingIn != null && destination != null
            && standingIn.spaceId().equals(destination.spaceId());
    }

    /** The room whose cube covers a column of the pocket world, base room included. */
    public static Optional<PocketRoom> roomAt(PocketSpace space, int blockX, int blockZ) {
        Objects.requireNonNull(space, "space");
        List<PocketRoom> candidates = new ArrayList<>(space.rooms().size() + 1);
        candidates.add(BASE);
        candidates.addAll(space.rooms());
        for (PocketRoom room : candidates) {
            PocketLayout layout = layout(space, room);
            if (blockX >= layout.minX() && blockX <= layout.maxX()
                && blockZ >= layout.minZ() && blockZ <= layout.maxZ()) {
                return Optional.of(room);
            }
        }
        return Optional.empty();
    }

    /**
     * The cube one room occupies.
     *
     * <p>The space handed to {@link PocketLayout} is a geometry view shifted onto the room's own
     * centre; it is never persisted and never handed to the allocator.</p>
     */
    public static PocketLayout layout(PocketSpace space, PocketRoom room) {
        Objects.requireNonNull(space, "space");
        Objects.requireNonNull(room, "room");
        return new PocketLayout(new PocketSpace(
            space.spaceId(),
            space.binding(),
            space.slot(),
            Math.addExact(space.centerX(), room.offsetX()),
            space.centerY(),
            Math.addExact(space.centerZ(), room.offsetZ()),
            space.shell()));
    }

    private static int nextIndex(List<PocketRoom> rooms) {
        int highest = 0;
        for (PocketRoom room : rooms) {
            highest = Math.max(highest, room.index());
        }
        return highest + 1;
    }

    private static boolean taken(List<PocketRoom> rooms, int cellX, int cellZ) {
        if (cellX == 0 && cellZ == 0) {
            return true;
        }
        for (PocketRoom room : rooms) {
            if (room.offsetX() == cellX * ROOM_STRIDE && room.offsetZ() == cellZ * ROOM_STRIDE) {
                return true;
            }
        }
        return false;
    }

    private static int[] cellOf(BlockFace wall) {
        return switch (wall) {
            case NORTH -> new int[]{0, -1};
            case SOUTH -> new int[]{0, 1};
            case EAST -> new int[]{1, 0};
            case WEST -> new int[]{-1, 0};
            default -> throw new IllegalArgumentException("A pocket only grows through a cardinal wall: " + wall);
        };
    }
}
