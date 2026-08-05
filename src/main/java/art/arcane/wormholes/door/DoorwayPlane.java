package art.arcane.wormholes.door;

import java.util.Objects;
import java.util.Optional;

import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.TrapDoor;

/**
 * The portal aperture of one placed dimensional door or trapdoor.
 *
 * <p>A hinged door contributes the two-block-high vertical plane at the recessed
 * portal surface of its closed facing. A door may swing left or right, but the
 * opening a player walks through remains the same one-block-wide threshold.</p>
 *
 * <p>A trapdoor contributes the horizontal one-by-one plane its plate occupies
 * when closed - the top or the bottom of its own block per {@link Bisected.Half}
 * - regardless of which way it faces. Facing only decides where the swung plate
 * ends up and where the veil is drawn. The selected {@link DoorOpenState}
 * decides whether traversal crosses the open aperture or touches the closed
 * door surface.</p>
 */
public record DoorwayPlane(
	int blockX,
	int blockY,
	int blockZ,
	BlockFace facing,
	DoorForm form,
	Bisected.Half half,
	DoorOpenState openState)
{
	private static final double EPSILON = 1.0E-7D;
	private static final double MIN_VERTICAL_OFFSET = -0.6D;
	static final double PORTAL_RECESS = 0.0625D;
	static final double PORTAL_THICKNESS = 0.035D;
	static final double PORTAL_THRESHOLD_OFFSET = -(0.5D - PORTAL_RECESS - (PORTAL_THICKNESS / 2.0D));
	static final double TRAPDOOR_PLATE_THICKNESS = 3.0D / 16.0D;
	static final double CONTACT_EPSILON = 0.1D;
	static final double CONTACT_SURFACE_OFFSET = TRAPDOOR_PLATE_THICKNESS / 2.0D;

	public DoorwayPlane
	{
		Objects.requireNonNull(facing, "facing");
		Objects.requireNonNull(form, "form");
		Objects.requireNonNull(half, "half");
		Objects.requireNonNull(openState, "openState");
		if(!isCardinal(facing))
		{
			throw new IllegalArgumentException("A doorway must face north, south, east, or west");
		}
		if(form == DoorForm.DOOR)
		{
			if(half != Bisected.Half.BOTTOM)
			{
				throw new IllegalArgumentException("A hinged doorway is anchored to its lower half");
			}
		}
	}

	public DoorwayPlane(int blockX, int blockY, int blockZ, BlockFace facing)
	{
		this(blockX, blockY, blockZ, facing, DoorForm.DOOR, Bisected.Half.BOTTOM, DoorOpenState.OPEN);
	}

	public static DoorwayPlane trapdoor(
		int blockX,
		int blockY,
		int blockZ,
		BlockFace facing,
		Bisected.Half half,
		DoorOpenState openState)
	{
		return new DoorwayPlane(blockX, blockY, blockZ, facing, DoorForm.TRAPDOOR, half, openState);
	}

	/**
	 * Builds a plane from live vanilla {@link Door} block data. The supplied
	 * coordinates may point at either half; the result is always anchored to the
	 * lower half.
	 */
	public static DoorwayPlane fromBlockData(int blockX, int blockY, int blockZ, Door door)
	{
		return fromBlockData(blockX, blockY, blockZ, door, DoorOpenState.OPEN);
	}

	public static DoorwayPlane fromBlockData(
		int blockX,
		int blockY,
		int blockZ,
		Door door,
		DoorOpenState openState)
	{
		Objects.requireNonNull(door, "door");
		int lowerY = door.getHalf() == Bisected.Half.TOP ? blockY - 1 : blockY;
		return new DoorwayPlane(
			blockX, lowerY, blockZ, door.getFacing(), DoorForm.DOOR, Bisected.Half.BOTTOM, openState);
	}

	/** A trapdoor is a single block, so its coordinates never need normalizing. */
	public static DoorwayPlane fromBlockData(
		int blockX,
		int blockY,
		int blockZ,
		TrapDoor trapDoor,
		DoorOpenState openState)
	{
		Objects.requireNonNull(trapDoor, "trapDoor");
		return trapdoor(blockX, blockY, blockZ, trapDoor.getFacing(), trapDoor.getHalf(), openState);
	}

	public boolean isTrapdoor()
	{
		return form == DoorForm.TRAPDOOR;
	}

	/** True when the plane lies flat, which is exactly the trapdoor case. */
	public boolean horizontal()
	{
		return form == DoorForm.TRAPDOOR;
	}

	public boolean contactSurface()
	{
		return openState == DoorOpenState.CLOSED;
	}

	/**
	 * The world height of a horizontal plane; the head height of a vertical one.
	 *
	 * <p>A trapdoor's plane is the middle of the plate slab, which is exactly where
	 * its veil is drawn, so the surface a traveler sees is the surface that fires.
	 * Hinged doors follow the same rule through {@link #PORTAL_THRESHOLD_OFFSET}.</p>
	 */
	public double planeY()
	{
		if(form != DoorForm.TRAPDOOR)
		{
			return blockY + 1.0D;
		}
		return half == Bisected.Half.TOP
			? blockY + 1.0D - (TRAPDOOR_PLATE_THICKNESS / 2.0D)
			: blockY + (TRAPDOOR_PLATE_THICKNESS / 2.0D);
	}

	double exposedSurfaceY(int sideSign)
	{
		if(form != DoorForm.TRAPDOOR)
		{
			throw new IllegalStateException("Only trapdoors have a horizontal exposed surface");
		}
		if(sideSign == 0)
		{
			throw new IllegalArgumentException("A trapdoor surface side cannot be zero");
		}
		if(half == Bisected.Half.TOP)
		{
			return sideSign > 0
				? blockY + 1.0D
				: blockY + 1.0D - TRAPDOOR_PLATE_THICKNESS;
		}
		return sideSign > 0
			? blockY + TRAPDOOR_PLATE_THICKNESS
			: blockY;
	}

	public double normalX()
	{
		return form == DoorForm.TRAPDOOR ? 0.0D : facing.getModX();
	}

	public double normalY()
	{
		return form == DoorForm.TRAPDOOR ? 1.0D : 0.0D;
	}

	public double normalZ()
	{
		return form == DoorForm.TRAPDOOR ? 0.0D : facing.getModZ();
	}

	public DoorVec3 center()
	{
		if(form == DoorForm.TRAPDOOR)
		{
			return new DoorVec3(blockX + 0.5D, planeY(), blockZ + 0.5D);
		}
		return new DoorVec3(
			blockX + 0.5D + (facing.getModX() * PORTAL_THRESHOLD_OFFSET),
			blockY + 1.0D,
			blockZ + 0.5D + (facing.getModZ() * PORTAL_THRESHOLD_OFFSET));
	}

	/**
	 * Resolves a movement segment against this plane using whichever activation
	 * rule the plane carries: a swept crossing of the open aperture, or contact
	 * with the closed physical surface.
	 */
	public Optional<DoorwayCrossing> intersect(DoorVec3 from, DoorVec3 to)
	{
		return openState == DoorOpenState.OPEN ? crossing(from, to) : contact(from, to);
	}

	public Optional<DoorwayCrossing> intersect(
		DoorVec3 from,
		DoorVec3 to,
		double travelerHalfWidth,
		double travelerHeight)
	{
		if(!Double.isFinite(travelerHalfWidth) || travelerHalfWidth < 0.0D
			|| !Double.isFinite(travelerHeight) || travelerHeight < 0.0D)
		{
			throw new IllegalArgumentException("Traveler dimensions must be finite and non-negative");
		}
		return openState == DoorOpenState.OPEN
			? crossing(from, to)
			: contact(from, to, travelerHalfWidth, travelerHeight);
	}

	/**
	 * Intersects a traveler's movement segment with the doorway aperture.
	 *
	 * <p>Merely moving along the plane is not a crossing. Starting exactly on the
	 * plane and moving away is also ignored: the movement that arrived at the
	 * plane is the crossing event, which prevents stationary travelers from being
	 * pulled through when a door opens around them.</p>
	 */
	public Optional<DoorwayCrossing> crossing(DoorVec3 from, DoorVec3 to)
	{
		Objects.requireNonNull(from, "from");
		Objects.requireNonNull(to, "to");

		DoorVec3 center = center();
		double fromDistance = signedDistance(from, center);
		double toDistance = signedDistance(to, center);
		double normalTravel = toDistance - fromDistance;

		if(Math.abs(normalTravel) <= EPSILON)
		{
			return Optional.empty();
		}

		double fraction = -fromDistance / normalTravel;
		if(fraction <= EPSILON || fraction > 1.0D + EPSILON)
		{
			return Optional.empty();
		}

		fraction = Math.min(1.0D, fraction);
		DoorVec3 point = from.interpolate(to, fraction);
		double lateralOffset = lateralOffset(point, center);
		double secondaryOffset = secondaryOffset(point, center);

		if(!withinAperture(lateralOffset, secondaryOffset))
		{
			return Optional.empty();
		}

		DoorwayCrossing.Direction direction = fromDistance > 0.0D
			? DoorwayCrossing.Direction.FRONT_TO_BACK
			: DoorwayCrossing.Direction.BACK_TO_FRONT;
		return Optional.of(new DoorwayCrossing(
			point, fraction, lateralOffset, secondaryOffset, direction));
	}

	/**
	 * Contact test for a door or trapdoor whose closed surface is the portal.
	 *
	 * <p>Nothing passes through solid matter here: the traveler's collision extent
	 * only has to reach the surface while still moving toward it. A traveler that
	 * was already touching at the start of the segment is ignored.</p>
	 */
	public Optional<DoorwayCrossing> contact(DoorVec3 from, DoorVec3 to)
	{
		return contact(from, to, 0.0D, 0.0D);
	}

	public Optional<DoorwayCrossing> contact(
		DoorVec3 from,
		DoorVec3 to,
		double travelerHalfWidth,
		double travelerHeight)
	{
		Objects.requireNonNull(from, "from");
		Objects.requireNonNull(to, "to");

		DoorVec3 center = center();
		double fromDistance = signedDistance(from, center);
		double toDistance = signedDistance(to, center);
		double lateralOffset = lateralOffset(to, center);
		double secondaryOffset = secondaryOffset(to, center);
		double contactReach = contactReach(fromDistance, toDistance, travelerHalfWidth, travelerHeight);

		if(!touching(toDistance, lateralOffset, secondaryOffset, travelerHalfWidth, contactReach))
		{
			return Optional.empty();
		}
		if(touching(
			fromDistance,
			lateralOffset(from, center),
			secondaryOffset(from, center),
			travelerHalfWidth,
			contactReach))
		{
			return Optional.empty();
		}
		if(Math.abs(toDistance) > Math.abs(fromDistance) + EPSILON)
		{
			return Optional.empty();
		}

		// Walking on from a neighbouring block leaves both distances at zero; that is
		// the exposed face, so it counts as arriving from the positive side.
		double reference = fromDistance == 0.0D ? toDistance : fromDistance;
		DoorwayCrossing.Direction direction = reference < 0.0D
			? DoorwayCrossing.Direction.BACK_TO_FRONT
			: DoorwayCrossing.Direction.FRONT_TO_BACK;
		return Optional.of(new DoorwayCrossing(to, 1.0D, lateralOffset, secondaryOffset, direction));
	}

	public double signedDistance(DoorVec3 point)
	{
		Objects.requireNonNull(point, "point");
		return signedDistance(point, center());
	}

	public DoorVec3 entrySidePoint(DoorwayCrossing.Direction direction, double offset)
	{
		Objects.requireNonNull(direction, "direction");
		return sidePoint(direction.entrySideSign(), offset);
	}

	public DoorVec3 exitSidePoint(DoorwayCrossing.Direction direction, double offset)
	{
		Objects.requireNonNull(direction, "direction");
		return sidePoint(direction.exitSideSign(), offset);
	}

	/** A point one {@code offset} off the plane on the requested side. */
	public DoorVec3 sidePoint(int sign, double offset)
	{
		if(!Double.isFinite(offset) || offset <= 0.0D)
		{
			throw new IllegalArgumentException("Doorway side offset must be finite and positive");
		}
		if(form == DoorForm.TRAPDOOR)
		{
			return new DoorVec3(blockX + 0.5D, planeY() + (offset * sign), blockZ + 0.5D);
		}
		return new DoorVec3(
			blockX + 0.5D + (facing.getModX() * offset * sign),
			blockY,
			blockZ + 0.5D + (facing.getModZ() * offset * sign));
	}

	public float rotateYawTo(DoorwayPlane destination, float yaw)
	{
		Objects.requireNonNull(destination, "destination");
		if(!Float.isFinite(yaw))
		{
			throw new IllegalArgumentException("Yaw must be finite");
		}

		return normalizeYaw(yaw + facingYaw(destination.facing) - facingYaw(facing));
	}

	public float rotateYawToMatchingSide(DoorwayPlane destination, float yaw)
	{
		return normalizeYaw(rotateYawTo(destination, yaw) + 180.0F);
	}

	private double contactReach(
		double fromDistance,
		double toDistance,
		double travelerHalfWidth,
		double travelerHeight)
	{
		if(!horizontal())
		{
			return CONTACT_SURFACE_OFFSET + CONTACT_EPSILON + travelerHalfWidth;
		}
		double reference = Math.abs(fromDistance) > EPSILON ? fromDistance : toDistance;
		double travelerExtent = reference < 0.0D ? travelerHeight : 0.0D;
		return CONTACT_SURFACE_OFFSET + CONTACT_EPSILON + travelerExtent;
	}

	private boolean touching(
		double distance,
		double lateralOffset,
		double secondaryOffset,
		double travelerHalfWidth,
		double contactReach)
	{
		return Math.abs(distance) <= contactReach
			&& withinContactSurface(lateralOffset, secondaryOffset, travelerHalfWidth);
	}

	private boolean withinContactSurface(
		double lateralOffset,
		double secondaryOffset,
		double travelerHalfWidth)
	{
		if(Math.abs(lateralOffset) > 0.5D + travelerHalfWidth + EPSILON)
		{
			return false;
		}
		if(horizontal())
		{
			return Math.abs(secondaryOffset) <= 0.5D + travelerHalfWidth + EPSILON;
		}
		return secondaryOffset >= MIN_VERTICAL_OFFSET - EPSILON
			&& secondaryOffset <= 2.0D + EPSILON;
	}

	private boolean withinAperture(double lateralOffset, double secondaryOffset)
	{
		if(Math.abs(lateralOffset) > 0.5D + EPSILON)
		{
			return false;
		}
		if(form == DoorForm.TRAPDOOR)
		{
			return Math.abs(secondaryOffset) <= 0.5D + EPSILON;
		}
		return secondaryOffset >= MIN_VERTICAL_OFFSET - EPSILON && secondaryOffset <= 2.0D + EPSILON;
	}

	/** Offset along the horizontal axis perpendicular to the facing, for either form. */
	private double lateralOffset(DoorVec3 point, DoorVec3 center)
	{
		return ((point.x() - center.x()) * -facing.getModZ()) + ((point.z() - center.z()) * facing.getModX());
	}

	/**
	 * The second in-plane axis: height above the door base for a vertical plane,
	 * depth along the facing for a horizontal one.
	 */
	private double secondaryOffset(DoorVec3 point, DoorVec3 center)
	{
		if(form == DoorForm.TRAPDOOR)
		{
			return ((point.x() - center.x()) * facing.getModX()) + ((point.z() - center.z()) * facing.getModZ());
		}
		return point.y() - blockY;
	}

	private double signedDistance(DoorVec3 point, DoorVec3 center)
	{
		return ((point.x() - center.x()) * normalX())
			+ ((point.y() - center.y()) * normalY())
			+ ((point.z() - center.z()) * normalZ());
	}

	private static float facingYaw(BlockFace face)
	{
		return switch(face)
		{
			case SOUTH -> 0.0F;
			case WEST -> 90.0F;
			case NORTH -> 180.0F;
			case EAST -> -90.0F;
			default -> throw new IllegalArgumentException("A doorway must face north, south, east, or west");
		};
	}

	private static float normalizeYaw(float yaw)
	{
		float normalized = yaw % 360.0F;
		if(normalized >= 180.0F)
		{
			normalized -= 360.0F;
		}
		else if(normalized < -180.0F)
		{
			normalized += 360.0F;
		}
		return normalized;
	}

	private static boolean isCardinal(BlockFace facing)
	{
		return facing == BlockFace.NORTH
			|| facing == BlockFace.SOUTH
			|| facing == BlockFace.EAST
			|| facing == BlockFace.WEST;
	}
}
