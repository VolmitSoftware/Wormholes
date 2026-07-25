package art.arcane.wormholes.portal.vanilla;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.portal.ILocalPortal;

final class VanillaPortalCleanup
{
	private VanillaPortalCleanup()
	{
	}

	@FunctionalInterface
	interface RegionDispatch
	{
		boolean runRegion(World world, int chunkX, int chunkZ, Runnable command);
	}

	static boolean clearCells(Set<Block> portalBlocks, Material expected)
	{
		return clearCells(portalBlocks, expected, VanillaPortalCleanup::dispatchRegion);
	}

	static boolean clearCells(Set<Block> portalBlocks, Material expected, RegionDispatch dispatch)
	{
		if(portalBlocks == null || portalBlocks.isEmpty())
		{
			return true;
		}
		Map<Long, List<Block>> byChunk = new HashMap<Long, List<Block>>();
		for(Block block : portalBlocks)
		{
			long key = (((long) block.getX() >> 4) << 32) ^ (((long) block.getZ() >> 4) & 0xffffffffL);
			byChunk.computeIfAbsent(Long.valueOf(key), ignored -> new ArrayList<Block>()).add(block);
		}
		World world = portalBlocks.iterator().next().getWorld();
		boolean cleared = true;
		for(List<Block> chunkBlocks : byChunk.values())
		{
			Block anchor = chunkBlocks.get(0);
			boolean scheduled = dispatch.runRegion(world, anchor.getX() >> 4, anchor.getZ() >> 4, () ->
			{
				for(Block block : chunkBlocks)
				{
					if(block.getType() == expected)
					{
						block.setType(Material.AIR, false);
					}
				}
			});
			if(!scheduled)
			{
				cleared = false;
				Wormholes.w("[vanilla-portal] region refused clearing " + chunkBlocks.size() + " " + expected + " cell(s) at "
						+ anchor.getX() + "," + anchor.getY() + "," + anchor.getZ() + " in " + world.getName()
						+ "; vanilla portal blocks left in place");
			}
		}
		return cleared;
	}

	static void destroyIfUnlinked(ILocalPortal portal)
	{
		if(portal != null && portal.getTunnel() == null && portal.getDimensionalCounterpartId() == null)
		{
			portal.destroy();
		}
	}

	private static boolean dispatchRegion(World world, int chunkX, int chunkZ, Runnable command)
	{
		return FoliaScheduler.runRegion(Wormholes.instance, world, chunkX, chunkZ, command);
	}
}
