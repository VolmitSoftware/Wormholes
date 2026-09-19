package art.arcane.wormholes.portal.vanilla;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.bukkit.Location;
import org.bukkit.Axis;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Orientable;
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
import org.bukkit.entity.Entity;
import org.bukkit.util.BlockVector;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.Settings;
import art.arcane.wormholes.access.AccessGuards;
import art.arcane.wormholes.access.PlacementKind;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.PortalTypeAccess;
import art.arcane.wormholes.portal.DimensionalPortalKind;
import art.arcane.wormholes.api.portal.NetherPortalShapes;
import art.arcane.wormholes.util.Direction;

public final class VanillaPortalReplacer implements Listener, NetherPortalShapes
{
	private final ThreadLocal<Boolean> shapeProposal = ThreadLocal.withInitial(() -> false);

	private final VanillaPortalIndex index = new VanillaPortalIndex();
	private final VanillaPortalNetherPairing netherPairing = new VanillaPortalNetherPairing(index, new VanillaPortalNetherSites());
	private final VanillaPortalEndPairing endPairing = new VanillaPortalEndPairing(index, new VanillaPortalEndSites(index));
	private final VanillaPortalFrameIntegrity frames = new VanillaPortalFrameIntegrity();

	@Override
	public Result submit(World world, Set<BlockVector> positions, Axis axis, Entity creator)
	{
		if(!Settings.REPLACE_NETHER_AND_END_PORTALS || Wormholes.portalManager == null
				|| world == null || positions == null || positions.isEmpty() || (axis != Axis.X && axis != Axis.Z))
		{
			return Result.UNAVAILABLE;
		}
		Set<Block> cells = new HashSet<Block>(positions.size());
		Integer plane = null;
		for(BlockVector position : positions)
		{
			if(position == null || position.getBlockY() < world.getMinHeight() || position.getBlockY() >= world.getMaxHeight())
			{
				return Result.UNAVAILABLE;
			}
			int coordinate = axis == Axis.X ? position.getBlockZ() : position.getBlockX();
			if(plane != null && plane.intValue() != coordinate)
			{
				return Result.UNAVAILABLE;
			}
			plane = coordinate;
			if(!FoliaScheduler.isOwnedByCurrentRegion(world, position.getBlockX() >> 4, position.getBlockZ() >> 4))
			{
				return Result.UNAVAILABLE;
			}
			cells.add(world.getBlockAt(position.getBlockX(), position.getBlockY(), position.getBlockZ()));
		}
		if(owns(world, positions))
		{
			VanillaPortalCleanup.clearCells(cells, Material.NETHER_PORTAL);
			return Result.ACCEPTED;
		}
		if(WorldGroups.isDisabled(world) || WorldPairing.pairedNetherPortalTarget(world) == null)
		{
			return Result.UNAVAILABLE;
		}
		if(creator instanceof Player player && (!PortalTypeAccess.allows(player, PortalType.PORTAL) || !claimsAllow(player, world, cells)))
		{
			return Result.REJECTED;
		}
		for(Block cell : cells)
		{
			if(index.covers(cell.getLocation()))
			{
				return Result.REJECTED;
			}
		}
		if(!allowShapeProposal(world, cells, axis, creator))
		{
			return Result.REJECTED;
		}
		VanillaPortalIndex.PendingCoverage pending = index.registerPending(cells);
		Block anchor = cells.iterator().next();
		boolean scheduled = FoliaScheduler.runRegion(Wormholes.instance, anchor.getLocation(), () ->
		{
			try
			{
				netherPairing.pair(world, cells, axis == Axis.X ? Direction.N : Direction.E, DimensionalPortalKind.SHAPED_NETHER);
				VanillaPortalCleanup.clearCells(cells, Material.FIRE);
				VanillaPortalCleanup.clearCells(cells, Material.SOUL_FIRE);
			}
			finally
			{
				index.releasePending(pending);
			}
		}, 1L);
		if(!scheduled)
		{
			index.releasePending(pending);
		}
		return scheduled ? Result.ACCEPTED : Result.UNAVAILABLE;
	}

	@Override
	public boolean owns(World world, Set<BlockVector> cells)
	{
		return index.ownsNetherShape(world, cells);
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onPortalCreate(PortalCreateEvent event)
	{
		if(!Settings.REPLACE_NETHER_AND_END_PORTALS || shapeProposal.get())
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
		if(!claimsAllow(player, world, cells))
		{
			return;
		}
		World source = world;
		Block anchor = cells.iterator().next();
		VanillaPortalIndex.PendingCoverage pending = index.registerPending(cells);
		Wormholes.v(() -> "[vanilla-portal] " + event.getReason() + " create: " + cells.size() + " nether cells in " + source.getName() + " @ " + anchor.getX() + "," + anchor.getY() + "," + anchor.getZ());
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
		if(!claimsAllow(event.getPlayer(), frame.getWorld(), Set.of(event.getClickedBlock())))
		{
			return;
		}
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

	private boolean allowShapeProposal(World world, Set<Block> cells, Axis axis, Entity creator)
	{
		Orientable data = (Orientable) Material.NETHER_PORTAL.createBlockData();
		data.setAxis(axis);
		List<BlockState> originals = new ArrayList<BlockState>(cells.size());
		List<BlockState> proposed = new ArrayList<BlockState>(cells.size());
		for(Block cell : cells)
		{
			originals.add(cell.getState());
			BlockState state = cell.getState();
			state.setType(Material.NETHER_PORTAL);
			state.setBlockData(data.clone());
			proposed.add(state);
		}
		PortalCreateEvent event = new PortalCreateEvent(proposed, world, creator, PortalCreateEvent.CreateReason.FIRE);
		shapeProposal.set(true);
		try
		{
			Wormholes.instance.getServer().getPluginManager().callEvent(event);
		}
		finally
		{
			shapeProposal.remove();
		}
		if(event.isCancelled())
		{
			return false;
		}
		for(BlockState original : originals)
		{
			if(!original.getBlockData().equals(original.getBlock().getBlockData()))
			{
				return false;
			}
		}
		return true;
	}

	/** A replaced vanilla portal is still a new Wormholes portal, so the claim policy has a say. */
	private static boolean claimsAllow(Player player, World world, Set<Block> cells)
	{
		if(player == null || world == null || cells.isEmpty())
		{
			return true;
		}
		List<int[]> positions = new ArrayList<int[]>(cells.size());
		for(Block cell : cells)
		{
			positions.add(new int[] { cell.getX(), cell.getY(), cell.getZ() });
		}
		return AccessGuards.allowPlacement(player.getUniqueId(), world, positions, PlacementKind.CREATE);
	}

	private static boolean isEndCause(PlayerTeleportEvent.TeleportCause cause)
	{
		return cause == PlayerTeleportEvent.TeleportCause.END_PORTAL || cause == PlayerTeleportEvent.TeleportCause.END_GATEWAY;
	}
}
