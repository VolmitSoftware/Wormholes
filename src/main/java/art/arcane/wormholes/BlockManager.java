package art.arcane.wormholes;

import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.ItemStack;

import art.arcane.volmlib.util.localization.MessageArgument;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.localization.WormholesLocalization;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.portal.PortalBlock;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.PortalTypeAccess;
import art.arcane.wormholes.service.WormholesHud;
import art.arcane.wormholes.util.GChunk;
import art.arcane.wormholes.util.J;

public class BlockManager implements Listener
{
	static final String RUNE_BREAK_DROP_FAILED = "RUNE_BREAK_DROP_FAILED";
	static final String RUNE_BREAK_DROP_SCHEDULE_REJECTED = "RUNE_BREAK_DROP_SCHEDULE_REJECTED";

	private final BlockOpsRuneCatalog catalog;
	private final BlockOpsRuneIndex index;
	private final BlockOpsRuneConstruction construction;

	public BlockManager()
	{
		Wormholes.v("Starting Block Manager");
		catalog = new BlockOpsRuneCatalog();
		index = new BlockOpsRuneIndex();
		construction = new BlockOpsRuneConstruction(index, catalog);
		J.ar(() -> index.updatePlacedBlocks(), 9);
	}

	public void onLanguageReload()
	{
		catalog.onLanguageReload();
	}

	public void destroyAll()
	{
		index.destroyAll();
		catalog.unregisterAllRecipes();
	}

	@EventHandler
	public void on(ChunkLoadEvent e)
	{
		try
		{
			if(index.destroyAllInChunk(new GChunk(e.getChunk())))
			{
				J.s(() -> e.getChunk().unload());
			}
		}

		catch(Throwable ex)
		{
			Wormholes.log().log(Level.WARNING, "Could not clear tracked portal runes from chunk "
					+ e.getChunk().getX() + ", " + e.getChunk().getZ() + " in " + e.getWorld().getName(), ex);
		}
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void on(PlayerInteractEvent e)
	{
		if(!isWand(e.getPlayer().getInventory().getItemInMainHand()))
		{
			return;
		}
		if(!e.getAction().equals(Action.LEFT_CLICK_BLOCK))
		{
			return;
		}

		if(index.isReservedRuneCell(e.getClickedBlock()))
		{
			e.setUseInteractedBlock(Event.Result.DENY);
			e.setCancelled(true);
			return;
		}

		PortalBlock b = getBlock(e.getClickedBlock());

		if(b == null)
		{
			return;
		}

		e.setUseInteractedBlock(Event.Result.DENY);
		e.setCancelled(true);
		if(b.getType() == PortalType.RTP)
		{
			WormholesHud.notice(e.getPlayer(), Wormholes.text().component(WormholesMessages.PORTAL_RTP_RUNE_UNSUPPORTED));
			return;
		}
		if(!PortalTypeAccess.allows(e.getPlayer(), b.getType()))
		{
			WormholesHud.notice(e.getPlayer(), Wormholes.text().component(WormholesMessages.COMMAND_NO_PERMISSION));
			return;
		}
		WormholesHud.notice(e.getPlayer(), Wormholes.text().component(
				WormholesMessages.PORTAL_FORMING,
				WormholesLocalization.args(MessageArgument.untrusted("type", b.getType().name().toLowerCase()))));
		construction.construct(e.getPlayer(), e.getClickedBlock());
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void on(BlockPlaceEvent e)
	{
		PortalType placedType = catalog.placedRuneType(e.getItemInHand());

		if(placedType == null)
		{
			return;
		}

		placeBlock(new PortalBlock(placedType, e.getBlock().getLocation()));
		WormholesHud.notice(e.getPlayer(), Wormholes.text().component(WormholesMessages.PORTAL_RUNE_PLACED));
	}

	@EventHandler(priority = EventPriority.LOWEST)
	public void denyWandRuneBreak(BlockBreakEvent e)
	{
		if(index.isReservedRuneCell(e.getBlock()))
		{
			e.setCancelled(true);
			return;
		}
		if(!isWand(e.getPlayer().getInventory().getItemInMainHand()))
		{
			return;
		}
		if(getBlock(e.getBlock()) == null)
		{
			return;
		}
		e.setCancelled(true);
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void on(BlockBreakEvent e)
	{
		if(index.tracksChunk(new GChunk(e.getBlock().getLocation().getChunk())))
		{
			ItemStack drop = null;

			for(PortalBlock i : index.snapshotChunk(new GChunk(e.getBlock().getLocation().getChunk())))
			{
				if(i.getLocation().equals(e.getBlock().getLocation()))
				{
					removeBlock(i);
					e.setDropItems(false);

					if(e.getPlayer().getGameMode().equals(GameMode.SURVIVAL))
					{
						switch(i.getType())
						{
							case PORTAL:
								drop = getPortalRune(1);
								break;
							case WORMHOLE:
								drop = getWormholeRune(1);
								break;
							case GATEWAY:
								drop = getGatewayRune(1);
								break;
							case RTP:
								break;
						}
					}
				}
			}

			if(drop != null)
			{
				ItemStack dr = drop;
				Block dropBlock = e.getBlock();
				BlockBreakEvent breakEvent = e;
				Location dropAt = dropBlock.getLocation();
				String owner = e.getPlayer().getName();

				dropBrokenRune(
						task -> FoliaScheduler.runRegion(Wormholes.instance, dropAt, task, 1L),
						() -> FoliaScheduler.isOwnedByCurrentRegion(dropAt),
						() -> "Could not return a broken portal rune to " + owner + " at " + dropAt,
						() ->
						{
							if(!breakEvent.isCancelled() && dropBlock.isEmpty())
							{
								dropBlock.getWorld().dropItemNaturally(dropAt.clone().add(0.5, 0.5, 0.5), dr);
							}
						},
						() -> dropBlock.getWorld().dropItemNaturally(dropAt.clone().add(0.5, 0.5, 0.5), dr));
			}
		}
	}

	static void dropBrokenRune(BlockOpsGuardedDispatch.RegionDispatch dispatch, BooleanSupplier ownsRegion, Supplier<String> context,
			Runnable scheduledDrop, Runnable localDrop)
	{
		if(BlockOpsGuardedDispatch.dispatchGuarded(dispatch, ownsRegion, RUNE_BREAK_DROP_FAILED, RUNE_BREAK_DROP_SCHEDULE_REJECTED,
				context, scheduledDrop, localDrop))
		{
			return;
		}

		Wormholes.w(context.get() + " because region scheduling was rejected");
	}

	public PortalBlock getBlock(Block block)
	{
		return index.getBlock(block);
	}

	public void removeBlock(PortalBlock block)
	{
		if(index.unregisterBlock(block))
		{
			Wormholes.effectManager.playPortalBlockDestroyed(block.getLocation().getBlock());
		}
	}

	public void placeBlock(PortalBlock block)
	{
		index.registerBlockSilently(block);
		Wormholes.effectManager.playPortalBlockPlaced(block.getLocation().getBlock());
	}

	public void registerRecipes()
	{
		catalog.registerRecipes();
	}

	public boolean isPortalTool(ItemStack item)
	{
		return catalog.isPortalTool(item);
	}

	public boolean isWand(ItemStack item)
	{
		return catalog.isWand(item);
	}

	public boolean isPortalRune(ItemStack item)
	{
		return catalog.isPortalRune(item);
	}

	public ItemStack getWand()
	{
		return catalog.getWand();
	}

	public ItemStack getPortalRune(int c)
	{
		return catalog.getPortalRune(c);
	}

	public ItemStack getWormholeRune(int c)
	{
		return catalog.getWormholeRune(c);
	}

	public ItemStack getGatewayRune(int c)
	{
		return catalog.getGatewayRune(c);
	}

	public void refund(Set<Block> blocks, PortalType type)
	{
		construction.refund(blocks, type);
	}

	public ItemStack get(PortalType t, int stack)
	{
		return catalog.get(t, stack);
	}
}
