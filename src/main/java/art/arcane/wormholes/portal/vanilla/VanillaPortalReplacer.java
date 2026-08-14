package art.arcane.wormholes.portal.vanilla;

import java.util.HashSet;
import java.util.Set;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.entity.Player;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.PortalTypeAccess;

public final class VanillaPortalReplacer implements Listener
{
	private final VanillaPortalIndex index = new VanillaPortalIndex();
	private final VanillaPortalNetherPairing netherPairing = new VanillaPortalNetherPairing(index, new VanillaPortalNetherSites());
	private final VanillaPortalEndPairing endPairing = new VanillaPortalEndPairing(index, new VanillaPortalEndSites(index));
	private final VanillaPortalFrameIntegrity frames = new VanillaPortalFrameIntegrity();

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onPortalCreate(PortalCreateEvent event)
	{
		if(!Settings.REPLACE_NETHER_AND_END_PORTALS)
		{
			return;
		}
		if(event.getReason() != PortalCreateEvent.CreateReason.FIRE
				&& event.getReason() != PortalCreateEvent.CreateReason.NETHER_PAIR)
		{
			return;
		}
		if(!(event.getEntity() instanceof Player player) || !PortalTypeAccess.allows(player, PortalType.PORTAL))
		{
			return;
		}
		Set<Block> cells = new HashSet<Block>();
		World world = null;
		for(BlockState state : event.getBlocks())
		{
			if(state.getType() == Material.NETHER_PORTAL)
			{
				cells.add(state.getBlock());
				world = state.getWorld();
			}
		}
		if(cells.isEmpty() || world == null)
		{
			return;
		}
		World source = world;
		Block anchor = cells.iterator().next();
		VanillaPortalIndex.PendingCoverage pending = index.registerPending(cells);
		Wormholes.w("[vanilla-portal] " + event.getReason() + " create: " + cells.size() + " nether cells in " + world.getName() + " @ " + anchor.getX() + "," + anchor.getY() + "," + anchor.getZ());
		if(!FoliaScheduler.runRegion(Wormholes.instance, anchor.getLocation(), () ->
		{
			try
			{
				netherPairing.pair(source, cells);
			}
			finally
			{
				index.releasePending(pending);
			}
		}, 2L))
		{
			index.releasePending(pending);
			Wormholes.w("[vanilla-portal] region refused the nether pairing pass at " + anchor.getX() + "," + anchor.getY() + "," + anchor.getZ()
					+ " in " + world.getName() + "; portal stays vanilla until it is relit");
		}
	}

	@EventHandler(ignoreCancelled = true)
	public void onPlayerPortal(PlayerPortalEvent event)
	{
		if(!Settings.REPLACE_NETHER_AND_END_PORTALS)
		{
			return;
		}
		if(index.covers(event.getFrom()) || (isEndCause(event.getCause()) && index.nearEndWindow(event.getFrom())))
		{
			event.setCancelled(true);
		}
	}

	@EventHandler(ignoreCancelled = true)
	public void onEntityPortal(EntityPortalEvent event)
	{
		if(!Settings.REPLACE_NETHER_AND_END_PORTALS)
		{
			return;
		}
		if(index.covers(event.getFrom()) || index.nearEndWindow(event.getFrom()))
		{
			event.setCancelled(true);
		}
	}

	@EventHandler(ignoreCancelled = true)
	public void onPlayerInteract(PlayerInteractEvent event)
	{
		if(!Settings.REPLACE_NETHER_AND_END_PORTALS)
		{
			return;
		}
		if(event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null)
		{
			return;
		}
		if(event.getClickedBlock().getType() != Material.END_PORTAL_FRAME)
		{
			return;
		}
		if(event.getItem() == null || event.getItem().getType() != Material.ENDER_EYE)
		{
			return;
		}
		if(!PortalTypeAccess.allows(event.getPlayer(), PortalType.PORTAL))
		{
			return;
		}
		Location frame = event.getClickedBlock().getLocation();
		VanillaPortalIndex.PendingCoverage pending = index.registerPendingEnd(frame);
		if(!FoliaScheduler.runRegion(Wormholes.instance, frame, () ->
		{
			try
			{
				endPairing.pair(frame);
			}
			finally
			{
				index.releasePending(pending);
			}
		}, 2L))
		{
			index.releasePending(pending);
			Wormholes.w("[vanilla-portal] region refused the end pairing pass at " + frame.getBlockX() + "," + frame.getBlockY() + "," + frame.getBlockZ()
					+ "; end portal stays vanilla until an eye is placed again");
		}
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onBlockBreak(BlockBreakEvent event)
	{
		if(!Settings.REPLACE_NETHER_AND_END_PORTALS)
		{
			return;
		}
		Block broken = event.getBlock();
		Material brokenType = broken.getType();
		frames.scheduleBreakCheck(broken, () -> broken.getType() != brokenType);
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onBlockExplode(BlockExplodeEvent event)
	{
		if(!Settings.REPLACE_NETHER_AND_END_PORTALS)
		{
			return;
		}
		frames.breakDamagedFrames(event.blockList());
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onEntityExplode(EntityExplodeEvent event)
	{
		if(!Settings.REPLACE_NETHER_AND_END_PORTALS)
		{
			return;
		}
		frames.breakDamagedFrames(event.blockList());
	}

	public void validateDimensionalFrames()
	{
		frames.validate();
	}

	private static boolean isEndCause(PlayerTeleportEvent.TeleportCause cause)
	{
		return cause == PlayerTeleportEvent.TeleportCause.END_PORTAL || cause == PlayerTeleportEvent.TeleportCause.END_GATEWAY;
	}
}
