package art.arcane.wormholes.door;

import art.arcane.optics.math.Face;
import art.arcane.wormholes.util.Cuboid;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.TrapDoor;

import java.util.Objects;

public final class BukkitDoorGeometry {
    private BukkitDoorGeometry() {
    }

    public static Face direction(BlockFace facing) {
        return switch (Objects.requireNonNull(facing, "facing")) {
            case NORTH -> Face.N;
            case SOUTH -> Face.S;
            case EAST -> Face.E;
            case WEST -> Face.W;
            case UP -> Face.U;
            case DOWN -> Face.D;
            default -> throw new IllegalArgumentException("Door facing must be axial: " + facing);
        };
    }

    public static BlockFace facing(Face direction) {
        return switch (Objects.requireNonNull(direction, "direction")) {
            case N -> BlockFace.NORTH;
            case S -> BlockFace.SOUTH;
            case E -> BlockFace.EAST;
            case W -> BlockFace.WEST;
            case U -> BlockFace.UP;
            case D -> BlockFace.DOWN;
        };
    }

    public static DoorHalf half(Bisected.Half half) {
        return switch (Objects.requireNonNull(half, "half")) {
            case TOP -> DoorHalf.TOP;
            case BOTTOM -> DoorHalf.BOTTOM;
        };
    }

	/**
	 * Builds a plane from live vanilla {@link Door} block data. The supplied
	 * coordinates may point at either half; the result is always anchored to the
	 * lower half.
	 */
	public static DoorwayPlane plane(int blockX, int blockY, int blockZ, Door door)
	{
		return plane(blockX, blockY, blockZ, door, DoorOpenState.OPEN);
	}

	public static DoorwayPlane plane(
		int blockX,
		int blockY,
		int blockZ,
		Door door,
		DoorOpenState openState)
	{
		Objects.requireNonNull(door, "door");
		int lowerY = door.getHalf() == Bisected.Half.TOP ? blockY - 1 : blockY;
		return new DoorwayPlane(
			blockX, lowerY, blockZ, direction(door.getFacing()), DoorForm.DOOR, DoorHalf.BOTTOM, openState);
	}

	/** A trapdoor is a single block, so its coordinates never need normalizing. */
	public static DoorwayPlane plane(
		int blockX,
		int blockY,
		int blockZ,
		TrapDoor trapDoor,
		DoorOpenState openState)
	{
		Objects.requireNonNull(trapDoor, "trapDoor");
		return DoorwayPlane.trapdoor(blockX, blockY, blockZ, direction(trapDoor.getFacing()), half(trapDoor.getHalf()), openState);
	}

    /** The cells a traveler passes through: both door halves, or the single trapdoor block. */
    public static Cuboid cells(World world, DoorwayPlane plane) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(plane, "plane");
        int topY = plane.form() == DoorForm.TRAPDOOR ? plane.blockY() : plane.blockY() + 1;
        return new Cuboid(world,
            plane.blockX(), plane.blockY(), plane.blockZ(),
            plane.blockX(), topY, plane.blockZ());
    }

}
