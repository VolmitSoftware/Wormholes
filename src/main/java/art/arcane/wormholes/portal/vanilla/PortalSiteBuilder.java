package art.arcane.wormholes.portal.vanilla;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.platform.WormholesPlatform;

public final class PortalSiteBuilder
{
	private static final int MIN_INTERIOR_HEIGHT = 2;
	private static final int MAX_INTERIOR = 21;

	private PortalSiteBuilder()
	{
	}

	public static CompletableFuture<Set<Block>> buildNetherFrameAsync(World world, int centerX, int startY, int centerZ, boolean alongX, int interiorWidth, int interiorHeight)
	{
		CompletableFuture<Set<Block>> result = new CompletableFuture<Set<Block>>();
		int width = netherInteriorWidth(interiorWidth);
		int height = Math.max(MIN_INTERIOR_HEIGHT, Math.min(MAX_INTERIOR, interiorHeight));
		loadNetherFootprint(world, centerX, centerZ, alongX, width, height).whenComplete((ignored, loadError) ->
		{
			if(loadError != null)
			{
				result.completeExceptionally(loadError);
				return;
			}
			boolean scheduled = FoliaScheduler.runRegion(Wormholes.instance, world, centerX >> 4, centerZ >> 4, () ->
			{
				try
				{
					int baseY = findSafeY(world, centerX, startY, centerZ, alongX, width, height);
					scheduleNetherMutations(world, centerX, baseY, centerZ, alongX, width, height, result);
				}
				catch(Throwable error)
				{
					result.completeExceptionally(error);
				}
			});
			if(!scheduled)
			{
				result.completeExceptionally(new IllegalStateException("Nether target region rejected frame planning"));
			}
		});
		return result;
	}

	private static CompletableFuture<Void> loadNetherFootprint(World world, int centerX, int centerZ,
			boolean alongX, int width, int height)
	{
		int padding = netherPlatformPadding(width, height);
		int startX = centerX - (alongX ? width / 2 : 0);
		int startZ = centerZ - (alongX ? 0 : width / 2);
		int minX = alongX ? startX - 1 - padding : centerX - padding;
		int maxX = alongX ? startX + width + padding : centerX + padding;
		int minZ = alongX ? centerZ - padding : startZ - 1 - padding;
		int maxZ = alongX ? centerZ + padding : startZ + width + padding;
		List<CompletableFuture<?>> loads = new ArrayList<>();
		for(int x = minX >> 4; x <= maxX >> 4; x++)
		{
			for(int z = minZ >> 4; z <= maxZ >> 4; z++)
			{
				loads.add(WormholesPlatform.loadChunk(Wormholes.instance, world, x, z).thenAccept(chunk ->
				{
					if(chunk == null)
					{
						throw new IllegalStateException("Nether target chunk did not load");
					}
				}));
			}
		}
		return CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new));
	}

	private static void scheduleNetherMutations(World world, int centerX, int baseY, int centerZ, boolean alongX, int width, int height,
			CompletableFuture<Set<Block>> result)
	{
		if(!netherFrameFits(baseY, height, world.getMinHeight(), world.getMaxHeight()))
		{
			result.completeExceptionally(new IllegalStateException("Nether frame would exceed safe world height"));
			return;
		}
		Map<Long, ChunkMutationPlan> plans = new HashMap<Long, ChunkMutationPlan>();
		for(NetherMutation mutation : planNetherMutations(centerX, baseY, centerZ, alongX, width, height))
		{
			addMutation(plans, mutation);
		}
		applyNetherMutationPlans(world, plans, result);
	}

	static List<NetherMutation> planNetherMutations(int centerX, int baseY, int centerZ, boolean alongX, int interiorWidth, int interiorHeight)
	{
        List<NetherSitePlan.Mutation> planned = NetherSitePlan.plan(new NetherSitePlan.Options(centerX, baseY, centerZ, alongX, interiorWidth, interiorHeight));
        List<NetherMutation> mutations = new ArrayList<>(planned.size());
        for (NetherSitePlan.Mutation mutation : planned) {
            mutations.add(new NetherMutation(mutation.x(), mutation.y(), mutation.z(), Material.valueOf(mutation.material().name()),
                mutation.preserveObsidian(), mutation.interior()));
        }
        return List.copyOf(mutations);
    }

	static boolean isNetherFrameEdge(int u, int v, int width, int height)
	{
        return NetherSitePlan.isNetherFrameEdge(u, v, width, height);
    }

	static int netherInteriorWidth(int requestedWidth)
	{
        return NetherSitePlan.netherInteriorWidth(requestedWidth);
    }

	static int netherPlatformPadding(int interiorWidth, int interiorHeight)
	{
        return NetherSitePlan.netherPlatformPadding(interiorWidth, interiorHeight);
    }

	static boolean netherFrameFits(int baseY, int interiorHeight, int minHeight, int maxHeight)
	{
        return NetherSitePlan.netherFrameFits(baseY, interiorHeight, minHeight, maxHeight);
    }

	private static void addMutation(Map<Long, ChunkMutationPlan> plans, NetherMutation mutation)
	{
		int chunkX = mutation.x() >> 4;
		int chunkZ = mutation.z() >> 4;
		long key = (((long) chunkX) << 32) ^ (chunkZ & 0xffffffffL);
		ChunkMutationPlan plan = plans.computeIfAbsent(Long.valueOf(key), ignored -> new ChunkMutationPlan(chunkX, chunkZ, new ArrayList<NetherMutation>()));
		plan.mutations().add(mutation);
	}

	private static void applyNetherMutationPlans(World world, Map<Long, ChunkMutationPlan> plans, CompletableFuture<Set<Block>> result)
	{
		if(plans.isEmpty())
		{
			result.complete(Set.of());
			return;
		}
		List<CompletableFuture<?>> chunkLoads = new ArrayList<CompletableFuture<?>>(plans.size());
		for(ChunkMutationPlan plan : plans.values())
		{
			chunkLoads.add(WormholesPlatform.loadChunk(Wormholes.instance, world, plan.chunkX(), plan.chunkZ()).thenAccept(chunk ->
			{
				if(chunk == null)
				{
					throw new IllegalStateException("Nether frame chunk did not load");
				}
			}));
		}
		CompletableFuture.allOf(chunkLoads.toArray(CompletableFuture[]::new)).whenComplete((ignored, loadError) ->
		{
			if(loadError != null)
			{
				result.completeExceptionally(loadError);
				return;
			}
			applyLoadedNetherMutationPlans(world, plans, result);
		});
	}

	private static void applyLoadedNetherMutationPlans(World world, Map<Long, ChunkMutationPlan> plans, CompletableFuture<Set<Block>> result)
	{
		Set<Block> interior = ConcurrentHashMap.newKeySet();
		Map<BlockPosition, BlockRollback> originals = new ConcurrentHashMap<BlockPosition, BlockRollback>();
		AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
		AtomicInteger remaining = new AtomicInteger(plans.size());
		for(ChunkMutationPlan plan : plans.values())
		{
			boolean scheduled = FoliaScheduler.runRegion(Wormholes.instance, world, plan.chunkX(), plan.chunkZ(), () ->
			{
				try
				{
					if(failure.get() == null)
					{
						for(NetherMutation mutation : plan.mutations())
						{
							Block block = world.getBlockAt(mutation.x(), mutation.y(), mutation.z());
							if(!mutation.preserveObsidian() || block.getType() != Material.OBSIDIAN)
							{
								BlockPosition position = new BlockPosition(mutation.x(), mutation.y(), mutation.z());
								originals.putIfAbsent(position, new BlockRollback(block.getBlockData(), mutation.material()));
								block.setType(mutation.material(), false);
							}
							if(mutation.interior())
							{
								interior.add(block);
							}
						}
					}
				}
				catch(Throwable error)
				{
					failure.compareAndSet(null, error);
				}
				finally
				{
					finishNetherMutationPlan(world, result, interior, originals, failure, remaining);
				}
			});
			if(!scheduled)
			{
				failure.compareAndSet(null, new IllegalStateException("Nether frame region rejected mutations for " + plan.chunkX() + "," + plan.chunkZ()));
				finishNetherMutationPlan(world, result, interior, originals, failure, remaining);
			}
		}
	}

	private static void finishNetherMutationPlan(World world, CompletableFuture<Set<Block>> result, Set<Block> interior,
			Map<BlockPosition, BlockRollback> originals, AtomicReference<Throwable> failure, AtomicInteger remaining)
	{
		if(remaining.decrementAndGet() != 0)
		{
			return;
		}
		Throwable error = failure.get();
		if(error == null)
		{
			result.complete(Set.copyOf(interior));
			return;
		}
		rollbackNetherMutations(world, originals, error, result);
	}

	private static void rollbackNetherMutations(World world, Map<BlockPosition, BlockRollback> originals, Throwable error,
			CompletableFuture<Set<Block>> result)
	{
		if(originals.isEmpty())
		{
			result.completeExceptionally(error);
			return;
		}
		Map<Long, List<Map.Entry<BlockPosition, BlockRollback>>> byChunk = new HashMap<Long, List<Map.Entry<BlockPosition, BlockRollback>>>();
		for(Map.Entry<BlockPosition, BlockRollback> entry : originals.entrySet())
		{
			BlockPosition position = entry.getKey();
			int chunkX = position.x() >> 4;
			int chunkZ = position.z() >> 4;
			long key = (((long) chunkX) << 32) ^ (chunkZ & 0xffffffffL);
			byChunk.computeIfAbsent(Long.valueOf(key), ignored -> new ArrayList<Map.Entry<BlockPosition, BlockRollback>>()).add(entry);
		}
		AtomicInteger remaining = new AtomicInteger(byChunk.size());
		for(List<Map.Entry<BlockPosition, BlockRollback>> entries : byChunk.values())
		{
			BlockPosition anchor = entries.get(0).getKey();
			boolean scheduled = FoliaScheduler.runRegion(Wormholes.instance, world, anchor.x() >> 4, anchor.z() >> 4, () ->
			{
				try
				{
					for(Map.Entry<BlockPosition, BlockRollback> entry : entries)
					{
						BlockPosition position = entry.getKey();
						BlockRollback rollback = entry.getValue();
						Block block = world.getBlockAt(position.x(), position.y(), position.z());
						if(block.getType() == rollback.appliedMaterial())
						{
							block.setBlockData(rollback.originalData(), false);
						}
					}
				}
				finally
				{
					if(remaining.decrementAndGet() == 0)
					{
						result.completeExceptionally(error);
					}
				}
			});
			if(!scheduled && remaining.decrementAndGet() == 0)
			{
				result.completeExceptionally(error);
			}
		}
	}

	record NetherMutation(int x, int y, int z, Material material, boolean preserveObsidian, boolean interior)
	{
	}

	private record ChunkMutationPlan(int chunkX, int chunkZ, List<NetherMutation> mutations)
	{
	}

	private record BlockPosition(int x, int y, int z)
	{
	}

	private record BlockRollback(BlockData originalData, Material appliedMaterial)
	{
	}

	public static Set<Block> buildHorizontalWindow(World world, int centerX, int y, int centerZ, int half)
	{
		Set<Block> cells = horizontalWindowCells(world, centerX, y, centerZ, half);
		for(Block cell : cells)
		{
			cell.setType(Material.AIR, false);
		}
		return cells;
	}

	public static Set<Block> horizontalWindowCells(World world, int centerX, int y, int centerZ, int half)
	{
		Set<Block> cells = new HashSet<Block>();
		for(int dx = -half; dx <= half; dx++)
		{
			for(int dz = -half; dz <= half; dz++)
			{
				Block cell = world.getBlockAt(centerX + dx, y, centerZ + dz);
				cells.add(cell);
			}
		}
		return cells;
	}

	private static int findSafeY(World world, int x, int startY, int z, boolean alongX, int width, int height)
	{
		int min = world.getMinHeight() + 5;
		int max = NetherSiteSearch.maximumBaseY(world.getMaxHeight(), height, world.getEnvironment() == World.Environment.NETHER);
		int preferred = Math.clamp(startY, min, max);
		return NetherSiteSearch.findBaseY(new NetherSiteSearch.Options(x, z, alongX, width, height, preferred, min, max),
			(blockX, blockY, blockZ) -> siteCell(world, blockX, blockY, blockZ)).orElse(preferred);
	}

	private static NetherSiteSearch.Cell siteCell(World world, int x, int y, int z)
	{
		if(!world.isChunkLoaded(x >> 4, z >> 4)
			|| !WormholesPlatform.isOwnedByCurrentRegion(world, x >> 4, z >> 4, x >> 4, z >> 4))
		{
			return NetherSiteSearch.Cell.BLOCKED;
		}
		Block block = world.getBlockAt(x, y, z);
		Material material = block.getType();
		if(block.isLiquid() || switch(material)
		{
			case LAVA, WATER, FIRE, SOUL_FIRE, MAGMA_BLOCK, CAMPFIRE, SOUL_CAMPFIRE, CACTUS,
				POWDER_SNOW, SWEET_BERRY_BUSH, WITHER_ROSE, BEDROCK -> true;
			default -> false;
		})
		{
			return NetherSiteSearch.Cell.BLOCKED;
		}
		return material.isSolid() ? NetherSiteSearch.Cell.FLOOR
			: block.isPassable() ? NetherSiteSearch.Cell.CLEAR : NetherSiteSearch.Cell.BLOCKED;
	}
}
