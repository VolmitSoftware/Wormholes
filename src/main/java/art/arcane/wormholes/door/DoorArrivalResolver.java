package art.arcane.wormholes.door;

import art.arcane.wormholes.platform.WormholesPlatform;
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
	private static final int[] NEAR_Y_OFFSETS = {0, 1, -1, 2, -2};

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
		Consumer<Location> success,
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
			Optional<Location> safe = safeDestinationDoorArrival(world, captured.get().plane(), transit);
			if(safe.isEmpty())
			{
				failure.run();
				return;
			}
			success.accept(safe.get());
		}, failure);
	}

	Optional<Location> safeSourceDoorReturn(World world, DoorTransit transit)
	{
		DoorVec3 point = DimensionalDoorManager.arrivalPoint(transit.sourcePlane(), transit);
		float yaw = DimensionalDoorManager.arrivalYaw(transit.sourcePlane(), transit.sourcePlane(), transit);
		return safeVerticalDoorStandingLocation(
			world, point, yaw, transit.pitch(), transit.halfWidth(), transit.height());
	}

	Optional<Location> safeDestinationDoorArrival(
		World world,
		DoorwayPlane destinationPlane,
		DoorTransit transit)
	{
		DoorVec3 point = DimensionalDoorManager.arrivalPoint(destinationPlane, transit);
		float yaw = DimensionalDoorManager.arrivalYaw(transit.sourcePlane(), destinationPlane, transit);
		return safeVerticalDoorStandingLocation(
			world, point, yaw, transit.pitch(), transit.halfWidth(), transit.height());
	}

	Optional<Location> findSafeNear(Location stored, int radius)
	{
		if(isSafeStanding(stored))
		{
			return Optional.of(stored);
		}
		int originX = stored.getBlockX();
		int originY = stored.getBlockY();
		int originZ = stored.getBlockZ();
		for(int distance = 1; distance <= radius; distance++)
		{
			for(int x = -distance; x <= distance; x++)
			{
				for(int z = -distance; z <= distance; z++)
				{
					if(Math.max(Math.abs(x), Math.abs(z)) != distance)
					{
						continue;
					}
					for(int yOffset : NEAR_Y_OFFSETS)
					{
						Location candidate = new Location(stored.getWorld(),
							originX + x + 0.5D, originY + yOffset, originZ + z + 0.5D,
							stored.getYaw(), stored.getPitch());
						if(isSafeStanding(candidate))
						{
							return Optional.of(candidate);
						}
					}
				}
			}
		}
		return Optional.empty();
	}

	static boolean isSafeStanding(Location location)
	{
		return isSafeStanding(location, PLAYER_HALF_WIDTH, PLAYER_HEIGHT);
	}

	static int floor(double value)
	{
		return (int) Math.floor(value);
	}

	private Optional<Location> safeVerticalDoorStandingLocation(
		World world,
		DoorVec3 nominal,
		float yaw,
		float pitch,
		double halfWidth,
		double height)
	{
		return DimensionalDoorManager.findSafeVerticalDoorStanding(nominal, candidate -> isSafeStanding(new Location(
			world, candidate.x(), candidate.y(), candidate.z(), yaw, pitch), halfWidth, height))
			.map(candidate -> new Location(world, candidate.x(), candidate.y(), candidate.z(), yaw, pitch));
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
