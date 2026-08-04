package art.arcane.wormholes.door;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.TrapDoor;

/**
 * A single observation of a real vanilla door or trapdoor.
 *
 * <p>{@code open} is the raw block state. Whether the portal is live is
 * {@link #portalLive()}, which compares it with the configured portal state.
 * The powered flag is retained for diagnostics and lifecycle handling; it never
 * grants traversal by itself.</p>
 */
public record VanillaDoorSnapshot(
	UUID worldId,
	DoorwayPlane plane,
	Door.Hinge hinge,
	boolean open,
	boolean powered)
{
	public VanillaDoorSnapshot
	{
		Objects.requireNonNull(worldId, "worldId");
		Objects.requireNonNull(plane, "plane");
		Objects.requireNonNull(hinge, "hinge");
	}

	/** True while this block state actually presents a usable portal. */
	public boolean portalLive()
	{
		return plane.openState().matches(open);
	}

	public static VanillaDoorSnapshot fromBlockData(
		UUID worldId,
		int blockX,
		int blockY,
		int blockZ,
		Door door)
	{
		return fromBlockData(worldId, blockX, blockY, blockZ, door, DoorOpenState.OPEN);
	}

	public static VanillaDoorSnapshot fromBlockData(
		UUID worldId,
		int blockX,
		int blockY,
		int blockZ,
		Door door,
		DoorOpenState openState)
	{
		Objects.requireNonNull(door, "door");
		return new VanillaDoorSnapshot(
			worldId,
			DoorwayPlane.fromBlockData(blockX, blockY, blockZ, door, openState),
			door.getHinge(),
			door.isOpen(),
			door.isPowered());
	}

	public static VanillaDoorSnapshot fromBlockData(
		UUID worldId,
		int blockX,
		int blockY,
		int blockZ,
		TrapDoor trapDoor,
		DoorOpenState openState)
	{
		Objects.requireNonNull(trapDoor, "trapDoor");
		Objects.requireNonNull(openState, "openState");
		return new VanillaDoorSnapshot(
			worldId,
			DoorwayPlane.fromBlockData(blockX, blockY, blockZ, trapDoor, openState),
			Door.Hinge.LEFT,
			trapDoor.isOpen(),
			trapDoor.isPowered());
	}

	public static Optional<VanillaDoorSnapshot> capture(Block candidate)
	{
		return capture(candidate, DoorOpenState.OPEN);
	}

	/**
	 * Captures an in-world door or trapdoor. Both halves of a hinged door must be
	 * present and matched, and the result is normalized to its lower block; a
	 * trapdoor is a single block and is captured as it stands.
	 */
	public static Optional<VanillaDoorSnapshot> capture(Block candidate, DoorOpenState openState)
	{
		Objects.requireNonNull(candidate, "candidate");
		Objects.requireNonNull(openState, "openState");
		if(candidate.getBlockData() instanceof TrapDoor trapDoor)
		{
			return Optional.of(fromBlockData(
				candidate.getWorld().getUID(),
				candidate.getX(),
				candidate.getY(),
				candidate.getZ(),
				trapDoor,
				openState));
		}
		if(!(candidate.getBlockData() instanceof Door candidateDoor))
		{
			return Optional.empty();
		}

		Block lowerBlock = candidateDoor.getHalf() == Bisected.Half.TOP
			? candidate.getRelative(BlockFace.DOWN)
			: candidate;
		if(!(lowerBlock.getBlockData() instanceof Door lowerDoor)
			|| lowerDoor.getHalf() != Bisected.Half.BOTTOM)
		{
			return Optional.empty();
		}

		Block upperBlock = lowerBlock.getRelative(BlockFace.UP);
		if(upperBlock.getType() != lowerBlock.getType()
			|| !(upperBlock.getBlockData() instanceof Door upperDoor)
			|| upperDoor.getHalf() != Bisected.Half.TOP)
		{
			return Optional.empty();
		}

		DoorwayPlane plane = DoorwayPlane.fromBlockData(
			lowerBlock.getX(),
			lowerBlock.getY(),
			lowerBlock.getZ(),
			lowerDoor,
			openState);
		return Optional.of(new VanillaDoorSnapshot(
			lowerBlock.getWorld().getUID(),
			plane,
			upperDoor.getHinge(),
			lowerDoor.isOpen(),
			upperDoor.isPowered()));
	}
}
