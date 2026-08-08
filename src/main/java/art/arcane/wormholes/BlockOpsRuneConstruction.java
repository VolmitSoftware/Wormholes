package art.arcane.wormholes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.BlockOpsRuneIndex.RuneCoordinate;
import art.arcane.wormholes.BlockOpsRuneIndex.RuneReservation;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.portal.PortalBlock;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.PortalTypeAccess;
import art.arcane.wormholes.service.WormholesHud;
import art.arcane.wormholes.service.WormholesTelemetry;
import art.arcane.wormholes.util.M;

final class BlockOpsRuneConstruction
{
	static final String RUNE_CONSUME_FAILED = "RUNE_CONSUME_FAILED";
	static final String RUNE_CONSUME_SCHEDULE_REJECTED = "RUNE_CONSUME_SCHEDULE_REJECTED";
	static final String RUNE_CONSTRUCT_FAILED = "RUNE_CONSTRUCT_FAILED";
	static final String RUNE_CONSTRUCT_SCHEDULE_REJECTED = "RUNE_CONSTRUCT_SCHEDULE_REJECTED";
	static final String RUNE_ROLLBACK_FAILED = "RUNE_ROLLBACK_FAILED";
	static final String RUNE_ROLLBACK_SCHEDULE_REJECTED = "RUNE_ROLLBACK_SCHEDULE_REJECTED";
	static final String RUNE_ROLLBACK_NOTICE_FAILED = "RUNE_ROLLBACK_NOTICE_FAILED";
	static final String RUNE_ROLLBACK_NOTICE_SCHEDULE_REJECTED = "RUNE_ROLLBACK_NOTICE_SCHEDULE_REJECTED";
	static final String RUNE_REFUND_DROP_FAILED = "RUNE_REFUND_DROP_FAILED";
	static final String RUNE_REFUND_DROP_SCHEDULE_REJECTED = "RUNE_REFUND_DROP_SCHEDULE_REJECTED";
	static final String RUNE_REFUND_TICK_FAILED = "RUNE_REFUND_TICK_FAILED";
	static final String RUNE_REFUND_TICK_SCHEDULE_REJECTED = "RUNE_REFUND_TICK_SCHEDULE_REJECTED";

	private final BlockOpsRuneIndex index;
	private final BlockOpsRuneCatalog catalog;

	BlockOpsRuneConstruction(BlockOpsRuneIndex index, BlockOpsRuneCatalog catalog)
	{
		this.index = index;
		this.catalog = catalog;
	}

	void construct(Player player, Block clickedBlock)
	{
		PortalBlock clickedRune = index.getBlock(clickedBlock);
		if(clickedRune == null)
		{
			return;
		}
		if(!PortalTypeAccess.allows(player, clickedRune.getType()))
		{
			Wormholes.effectManager.playNotificationFail(Wormholes.text().legacy(WormholesMessages.COMMAND_NO_PERMISSION), clickedBlock.getLocation());
			return;
		}
		RuneReservation reservation = index.reserveConnectedRunes(clickedBlock);
		if(reservation == null)
		{
			return;
		}
		if(!reservation.coplanar())
		{
			Wormholes.effectManager.playNotificationFail(Wormholes.text().legacy(WormholesMessages.PORTAL_MUST_BE_FLAT), clickedBlock.getLocation());
			return;
		}
		Vector look = player.getLocation().getDirection();
		consumeConnectedRunes(player, player.getUniqueId(), clickedBlock.getWorld(), reservation.blocks(), reservation.type(), look);
	}

	private void consumeConnectedRunes(Player player, UUID ownerId, World world, Set<PortalBlock> connected, PortalType type, Vector look)
	{
		Map<ChunkCoordinate, List<PortalBlock>> byChunk = new HashMap<ChunkCoordinate, List<PortalBlock>>();
		for(PortalBlock portalBlock : connected)
		{
			Location location = portalBlock.getLocation();
			ChunkCoordinate chunk = new ChunkCoordinate(location.getBlockX() >> 4, location.getBlockZ() >> 4);
			byChunk.computeIfAbsent(chunk, ignored -> new ArrayList<PortalBlock>()).add(portalBlock);
		}
		List<EffectManager.PortalBlockSnapshot> snapshots = Collections.synchronizedList(new ArrayList<EffectManager.PortalBlockSnapshot>());
		Set<Block> consumed = ConcurrentHashMap.newKeySet();
		AtomicBoolean failed = new AtomicBoolean();
		AtomicInteger remaining = new AtomicInteger(byChunk.size());
		Material expectedMaterial = runeMaterial(type);
		for(Map.Entry<ChunkCoordinate, List<PortalBlock>> entry : byChunk.entrySet())
		{
			ChunkCoordinate chunk = entry.getKey();
			Supplier<String> context = () -> "Could not consume portal runes in " + world.getName() + " chunk " + chunk.x() + "," + chunk.z();
			boolean scheduled = BlockOpsGuardedDispatch.dispatchGuarded(
					task -> FoliaScheduler.runRegion(Wormholes.instance, world, chunk.x(), chunk.z(), task),
					RUNE_CONSUME_FAILED,
					RUNE_CONSUME_SCHEDULE_REJECTED,
					context,
					() ->
					{
						try
						{
							for(PortalBlock portalBlock : entry.getValue())
							{
								Location location = portalBlock.getLocation();
								Block block = world.getBlockAt(location.getBlockX(), location.getBlockY(), location.getBlockZ());
								if(block.getType() != expectedMaterial)
								{
									failed.set(true);
									continue;
								}
								EffectManager.PortalBlockSnapshot snapshot = new EffectManager.PortalBlockSnapshot(block.getLocation(), block.getBlockData());
								block.setType(Material.AIR, false);
								snapshots.add(snapshot);
								consumed.add(block);
							}
						}
						catch(Throwable error)
						{
							failed.set(true);
							WormholesTelemetry.countFailure(RUNE_CONSUME_FAILED);
							Wormholes.log().log(Level.WARNING, context.get(), error);
						}
						finally
						{
							if(remaining.decrementAndGet() == 0)
							{
								finishRuneConstruction(player, ownerId, world, snapshots, consumed, connected, type, look, failed.get());
							}
						}
					});
			if(!scheduled)
			{
				failed.set(true);
				if(remaining.decrementAndGet() == 0)
				{
					finishRuneConstruction(player, ownerId, world, snapshots, consumed, connected, type, look, true);
				}
			}
		}
	}

	private void finishRuneConstruction(Player player, UUID ownerId, World world, List<EffectManager.PortalBlockSnapshot> synchronizedSnapshots,
			Set<Block> consumed, Set<PortalBlock> reserved, PortalType type, Vector look, boolean failed)
	{
		List<EffectManager.PortalBlockSnapshot> snapshots;
		synchronized(synchronizedSnapshots)
		{
			snapshots = List.copyOf(synchronizedSnapshots);
		}
		if(failed || snapshots.size() != reserved.size() || consumed.size() != reserved.size())
		{
			rollbackRuneReservation(world, reserved, snapshots, type);
			notifyRuneRollback(player);
			return;
		}
		if(snapshots.isEmpty())
		{
			return;
		}
		double sumX = 0.0D;
		double sumY = 0.0D;
		double sumZ = 0.0D;
		for(EffectManager.PortalBlockSnapshot snapshot : snapshots)
		{
			sumX += snapshot.location().getX() + 0.5D;
			sumY += snapshot.location().getY() + 0.5D;
			sumZ += snapshot.location().getZ() + 0.5D;
		}
		Location center = new Location(world, sumX / snapshots.size(), sumY / snapshots.size(), sumZ / snapshots.size());
		boolean scheduled = BlockOpsGuardedDispatch.dispatchGuarded(
				task -> FoliaScheduler.runRegion(Wormholes.instance, center, task),
				RUNE_CONSTRUCT_FAILED,
				RUNE_CONSTRUCT_SCHEDULE_REJECTED,
				() -> "Could not finish portal construction in " + world.getName() + " at " + center,
				() ->
				{
					if(Wormholes.constructionManager.constructPortal(ownerId, consumed, type, look))
					{
						index.clearReservedCells(reserved);
						Wormholes.effectManager.playPortalVortex(world, center, snapshots);
						return;
					}
					rollbackRuneReservation(world, reserved, snapshots, type);
					notifyRuneRollback(player);
				});
		if(!scheduled)
		{
			rollbackRuneReservation(world, reserved, snapshots, type);
			notifyRuneRollback(player);
		}
	}

	private void notifyRuneRollback(Player player)
	{
		if(player != null)
		{
			boolean scheduled = BlockOpsGuardedDispatch.dispatchGuarded(
					task -> FoliaScheduler.runEntity(Wormholes.instance, player, task),
					RUNE_ROLLBACK_NOTICE_FAILED,
					RUNE_ROLLBACK_NOTICE_SCHEDULE_REJECTED,
					() -> "Could not tell " + player.getName() + " that portal formation was interrupted",
					() -> WormholesHud.notice(player, Wormholes.text().component(WormholesMessages.PORTAL_FORM_INTERRUPTED)));

			if(!scheduled)
			{
				Wormholes.w("Could not tell " + player.getName() + " that portal formation was interrupted; their runes were rolled back silently");
			}
		}
	}

	private void rollbackRuneReservation(World world, Set<PortalBlock> reserved, List<EffectManager.PortalBlockSnapshot> snapshots, PortalType type)
	{
		Map<RuneCoordinate, BlockData> originals = new HashMap<RuneCoordinate, BlockData>();
		for(EffectManager.PortalBlockSnapshot snapshot : snapshots)
		{
			originals.put(RuneCoordinate.from(snapshot.location()), snapshot.data());
		}
		Map<ChunkCoordinate, List<PortalBlock>> byChunk = new HashMap<ChunkCoordinate, List<PortalBlock>>();
		for(PortalBlock portalBlock : reserved)
		{
			Location location = portalBlock.getLocation();
			ChunkCoordinate chunk = new ChunkCoordinate(location.getBlockX() >> 4, location.getBlockZ() >> 4);
			byChunk.computeIfAbsent(chunk, ignored -> new ArrayList<PortalBlock>()).add(portalBlock);
		}
		Material expectedMaterial = runeMaterial(type);
		for(Map.Entry<ChunkCoordinate, List<PortalBlock>> entry : byChunk.entrySet())
		{
			ChunkCoordinate chunk = entry.getKey();
			Runnable rollback = () ->
			{
				for(PortalBlock portalBlock : entry.getValue())
				{
					Location location = portalBlock.getLocation();
					Block block = world.getBlockAt(location.getBlockX(), location.getBlockY(), location.getBlockZ());
					BlockData original = originals.get(RuneCoordinate.from(location));
					if(original != null && block.getType().isAir())
					{
						block.setBlockData(original, false);
					}
					if(block.getType() == expectedMaterial)
					{
						index.registerBlockSilently(portalBlock);
					}
					index.releaseReservedCell(location);
				}
			};
			if(!restoreRunesInChunk(
					task -> FoliaScheduler.runRegion(Wormholes.instance, world, chunk.x(), chunk.z(), task),
					() -> FoliaScheduler.isOwnedByCurrentRegion(world, chunk.x(), chunk.z()),
					() -> "Could not restore reserved portal runes in " + world.getName() + " chunk " + chunk.x() + "," + chunk.z(),
					rollback))
			{
				int unrestored = 0;
				for(PortalBlock portalBlock : entry.getValue())
				{
					Location location = portalBlock.getLocation();
					if(originals.containsKey(RuneCoordinate.from(location)))
					{
						unrestored++;
					}
					else
					{
						index.registerBlockSilently(portalBlock);
					}
					index.releaseReservedCell(location);
				}
				Wormholes.w("Could not restore reserved portal runes in " + world.getName() + " chunk " + chunk.x() + "," + chunk.z()
						+ " because region scheduling was rejected; re-indexed " + (entry.getValue().size() - unrestored)
						+ " untouched rune(s) and lost " + unrestored + " already-consumed rune(s)");
			}
		}
	}

	void refund(Set<Block> blocks, PortalType type)
	{
		if(blocks == null || blocks.isEmpty())
		{
			return;
		}

		KList<Block> refund = new KList<Block>(blocks);
		ItemStack is = catalog.get(type, 1);
		Location anchor = refund.get(0).getLocation();

		Supplier<String> tickContext = () -> "The portal rune refund sweep failed at " + anchor;
		Runnable[] tickHolder = new Runnable[1];
		tickHolder[0] = () ->
		{
			if(refund.isEmpty())
			{
				return;
			}

			if(M.r(Settings.PORTAL_COLAPSE_SPEED))
			{
				refundRune(refund.pop(), is);
			}

			if(!scheduleRefundTick(task -> FoliaScheduler.runRegion(Wormholes.instance, anchor, task, 1L), tickContext, tickHolder[0]))
			{
				drainRefundNow(refund, is);
			}
		};

		if(!scheduleRefundTick(task -> FoliaScheduler.runRegion(Wormholes.instance, anchor, task), tickContext, tickHolder[0]))
		{
			drainRefundNow(refund, is);
		}
	}

	private void refundRune(Block block, ItemStack is)
	{
		refundRune(
				task -> FoliaScheduler.runRegion(Wormholes.instance, block.getLocation(), task),
				() -> FoliaScheduler.isOwnedByCurrentRegion(block.getLocation()),
				() -> "Could not refund a portal rune at " + block.getLocation(),
				() -> dropRefund(block, is));
	}

	static boolean scheduleRefundTick(BlockOpsGuardedDispatch.RegionDispatch dispatch, Supplier<String> context, Runnable tick)
	{
		return BlockOpsGuardedDispatch.dispatchGuarded(dispatch, RUNE_REFUND_TICK_FAILED, RUNE_REFUND_TICK_SCHEDULE_REJECTED, context, tick);
	}

	static void refundRune(BlockOpsGuardedDispatch.RegionDispatch dispatch, BooleanSupplier ownsRegion, Supplier<String> context, Runnable drop)
	{
		if(BlockOpsGuardedDispatch.dispatchGuarded(dispatch, ownsRegion, RUNE_REFUND_DROP_FAILED, RUNE_REFUND_DROP_SCHEDULE_REJECTED, context, drop))
		{
			return;
		}

		Wormholes.w(context.get() + " because region scheduling was rejected");
	}

	static boolean restoreRunesInChunk(BlockOpsGuardedDispatch.RegionDispatch dispatch, BooleanSupplier ownsRegion, Supplier<String> context,
			Runnable rollback)
	{
		return BlockOpsGuardedDispatch.dispatchGuarded(dispatch, ownsRegion, RUNE_ROLLBACK_FAILED, RUNE_ROLLBACK_SCHEDULE_REJECTED, context, rollback);
	}

	private void dropRefund(Block block, ItemStack is)
	{
		block.getWorld().dropItemNaturally(block.getLocation().clone().add(0.5, 0.5, 0.5), is);
		EffectManager effects = Wormholes.effectManager;

		if(effects != null)
		{
			effects.playPortalFailRefund(block);
		}
	}

	private boolean dropRefundNow(Block block, ItemStack is)
	{
		if(!FoliaScheduler.isOwnedByCurrentRegion(block.getLocation()))
		{
			return false;
		}

		try
		{
			dropRefund(block, is);
		}

		catch(Throwable error)
		{
			WormholesTelemetry.countFailure(RUNE_REFUND_DROP_FAILED);
			Wormholes.log().log(Level.WARNING, "Could not refund a portal rune at " + block.getLocation(), error);
		}

		return true;
	}

	private void drainRefundNow(KList<Block> remaining, ItemStack is)
	{
		int lost = 0;

		while(!remaining.isEmpty())
		{
			if(!dropRefundNow(remaining.pop(), is))
			{
				WormholesTelemetry.countFailure(RUNE_REFUND_DROP_SCHEDULE_REJECTED);
				lost++;
			}
		}

		if(lost > 0)
		{
			Wormholes.w("Could not refund " + lost + " portal rune(s) because region scheduling was rejected");
		}
	}

	private static Material runeMaterial(PortalType type)
	{
		return switch(type)
		{
			case PORTAL -> Material.PRISMARINE;
			case WORMHOLE -> Material.DARK_PRISMARINE;
			case GATEWAY -> Material.BLACK_STAINED_GLASS;
			case RTP -> throw new IllegalArgumentException("RTP is not a constructible rune type");
		};
	}

	private record ChunkCoordinate(int x, int z)
	{
	}
}
