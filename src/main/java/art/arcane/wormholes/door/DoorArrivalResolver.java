package art.arcane.wormholes.door;

import art.arcane.wormholes.platform.WormholesPlatform;
import art.arcane.optics.math.Vec3d;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

final class DoorArrivalResolver
{
	private static final double PLAYER_HALF_WIDTH = 0.3D;
	private static final double PLAYER_HEIGHT = 1.8D;
	private static final double COLLISION_EPSILON = 1.0E-7D;

	private final DoorRuntimeIndex runtimes;
	private final DoorChunkLoader chunkLoader;

	DoorArrivalResolver(DoorRuntimeIndex runtimes, DoorChunkLoader chunkLoader)
	{
		this.runtimes = Objects.requireNonNull(runtimes, "runtimes");
		this.chunkLoader = Objects.requireNonNull(chunkLoader, "chunkLoader");
	}

	void loadEndpointArrival(
		PlacedDoorEndpoint endpoint,
		DoorTransit transit,
		Consumer<DoorArrival> success,
		Runnable failure)
	{
		World world = runtimes.world(endpoint.position());
		if(world == null)
		{
			failure.run();
			return;
		}
		chunkLoader.loadChunk(world, endpoint.position().x(), endpoint.position().z(), () ->
		{
			Optional<VanillaDoorSnapshot> captured = runtimes.capture(endpoint, world);
			if(captured.isEmpty())
			{
				failure.run();
				return;
			}
            DoorRuntimeIndex.PreparedOpening opening = transit.preparedCrossing() == null ? null
                : runtimes.openPreparedArrival(endpoint, world, captured.get());
			DoorwayPlane destinationPlane = captured.get().plane();
			Optional<Location> safe = safeDestinationDoorArrival(world, destinationPlane, transit);
			if(safe.isEmpty())
			{
                runtimes.rollbackPreparedArrival(opening);
				failure.run();
				return;
			}
			// Already on the destination region thread with the chunk held, which is
			// the only place the far door may be swung open for the arrival.
            if (opening == null) {
                runtimes.openForArrival(endpoint, world, captured.get());
            }
			success.accept(new DoorArrival(safe.get(), destinationPlane, opening));
		}, failure);
	}

	Optional<Location> safeSourceDoorReturn(World world, DoorTransit transit)
	{
		DoorwayPlane plane = transit.sourcePlane();
		int sideSign = transit.entrySideSign();
		Vec3d point = DoorArrivals.arrivalPoint(plane, transit, sideSign);
		DoorArrivals.Facing facing = DoorArrivals.arrivalFacing(plane, transit, sideSign);
		return safeArrivalLocation(world, point, facing, transit, plane, sideSign);
	}

	Optional<Location> safeDestinationDoorArrival(
		World world,
		DoorwayPlane destinationPlane,
		DoorTransit transit)
	{
		int sideSign = DoorPlanePairing.arrivalSideSign(
			transit.sourcePlane(), destinationPlane, transit.entrySideSign());
		Vec3d point = DoorArrivals.destinationPoint(destinationPlane, transit, sideSign);
		DoorArrivals.Facing facing = DoorArrivals.destinationFacing(destinationPlane, transit, sideSign);
		return safeArrivalLocation(world, point, facing, transit, destinationPlane, sideSign);
	}

	Optional<Location> findSafeNear(Location stored, int radius)
	{
        return DoorArrivals.findSafeNear(new Vec3d(stored.getX(), stored.getY(), stored.getZ()), radius,
            point -> isSafeStanding(new Location(stored.getWorld(), point.x(), point.y(), point.z())))
            .map(point -> new Location(stored.getWorld(), point.x(), point.y(), point.z(), stored.getYaw(), stored.getPitch()));
	}

	static boolean isSafeStanding(Location location)
	{
		return isSafeStanding(location, PLAYER_HALF_WIDTH, PLAYER_HEIGHT);
	}

	/**
	 * Arrival test for one traveler. An object traveler only needs air to occupy:
	 * demanding a floor under it would reject every arrow.
	 */
	static boolean isSafeArrival(Location location, DoorTransit transit)
	{
		return transit.travelerClass() == DoorTravelerClass.OBJECT
			? isPassableVolume(location, transit.halfWidth(), transit.height())
			: isSafeStanding(location, transit.halfWidth(), transit.height());
	}

	static int floor(double value)
	{
		return (int) Math.floor(value);
	}

	/** Where a traveler lands, plus the plane it came out of so momentum can be mapped. */
	record DoorArrival(Location location, DoorwayPlane plane, DoorRuntimeIndex.PreparedOpening opening)
	{
		DoorArrival
		{
			Objects.requireNonNull(location, "location");
			Objects.requireNonNull(plane, "plane");
		}
	}

	private Optional<Location> safeArrivalLocation(
		World world,
		Vec3d nominal,
		DoorArrivals.Facing facing,
		DoorTransit transit,
		DoorwayPlane destination,
		int sideSign)
	{
        if (transit.preparedCrossing() != null) {
            Location candidate = new Location(world, nominal.x(), nominal.y(), nominal.z(), facing.yaw(), facing.pitch());
            boolean throughAperture = destination.horizontal() && !destination.contactSurface();
            boolean fits = destination.horizontal() && destination.contactSurface()
                ? fitsClosedTrapdoorSurface(candidate, transit, destination, sideSign)
                : fitsArrival(candidate, transit, throughAperture);
            return fits ? Optional.of(candidate) : Optional.empty();
        }
		boolean closedTrapdoorSurface = destination.horizontal() && destination.contactSurface();
		if(transit.travelerClass() == DoorTravelerClass.OBJECT || closedTrapdoorSurface)
		{
			Location candidate = new Location(world, nominal.x(), nominal.y(), nominal.z(), facing.yaw(), facing.pitch());
			boolean fits = closedTrapdoorSurface
				? fitsClosedTrapdoorSurface(candidate, transit, destination, sideSign)
				: isPassableVolume(candidate, transit.halfWidth(), transit.height());
			return fits
				? Optional.of(candidate)
				: Optional.empty();
		}
		// Leaving through a trapdoor aperture is a fall, not a step: demanding a floor
		// under the traveler would reject every open drop and strand it at the plate.
		boolean throughAperture = destination.horizontal() && !destination.contactSurface();
		return DoorArrivals.findSafeVerticalDoorStanding(
			nominal,
			DoorPlanePairing.arrivalYOffsets(destination, sideSign),
			candidate -> fitsArrival(
				new Location(world, candidate.x(), candidate.y(), candidate.z(), facing.yaw(), facing.pitch()),
				transit,
				throughAperture))
			.map(candidate -> new Location(world, candidate.x(), candidate.y(), candidate.z(), facing.yaw(), facing.pitch()));
	}

	private static boolean fitsArrival(Location candidate, DoorTransit transit, boolean throughAperture)
	{
		return throughAperture
			? isPassableVolume(candidate, transit.halfWidth(), transit.height())
			: isSafeStanding(candidate, transit.halfWidth(), transit.height());
	}

	private static boolean fitsClosedTrapdoorSurface(
		Location candidate,
		DoorTransit transit,
		DoorwayPlane destination,
		int sideSign)
	{
		double contactY = sideSign > 0
			? candidate.getY()
			: candidate.getY() + transit.height();
		if(Math.abs(contactY - destination.exposedSurfaceY(sideSign)) > COLLISION_EPSILON)
		{
			return false;
		}
		return isPassableVolume(
			candidate,
			transit.halfWidth(),
			transit.height(),
			destination,
			transit.travelerClass() != DoorTravelerClass.OBJECT);
	}

	private static boolean isPassableVolume(Location location, double halfWidth, double height)
	{
		return isPassableVolume(location, halfWidth, height, null, false);
	}

	private static boolean isPassableVolume(
		Location location,
		double halfWidth,
		double height,
		DoorwayPlane supportingSurface,
		boolean rejectHazards)
	{
		World world = location.getWorld();
		if(world == null || location.getBlockY() <= world.getMinHeight()
			|| location.getY() + height >= world.getMaxHeight())
		{
			return false;
		}
		int minX = floor(location.getX() - halfWidth + COLLISION_EPSILON);
		int maxX = floor(location.getX() + halfWidth - COLLISION_EPSILON);
		int minZ = floor(location.getZ() - halfWidth + COLLISION_EPSILON);
		int maxZ = floor(location.getZ() + halfWidth - COLLISION_EPSILON);
		if(!WormholesPlatform.isOwnedByCurrentRegion(world, minX >> 4, minZ >> 4, maxX >> 4, maxZ >> 4))
		{
			return false;
		}
		int lowestY = location.getBlockY();
		int highestY = floor(location.getY() + height - COLLISION_EPSILON);
		for(int x = minX; x <= maxX; x++)
		{
			for(int z = minZ; z <= maxZ; z++)
			{
				for(int y = lowestY; y <= highestY; y++)
				{
					Block occupied = world.getBlockAt(x, y, z);
					if(isNonOverlappingContactSurfaceBlock(
						supportingSurface,
						x,
						y,
						z,
						location.getY(),
						location.getY() + height))
					{
						continue;
					}
					if(!occupied.isPassable() || (rejectHazards && isHazard(occupied.getType())))
					{
						return false;
					}
				}
			}
		}
		return true;
	}

	static boolean isNonOverlappingContactSurfaceBlock(
		DoorwayPlane surface,
		int blockX,
		int blockY,
		int blockZ,
		double minimumY,
		double maximumY)
	{
		return surface != null
			&& surface.horizontal()
			&& surface.contactSurface()
			&& surface.blockX() == blockX
			&& surface.blockY() == blockY
			&& surface.blockZ() == blockZ
			&& (Math.abs(minimumY - surface.exposedSurfaceY(1)) <= COLLISION_EPSILON
				|| Math.abs(maximumY - surface.exposedSurfaceY(-1)) <= COLLISION_EPSILON);
	}

	private static boolean isSafeStanding(Location location, double halfWidth, double height)
	{
		World world = location.getWorld();
		if(world == null || location.getBlockY() <= world.getMinHeight()
			|| location.getY() + height >= world.getMaxHeight())
		{
			return false;
		}
		int minX = floor(location.getX() - halfWidth + COLLISION_EPSILON);
		int maxX = floor(location.getX() + halfWidth - COLLISION_EPSILON);
		int minZ = floor(location.getZ() - halfWidth + COLLISION_EPSILON);
		int maxZ = floor(location.getZ() + halfWidth - COLLISION_EPSILON);
		if(!WormholesPlatform.isOwnedByCurrentRegion(world, minX >> 4, minZ >> 4, maxX >> 4, maxZ >> 4))
		{
			return false;
		}
		int feetY = location.getBlockY();
		int highestY = floor(location.getY() + height - COLLISION_EPSILON);
		for(int x = minX; x <= maxX; x++)
		{
			for(int z = minZ; z <= maxZ; z++)
			{
				Block feet = world.getBlockAt(x, feetY, z);
				Block floor = feet.getRelative(BlockFace.DOWN);
				if(!floor.getType().isSolid() || isHazard(floor.getType()))
				{
					return false;
				}
				for(int y = feetY; y <= highestY; y++)
				{
					Block occupied = world.getBlockAt(x, y, z);
					if(!occupied.isPassable() || isHazard(occupied.getType()))
					{
						return false;
					}
				}
			}
		}
		return true;
	}

	private static boolean isHazard(Material material)
	{
		return material == Material.LAVA
			|| material == Material.FIRE
			|| material == Material.SOUL_FIRE
			|| material == Material.POWDER_SNOW
			|| material == Material.MAGMA_BLOCK
			|| material == Material.CAMPFIRE
			|| material == Material.SOUL_CAMPFIRE
			|| material == Material.CACTUS
			|| material == Material.SWEET_BERRY_BUSH
			|| material == Material.WITHER_ROSE;
	}
}
