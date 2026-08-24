package art.arcane.wormholes;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import art.arcane.volmlib.util.bukkit.WorldIdentity;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.portal.PortalBlock;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.util.GChunk;
import art.arcane.wormholes.util.M;

final class BlockOpsRuneIndex
{
	private static final int MAX_ANIMATION_TASKS_PER_PASS = 64;
	private static final int[][] ADJACENT_OFFSETS = new int[][] {
			{ 1, 0, 0 }, { -1, 0, 0 }, { 0, 1, 0 }, { 0, -1, 0 }, { 0, 0, 1 }, { 0, 0, -1 }
	};
	private final Map<GChunk, Set<PortalBlock>> blocks = new ConcurrentHashMap<GChunk, Set<PortalBlock>>();
	private final Object runeMutationLock = new Object();
	private final Set<RuneCell> reservedRuneCells = ConcurrentHashMap.newKeySet();
	private final AtomicBoolean animationFailureReported = new AtomicBoolean(false);
	private final RuneAnimationBudget animationBudget = new RuneAnimationBudget(MAX_ANIMATION_TASKS_PER_PASS);

	void destroyAll()
	{
		Wormholes.v("Releasing tracked portal blocks (" + blocks.size() + " chunks)");
		blocks.clear();
		animationBudget.clear();
	}

	boolean tracksChunk(GChunk c)
	{
		return blocks.containsKey(c);
	}

	KList<PortalBlock> snapshotChunk(GChunk c)
	{
		Set<PortalBlock> tracked = blocks.get(c);
		return tracked == null ? new KList<PortalBlock>() : new KList<PortalBlock>(tracked);
	}

	boolean destroyAllInChunk(GChunk c)
	{
		Set<PortalBlock> tracked = blocks.remove(c);

		if(tracked == null)
		{
			return false;
		}

		Wormholes.v("Destroying " + tracked.size() + " portal blocks in chunk " + c.getX() + ", " + c.getZ());

		for(PortalBlock i : tracked)
		{
			i.getLocation().getBlock().setType(Material.AIR);
		}

		return true;
	}

	void updatePlacedBlocks()
	{
		updatePlacedBlocks(Bukkit::getOnlinePlayers);
	}

	void updatePlacedBlocks(Supplier<? extends Collection<? extends Player>> onlinePlayersSupplier)
	{
		if(blocks.isEmpty())
		{
			return;
		}
		Map<UUID, Player> onlinePlayers = new HashMap<UUID, Player>();
		List<UUID> onlinePlayerIds = new ArrayList<UUID>();
		for(Player player : onlinePlayersSupplier.get())
		{
			UUID playerId = player.getUniqueId();
			onlinePlayers.put(playerId, player);
			onlinePlayerIds.add(playerId);
		}
		for(RuneAnimationBudget.Admission admission : animationBudget.acquire(onlinePlayerIds))
		{
			Player player = onlinePlayers.get(admission.playerId());
			if(player == null)
			{
				animationBudget.reject(admission);
				continue;
			}
			Runnable retired = () -> animationBudget.reject(admission);
			boolean scheduled = FoliaScheduler.runEntity(Wormholes.instance, player, () ->
			{
				try
				{
					animatePlacedBlocksFor(player);
				}
				finally
				{
					animationBudget.complete(admission);
				}
			}, 0L, retired);
			if(!scheduled)
			{
				animationBudget.reject(admission);
				Wormholes.v("Skipped the portal rune animation sweep for " + admission.playerId() + "; the entity scheduler rejected it");
			}
		}
	}

	void removePlayer(UUID playerId)
	{
		animationBudget.remove(playerId);
	}

	private void animatePlacedBlocksFor(Player i)
	{
		try
		{
			Location at = i.getLocation();
			if(at == null || at.getWorld() == null)
			{
				return;
			}

			String worldKey = WorldIdentity.serialize(at.getWorld());
			int cx = at.getBlockX() >> 4;
			int cz = at.getBlockZ() >> 4;

			for(int dx = -1; dx <= 1; dx++)
			{
				for(int dz = -1; dz <= 1; dz++)
				{
					Set<PortalBlock> set = blocks.get(new GChunk(cx + dx, cz + dz, worldKey));
					if(set == null)
					{
						continue;
					}

					for(PortalBlock k : set)
					{
						if(M.r(0.35))
						{
							k.animate(i);
						}
					}
				}
			}
		}

		catch(Throwable e)
		{
			reportAnimationFailure(i, e);
		}
	}

	private void reportAnimationFailure(Player player, Throwable error)
	{
		if(animationFailureReported.compareAndSet(false, true))
		{
			Wormholes.log().log(Level.WARNING, "The portal rune animation sweep failed for " + player.getName()
					+ "; later failures are only logged at FINE.", error);
			return;
		}

		Wormholes.log().log(Level.FINE, "The portal rune animation sweep failed for " + player.getName(), error);
	}

	RuneReservation reserveConnectedRunes(Block clickedBlock)
	{
		synchronized(runeMutationLock)
		{
			String worldKey = WorldIdentity.serialize(clickedBlock.getWorld());
			PortalBlock init = findTrackedBlock(worldKey, clickedBlock.getX(), clickedBlock.getY(), clickedBlock.getZ());
			if(init == null)
			{
				return null;
			}
			PortalType type = init.getType();
			Set<PortalBlock> connected = connectedRunes(worldKey, RuneCoordinate.from(init.getLocation()), type);
			if(connected.isEmpty())
			{
				return null;
			}
			if(!isCoplanar(connected))
			{
				return new RuneReservation(type, Set.copyOf(connected), false);
			}
			for(PortalBlock portalBlock : connected)
			{
				Set<PortalBlock> tracked = blocks.get(chunkKey(portalBlock.getLocation()));
				if(tracked == null || !tracked.contains(portalBlock))
				{
					return null;
				}
			}
			for(PortalBlock portalBlock : connected)
			{
				unregisterBlockLocked(portalBlock);
				reservedRuneCells.add(RuneCell.from(portalBlock.getLocation()));
			}
			return new RuneReservation(type, Set.copyOf(connected), true);
		}
	}

	private Set<PortalBlock> connectedRunes(String worldKey, RuneCoordinate start, PortalType type)
	{
		Set<PortalBlock> connected = new HashSet<PortalBlock>();
		Set<RuneCoordinate> visited = new HashSet<RuneCoordinate>();
		ArrayDeque<RuneCoordinate> search = new ArrayDeque<RuneCoordinate>();
		search.add(start);
		while(!search.isEmpty())
		{
			RuneCoordinate coordinate = search.removeFirst();
			if(!visited.add(coordinate))
			{
				continue;
			}
			PortalBlock portalBlock = findTrackedBlock(worldKey, coordinate.x(), coordinate.y(), coordinate.z());
			if(portalBlock == null || portalBlock.getType() != type)
			{
				continue;
			}
			connected.add(portalBlock);
			for(int[] offset : ADJACENT_OFFSETS)
			{
				search.addLast(new RuneCoordinate(coordinate.x() + offset[0], coordinate.y() + offset[1], coordinate.z() + offset[2]));
			}
		}
		return connected;
	}

	private static boolean isCoplanar(Set<PortalBlock> connected)
	{
		int minX = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int minY = Integer.MAX_VALUE;
		int maxY = Integer.MIN_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxZ = Integer.MIN_VALUE;
		for(PortalBlock portalBlock : connected)
		{
			Location location = portalBlock.getLocation();
			minX = Math.min(minX, location.getBlockX());
			maxX = Math.max(maxX, location.getBlockX());
			minY = Math.min(minY, location.getBlockY());
			maxY = Math.max(maxY, location.getBlockY());
			minZ = Math.min(minZ, location.getBlockZ());
			maxZ = Math.max(maxZ, location.getBlockZ());
		}
		return ConstructionManager.isCoplanarPortalArea(maxX - minX, maxY - minY, maxZ - minZ);
	}

	boolean isReservedRuneCell(Block block)
	{
		return reservedRuneCells.contains(RuneCell.from(block));
	}

	void releaseReservedCell(Location location)
	{
		reservedRuneCells.remove(RuneCell.from(location));
	}

	void clearReservedCells(Set<PortalBlock> reserved)
	{
		for(PortalBlock portalBlock : reserved)
		{
			reservedRuneCells.remove(RuneCell.from(portalBlock.getLocation()));
		}
	}

	PortalBlock getBlock(Block block)
	{
		synchronized(runeMutationLock)
		{
			return findTrackedBlock(WorldIdentity.serialize(block.getWorld()), block.getX(), block.getY(), block.getZ());
		}
	}

	boolean unregisterBlock(PortalBlock block)
	{
		synchronized(runeMutationLock)
		{
			return unregisterBlockLocked(block);
		}
	}

	private boolean unregisterBlockLocked(PortalBlock block)
	{
		GChunk chunk = chunkKey(block.getLocation());
		Set<PortalBlock> tracked = blocks.get(chunk);
		if(tracked == null || !tracked.remove(block))
		{
			return false;
		}
		if(tracked.isEmpty())
		{
			blocks.remove(chunk, tracked);
		}
		return true;
	}

	void registerBlockSilently(PortalBlock block)
	{
		synchronized(runeMutationLock)
		{
			GChunk chunk = chunkKey(block.getLocation());
			blocks.computeIfAbsent(chunk, ignored -> ConcurrentHashMap.newKeySet()).add(block);
		}
	}

	private PortalBlock findTrackedBlock(String worldKey, int x, int y, int z)
	{
		Set<PortalBlock> tracked = blocks.get(new GChunk(x >> 4, z >> 4, worldKey));
		if(tracked == null)
		{
			return null;
		}
		for(PortalBlock portalBlock : tracked)
		{
			Location location = portalBlock.getLocation();
			if(location.getBlockX() == x && location.getBlockY() == y && location.getBlockZ() == z)
			{
				return portalBlock;
			}
		}
		return null;
	}

	private static GChunk chunkKey(Location location)
	{
		return new GChunk(location.getBlockX() >> 4, location.getBlockZ() >> 4, WorldIdentity.serialize(location.getWorld()));
	}

	record RuneReservation(PortalType type, Set<PortalBlock> blocks, boolean coplanar)
	{
	}

	record RuneCoordinate(int x, int y, int z)
	{
		static RuneCoordinate from(Location location)
		{
			return new RuneCoordinate(location.getBlockX(), location.getBlockY(), location.getBlockZ());
		}
	}

	private record RuneCell(String worldKey, int x, int y, int z)
	{
		private static RuneCell from(Block block)
		{
			return new RuneCell(WorldIdentity.serialize(block.getWorld()), block.getX(), block.getY(), block.getZ());
		}

		private static RuneCell from(Location location)
		{
			return new RuneCell(WorldIdentity.serialize(location.getWorld()), location.getBlockX(), location.getBlockY(), location.getBlockZ());
		}
	}
}
