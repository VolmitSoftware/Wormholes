package art.arcane.wormholes.portal.vanilla;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

import org.bukkit.Axis;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Orientable;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.platform.WormholesPlatform;
import art.arcane.wormholes.portal.DimensionalPortalKind;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.util.Direction;

final class VanillaPortalNetherPairing
{
	private final VanillaPortalIndex index;
	private final VanillaPortalNetherSites sites;

	VanillaPortalNetherPairing(VanillaPortalIndex index, VanillaPortalNetherSites sites)
	{
		this.index = index;
		this.sites = sites;
	}

	void pair(World sourceWorld, Set<Block> cells)
	{
		try
		{
			if(index.coversCells(cells))
			{
				Wormholes.w("[vanilla-portal] skipped: cells already covered by an existing Wormholes portal");
				return;
			}
			Direction normal = deriveNormal(cells);
			boolean alongX = normal.z() != 0;
			int interiorWidth = interiorWidth(cells, alongX);
			int interiorHeight = interiorHeight(cells);
			WorldPairing.NetherPortalTarget targetPlan = WorldPairing.pairedNetherPortalTarget(sourceWorld);
			if(targetPlan == null)
			{
				Wormholes.w("[vanilla-portal] No paired " + (sourceWorld.getEnvironment() == World.Environment.NETHER ? "overworld" : "nether")
						+ " world for " + sourceWorld.getName() + "; leaving vanilla portal unchanged.");
				return;
			}
			World target = targetPlan.world();
			ILocalPortal sourcePortal = PortalFactory.createFromCells(cells, PortalFrame.canonical(normal), PortalType.PORTAL, VanillaPortalIndex.NETHER_TAG, DimensionalPortalKind.NETHER);
			if(sourcePortal == null)
			{
				Wormholes.w("[vanilla-portal] source portal creation returned null");
				return;
			}
			Location center = sourcePortal.getCenter();
			int tcx = WorldPairing.scaleHorizontal(sourceWorld, target, center.getBlockX());
			int tcz = WorldPairing.scaleHorizontal(sourceWorld, target, center.getBlockZ());
			int tcy = clampY(target, center.getBlockY());
			int reuseRadius = target.getEnvironment() == World.Environment.NETHER ? 16 : 128;
			Wormholes.w("[vanilla-portal] source built (" + interiorWidth + "x" + interiorHeight + "); target=" + target.getName() + " @ " + tcx + "," + tcy + "," + tcz);

			if(!targetPlan.sharedFallback())
			{
				ILocalPortal existing = index.findLinkable(target, VanillaPortalIndex.NETHER_TAG, DimensionalPortalKind.NETHER, tcx, tcy, tcz, reuseRadius, true);
				if(existing != null && PortalFactory.linkBidirectional(sourcePortal, existing))
				{
					VanillaPortalCleanup.clearCells(cells, Material.NETHER_PORTAL);
					Wormholes.w("[vanilla-portal] reused existing counterpart, linked both ways");
					return;
				}
			}
			else
			{
				buildSharedFallbackCounterpart(target, sourcePortal, cells, normal, alongX, interiorWidth, interiorHeight,
						tcx, tcy, tcz, reuseRadius);
				return;
			}
			findPhysicalPortalAsync(target, tcx, tcy, tcz, reuseRadius).whenComplete((physicalPortal, lookupError) ->
			{
				if(sourcePortal.isDestroyed())
				{
					return;
				}
				if(lookupError != null)
				{
					Wormholes.instance.getLogger().log(Level.WARNING, "[vanilla-portal] nearby physical portal lookup failed", lookupError);
				}
				Set<Block> reusable = physicalPortal == null ? Set.of() : physicalPortal;
				if(!reusable.isEmpty() && reusePhysicalPortal(sourcePortal, cells, reusable))
				{
					return;
				}
				buildGeneratedCounterpart(target, sourcePortal, cells, normal, alongX, interiorWidth, interiorHeight,
						tcx, tcy, tcz, Set.of());
			});
		}
		catch(Throwable ex)
		{
			Wormholes.instance.getLogger().log(Level.WARNING, "[vanilla-portal] nether pair build failed", ex);
		}
	}

	private void buildSharedFallbackCounterpart(World target, ILocalPortal sourcePortal, Set<Block> sourceCells, Direction normal,
			boolean alongX, int interiorWidth, int interiorHeight, int targetX, int targetY, int targetZ, int searchRadius)
	{
		findPhysicalPortalAsync(target, targetX, targetY, targetZ, searchRadius).whenComplete((physicalPortal, lookupError) ->
		{
			if(sourcePortal.isDestroyed())
			{
				return;
			}
			if(lookupError != null)
			{
				Wormholes.instance.getLogger().log(Level.WARNING, "[vanilla-portal] shared Nether physical portal lookup failed", lookupError);
			}
			Set<Block> forbidden = physicalPortal == null ? Set.of() : physicalPortal;
			buildGeneratedCounterpart(target, sourcePortal, sourceCells, normal, alongX, interiorWidth, interiorHeight,
					targetX, targetY, targetZ, forbidden);
		});
	}

	private boolean reusePhysicalPortal(ILocalPortal sourcePortal, Set<Block> sourceCells, Set<Block> physicalPortal)
	{
		ILocalPortal counterpart = PortalFactory.createFromCells(physicalPortal, PortalFrame.canonical(deriveNormal(physicalPortal)), PortalType.PORTAL,
				VanillaPortalIndex.NETHER_TAG, DimensionalPortalKind.NETHER);
		if(counterpart != null && PortalFactory.linkBidirectional(sourcePortal, counterpart))
		{
			VanillaPortalCleanup.clearCells(physicalPortal, Material.NETHER_PORTAL);
			VanillaPortalCleanup.clearCells(sourceCells, Material.NETHER_PORTAL);
			Wormholes.w("[vanilla-portal] reused physical vanilla counterpart, linked both ways");
			return true;
		}
		VanillaPortalCleanup.destroyIfUnlinked(counterpart);
		return false;
	}

	private void buildGeneratedCounterpart(World target, ILocalPortal sourcePortal, Set<Block> sourceCells, Direction normal,
			boolean alongX, int interiorWidth, int interiorHeight, int targetX, int targetY, int targetZ, Set<Block> forbiddenPhysicalPortal)
	{
		VanillaPortalNetherSites.BuildTarget buildTarget = sites.reserve(target, targetX, targetZ, interiorWidth, interiorHeight, forbiddenPhysicalPortal);
		if(buildTarget == null)
		{
			VanillaPortalCleanup.destroyIfUnlinked(sourcePortal);
			return;
		}
		PortalSiteBuilder.buildNetherFrameAsync(target, buildTarget.x(), targetY, buildTarget.z(), alongX, interiorWidth, interiorHeight).whenComplete((built, buildError) ->
		{
			if(buildError != null || built == null || built.isEmpty())
			{
				sites.release(buildTarget);
				VanillaPortalCleanup.destroyIfUnlinked(sourcePortal);
				Wormholes.w("[vanilla-portal] counterpart frame build failed: " + buildError);
				return;
			}
			boolean registrationScheduled = FoliaScheduler.runRegion(Wormholes.instance, target, buildTarget.x() >> 4, buildTarget.z() >> 4, () ->
			{
				try
				{
					ILocalPortal counterpart = PortalFactory.createFromCells(built, PortalFrame.canonical(normal), PortalType.PORTAL, VanillaPortalIndex.NETHER_TAG, DimensionalPortalKind.NETHER);
					if(counterpart == null)
					{
						VanillaPortalCleanup.destroyIfUnlinked(sourcePortal);
						return;
					}
					if(PortalFactory.linkBidirectional(sourcePortal, counterpart))
					{
						VanillaPortalCleanup.clearCells(sourceCells, Material.NETHER_PORTAL);
						Wormholes.w("[vanilla-portal] counterpart frame built + linked both ways at " + buildTarget.x() + "," + buildTarget.z());
					}
					else
					{
						VanillaPortalCleanup.destroyIfUnlinked(counterpart);
						VanillaPortalCleanup.destroyIfUnlinked(sourcePortal);
					}
				}
				catch(Throwable ex)
				{
					VanillaPortalCleanup.destroyIfUnlinked(sourcePortal);
					Wormholes.instance.getLogger().log(Level.WARNING, "[vanilla-portal] counterpart build failed", ex);
				}
				finally
				{
					sites.release(buildTarget);
				}
			});
			if(!registrationScheduled)
			{
				sites.release(buildTarget);
				VanillaPortalCleanup.destroyIfUnlinked(sourcePortal);
			}
		});
	}

	private CompletableFuture<Set<Block>> findPhysicalPortalAsync(World world, int x, int y, int z, int radius)
	{
		if(!FoliaScheduler.isFoliaThreading(Bukkit.getServer()))
		{
			return CompletableFuture.completedFuture(findPhysicalPortal(world, x, y, z, radius));
		}
		CompletableFuture<Set<Block>> result = new CompletableFuture<Set<Block>>();
		WormholesPlatform.loadChunk(Wormholes.instance, world, x >> 4, z >> 4).whenComplete((chunk, loadError) ->
		{
			if(loadError != null || chunk == null)
			{
				result.completeExceptionally(loadError == null ? new IllegalStateException("Physical portal search chunk did not load") : loadError);
				return;
			}
			boolean scheduled = FoliaScheduler.runRegion(Wormholes.instance, world, x >> 4, z >> 4, () ->
			{
				try
				{
					int ownedRadius = largestOwnedPoiRadius(world, x, z, radius);
					result.complete(ownedRadius <= 0 ? Set.of() : findPhysicalPortal(world, x, y, z, ownedRadius));
				}
				catch(Throwable error)
				{
					result.completeExceptionally(error);
				}
			});
			if(!scheduled)
			{
				result.completeExceptionally(new IllegalStateException("Physical portal search region rejected lookup"));
			}
		});
		return result;
	}

	private static int largestOwnedPoiRadius(World world, int x, int z, int requestedRadius)
	{
		int radius = Math.max(1, requestedRadius);
		while(radius >= 1)
		{
			int minChunkX = (x - radius) >> 4;
			int minChunkZ = (z - radius) >> 4;
			int maxChunkX = (x + radius) >> 4;
			int maxChunkZ = (z + radius) >> 4;
			if(WormholesPlatform.isOwnedByCurrentRegion(world, minChunkX, minChunkZ, maxChunkX, maxChunkZ))
			{
				return radius;
			}
			if(radius == 1)
			{
				break;
			}
			radius = Math.max(1, radius / 2);
		}
		return 0;
	}

	private Set<Block> findPhysicalPortal(World world, int x, int y, int z, int radius)
	{
		Location nearest;
		try
		{
			nearest = PortalPoiLocator.locateNearestNetherPortal(world, new Location(world, x, y, z), radius);
		}
		catch(RuntimeException e)
		{
			Wormholes.instance.getLogger().log(Level.WARNING, "[vanilla-portal] nearby physical portal lookup failed", e);
			return Set.of();
		}
		if(nearest == null)
		{
			return Set.of();
		}
		Block anchor = nearest.getBlock();
		if(anchor.getType() != Material.NETHER_PORTAL || !(anchor.getBlockData() instanceof Orientable orientable))
		{
			return Set.of();
		}
		boolean alongX = orientable.getAxis() == Axis.X;
		int[][] offsets = alongX
				? new int[][] { { 1, 0, 0 }, { -1, 0, 0 }, { 0, 1, 0 }, { 0, -1, 0 } }
				: new int[][] { { 0, 0, 1 }, { 0, 0, -1 }, { 0, 1, 0 }, { 0, -1, 0 } };
		ArrayDeque<Block> search = new ArrayDeque<Block>();
		Set<Long> visited = new HashSet<Long>();
		Set<Block> cells = new HashSet<Block>();
		search.add(anchor);
		while(!search.isEmpty() && cells.size() < 512)
		{
			Block block = search.removeFirst();
			long key = packBlock(block.getX(), block.getY(), block.getZ());
			if(!visited.add(Long.valueOf(key)) || Math.abs(block.getX() - x) > radius || Math.abs(block.getZ() - z) > radius)
			{
				continue;
			}
			if(block.getType() != Material.NETHER_PORTAL || !(block.getBlockData() instanceof Orientable blockOrientable)
					|| (blockOrientable.getAxis() == Axis.X) != alongX)
			{
				continue;
			}
			cells.add(block);
			for(int[] offset : offsets)
			{
				search.addLast(block.getRelative(offset[0], offset[1], offset[2]));
			}
		}
		if(cells.isEmpty() || index.coversCells(cells))
		{
			return Set.of();
		}
		return Set.copyOf(cells);
	}

	private static long packBlock(int x, int y, int z)
	{
		return ((long) (x & 0x3ffffff) << 38) | ((long) (z & 0x3ffffff) << 12) | (y & 0xfff);
	}

	private static Direction deriveNormal(Set<Block> cells)
	{
		int minX = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxZ = Integer.MIN_VALUE;
		for(Block cell : cells)
		{
			minX = Math.min(minX, cell.getX());
			maxX = Math.max(maxX, cell.getX());
			minZ = Math.min(minZ, cell.getZ());
			maxZ = Math.max(maxZ, cell.getZ());
		}
		boolean flatX = minX == maxX;
		return flatX ? Direction.E : Direction.N;
	}

	private static int interiorWidth(Set<Block> cells, boolean alongX)
	{
		int min = Integer.MAX_VALUE;
		int max = Integer.MIN_VALUE;
		for(Block cell : cells)
		{
			int value = alongX ? cell.getX() : cell.getZ();
			min = Math.min(min, value);
			max = Math.max(max, value);
		}
		return max - min + 1;
	}

	private static int interiorHeight(Set<Block> cells)
	{
		int min = Integer.MAX_VALUE;
		int max = Integer.MIN_VALUE;
		for(Block cell : cells)
		{
			min = Math.min(min, cell.getY());
			max = Math.max(max, cell.getY());
		}
		return max - min + 1;
	}

	private static int clampY(World world, int desired)
	{
		int min = world.getMinHeight() + 5;
		int max = world.getEnvironment() == World.Environment.NETHER ? 118 : world.getMaxHeight() - 6;
		return Math.max(min, Math.min(max, desired));
	}
}
