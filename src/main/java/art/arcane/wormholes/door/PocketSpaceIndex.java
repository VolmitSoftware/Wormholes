package art.arcane.wormholes.door;

import art.arcane.wormholes.survival.doors.dimension.PocketWorldService;
import org.bukkit.block.Block;

import java.util.ArrayList;
import java.util.List;
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

	/** Claims every chunk the pocket covers, grown rooms included. */
	void index(PocketSpace space)
	{
		Objects.requireNonNull(space, "space");
		for(PocketLayout layout : layouts(space))
		{
			for(int chunkX = layout.minX() >> 4; chunkX <= layout.maxX() >> 4; chunkX++)
			{
				for(int chunkZ = layout.minZ() >> 4; chunkZ <= layout.maxZ() >> 4; chunkZ++)
				{
					spacesByChunk.put(chunkKey(chunkX, chunkZ), space);
				}
			}
		}
	}

	void reindex(PocketSpace previous, PocketSpace updated)
	{
		Objects.requireNonNull(previous, "previous");
		Objects.requireNonNull(updated, "updated");
		for(PocketLayout layout : layouts(previous))
		{
			for(int chunkX = layout.minX() >> 4; chunkX <= layout.maxX() >> 4; chunkX++)
			{
				for(int chunkZ = layout.minZ() >> 4; chunkZ <= layout.maxZ() >> 4; chunkZ++)
				{
					spacesByChunk.remove(chunkKey(chunkX, chunkZ), previous);
				}
			}
		}
		index(updated);
	}

	/** The base room plus every room the pocket has grown. */
	private List<PocketLayout> layouts(PocketSpace space)
	{
		List<PocketLayout> layouts = new ArrayList<>(space.rooms().size() + 1);
		layouts.add(structures.layout(space));
		for(PocketRoom room : space.rooms())
		{
			layouts.add(PocketRooms.layout(space, room));
		}
		return layouts;
	}

	/** True when the block is part of any room's protected shell. */
	static boolean isProtectedBlock(PocketSpace space, PocketStructureService structures, int x, int y, int z)
	{
		Objects.requireNonNull(space, "space");
		Objects.requireNonNull(structures, "structures");
		if(structures.isProtected(space, x, y, z))
		{
			return true;
		}
		for(PocketRoom room : space.rooms())
		{
			if(PocketRooms.layout(space, room).isProtected(x, y, z))
			{
				return true;
			}
		}
		return false;
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
		return space != null
			&& isProtectedBlock(space, structures, block.getX(), block.getY(), block.getZ());
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
