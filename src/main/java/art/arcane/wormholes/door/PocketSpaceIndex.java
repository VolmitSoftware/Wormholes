package art.arcane.wormholes.door;

import art.arcane.wormholes.survival.doors.dimension.PocketWorldService;
import org.bukkit.block.Block;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

final class PocketSpaceIndex
{
	private final PocketStructureService structures;
	private final ConcurrentHashMap<Long, PocketSpace> spacesByChunk;

	PocketSpaceIndex(PocketStructureService structures)
	{
		this.structures = Objects.requireNonNull(structures, "structures");
		spacesByChunk = new ConcurrentHashMap<>();
	}

	void index(PocketSpace space)
	{
		PocketLayout layout = structures.layout(space);
		for(int chunkX = layout.minX() >> 4; chunkX <= layout.maxX() >> 4; chunkX++)
		{
			for(int chunkZ = layout.minZ() >> 4; chunkZ <= layout.maxZ() >> 4; chunkZ++)
			{
				spacesByChunk.put(chunkKey(chunkX, chunkZ), space);
			}
		}
	}

	PocketSpace spaceAt(int blockX, int blockZ)
	{
		return spacesByChunk.get(chunkKey(blockX >> 4, blockZ >> 4));
	}

	boolean isCoreBlock(Block block)
	{
		if(!PocketWorldService.isPocketWorld(block.getWorld()))
		{
			return false;
		}
		PocketSpace space = spaceAt(block.getX(), block.getZ());
		return space != null && structures.isProtected(space, block.getX(), block.getY(), block.getZ());
	}

	void clear()
	{
		spacesByChunk.clear();
	}

	private static long chunkKey(int chunkX, int chunkZ)
	{
		return ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);
	}
}
