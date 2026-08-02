package art.arcane.wormholes.door;

import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

import java.util.Objects;
import java.util.Optional;

public final class DoorBlockProtection implements Listener
{
	private final DoorStateGuard guard;
	private final PocketSpaceIndex pockets;

	DoorBlockProtection(DoorStateGuard guard, PocketSpaceIndex pockets)
	{
		this.guard = Objects.requireNonNull(guard, "guard");
		this.pockets = Objects.requireNonNull(pockets, "pockets");
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onBurn(BlockBurnEvent event)
	{
		if(isProtected(event.getBlock()))
		{
			event.setCancelled(true);
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onPistonExtend(BlockPistonExtendEvent event)
	{
		if(event.getBlocks().stream().anyMatch(this::isProtected)
			|| event.getBlocks().stream().map(block -> block.getRelative(event.getDirection())).anyMatch(this::isProtected))
		{
			event.setCancelled(true);
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onPistonRetract(BlockPistonRetractEvent event)
	{
		if(event.getBlocks().stream().anyMatch(this::isProtected))
		{
			event.setCancelled(true);
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onEntityChangeBlock(EntityChangeBlockEvent event)
	{
		if(isProtected(event.getBlock()))
		{
			event.setCancelled(true);
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onEntityExplode(EntityExplodeEvent event)
	{
		event.blockList().removeIf(this::isProtected);
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onBlockExplode(BlockExplodeEvent event)
	{
		event.blockList().removeIf(this::isProtected);
	}

	Optional<PlacedDoorEndpoint> endpointForDoorBlock(Block block)
	{
		Optional<PlacedDoorEndpoint> lower = guard.state().findEndpoint(
			block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
		if(lower.isPresent())
		{
			return lower;
		}
		return guard.state().findEndpoint(
			block.getWorld().getUID(), block.getX(), block.getY() - 1, block.getZ());
	}

	Optional<PlacedDoorEndpoint> endpointSupportedBy(Block block)
	{
		return guard.state().findEndpoint(
			block.getWorld().getUID(), block.getX(), block.getY() + 1, block.getZ());
	}

	boolean isPocketCore(Block block)
	{
		return pockets.isCoreBlock(block);
	}

	private boolean isProtected(Block block)
	{
		return endpointForDoorBlock(block).isPresent()
			|| endpointSupportedBy(block).isPresent()
			|| pockets.isCoreBlock(block);
	}
}
