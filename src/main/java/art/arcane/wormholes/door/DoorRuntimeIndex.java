package art.arcane.wormholes.door;

import art.arcane.wormholes.Settings;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.survival.doors.dimension.PocketWorldService;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Door;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

final class DoorRuntimeIndex implements AutoCloseable
{
	private final Plugin plugin;
	private final DoorStateGuard guard;
	private final PocketWorldService pocketWorldService;
	private final DoorPortalVisualService visuals;
	private final DoorSpatialIndex<RuntimeDoor> spatialIndex;
	private final ConcurrentHashMap<UUID, RuntimeDoor> runtimes;

	DoorRuntimeIndex(Plugin plugin, DoorStateGuard guard, PocketWorldService pocketWorldService)
	{
		this.plugin = Objects.requireNonNull(plugin, "plugin");
		this.guard = Objects.requireNonNull(guard, "guard");
		this.pocketWorldService = Objects.requireNonNull(pocketWorldService, "pocketWorldService");
		visuals = new DoorPortalVisualService(plugin);
		spatialIndex = new DoorSpatialIndex<>();
		runtimes = new ConcurrentHashMap<>();
	}

	RuntimeDoor install(PlacedDoorEndpoint endpoint)
	{
		RuntimeDoor runtime = runtimes.compute(endpoint.identity().itemId(), (ignored, current) ->
			current != null && current.endpoint().equals(endpoint) ? current : new RuntimeDoor(endpoint));
		spatialIndex.put(
			endpoint.identity().itemId(),
			endpoint.position().worldId(),
			endpoint.position().x(),
			endpoint.position().y(),
			endpoint.position().z(),
			runtime);
		return runtime;
	}

	void remove(PlacedDoorEndpoint endpoint)
	{
		UUID doorId = endpoint.identity().itemId();
		runtimes.remove(doorId);
		spatialIndex.remove(doorId);
		visuals.hide(doorId);
	}

	RuntimeDoor runtime(UUID doorId)
	{
		return runtimes.get(doorId);
	}

	List<DoorSpatialIndex.Entry<RuntimeDoor>> nearby(UUID worldId, int blockX, int blockZ, int chunkRadius)
	{
		return spatialIndex.nearby(worldId, blockX, blockZ, chunkRadius);
	}

	int size()
	{
		return runtimes.size();
	}

	void reconcile(RuntimeDoor runtime)
	{
		if(runtime == null || guard.closed())
		{
			return;
		}
		PlacedDoorEndpoint endpoint = runtime.endpoint();
		World world = world(endpoint.position());
		if(world == null)
		{
			runtime.invalidate();
			return;
		}
		if(!convertLegacyIronDoor(endpoint, world))
		{
			runtime.invalidate();
			return;
		}
		visuals.cleanChunk(world.getChunkAt(endpoint.position().x() >> 4, endpoint.position().z() >> 4));
		Optional<VanillaDoorSnapshot> captured = capture(endpoint, world);
		if(captured.isEmpty())
		{
			removeStaleEndpoint(runtime);
			return;
		}
		VanillaDoorSnapshot snapshot = captured.get();
		runtime.update(snapshot);
		if(snapshot.open() && destinationAvailable(endpoint.identity()))
		{
			visuals.show(endpoint, snapshot);
		}
		else
		{
			visuals.hide(endpoint.identity().itemId());
		}
	}

	void scheduleReconcile(PlacedDoorEndpoint endpoint, long delay)
	{
		World world = world(endpoint.position());
		if(world == null || !world.isChunkLoaded(endpoint.position().x() >> 4, endpoint.position().z() >> 4))
		{
			return;
		}
		FoliaScheduler.runRegion(plugin, world,
			endpoint.position().x() >> 4, endpoint.position().z() >> 4,
			() -> reconcile(runtimes.get(endpoint.identity().itemId())), delay);
	}

	void scheduleNearby(Block block, long delay)
	{
		for(DoorSpatialIndex.Entry<RuntimeDoor> nearby : spatialIndex.nearby(
			block.getWorld().getUID(), block.getX(), block.getZ(), 1))
		{
			if(Math.abs(nearby.blockX() - block.getX()) <= 2
				&& Math.abs(nearby.blockY() - block.getY()) <= 3
				&& Math.abs(nearby.blockZ() - block.getZ()) <= 2)
			{
				scheduleReconcile(nearby.value().endpoint(), delay);
			}
		}
	}

	void reconcileLoadedChunk(Chunk chunk)
	{
		visuals.cleanChunk(chunk);
		for(DoorSpatialIndex.Entry<RuntimeDoor> entry : spatialIndex.nearby(
			chunk.getWorld().getUID(), chunk.getX() << 4, chunk.getZ() << 4, 0))
		{
			reconcile(entry.value());
		}
	}

	void forgetUnloadedChunk(Chunk chunk)
	{
		visuals.unloadChunk(chunk);
	}

	void reconcileWorld(World world)
	{
		for(PlacedDoorEndpoint endpoint : guard.state().endpoints())
		{
			if(endpoint.position().worldId().equals(world.getUID()))
			{
				scheduleReconcile(endpoint, 1L);
			}
		}
	}

	boolean schedulePlacementConfirmation(PlacedDoorEndpoint endpoint)
	{
		World world = world(endpoint.position());
		return world != null && FoliaScheduler.runRegion(plugin, world,
			endpoint.position().x() >> 4, endpoint.position().z() >> 4,
			() -> confirmPlacement(endpoint), 1L);
	}

	Optional<VanillaDoorSnapshot> capture(PlacedDoorEndpoint endpoint, World world)
	{
		DoorPosition position = endpoint.position();
		Block lower = world.getBlockAt(position.x(), position.y(), position.z());
		if(!DoorSkin.isPlayerOperable(lower.getType()))
		{
			return Optional.empty();
		}
		return VanillaDoorSnapshot.capture(lower)
			.filter(snapshot -> snapshot.worldId().equals(position.worldId()));
	}

	World world(DoorPosition position)
	{
		return DoorWorlds.of(plugin.getServer(), position);
	}

	void closePhysicalDoor(World world, DoorwayPlane plane)
	{
		Block lowerBlock = world.getBlockAt(plane.blockX(), plane.blockY(), plane.blockZ());
		Block upperBlock = lowerBlock.getRelative(BlockFace.UP);
		Material material = lowerBlock.getType();
		boolean wasOpen = lowerBlock.getBlockData() instanceof Door lower && lower.isOpen();
		if(lowerBlock.getBlockData() instanceof Door lower)
		{
			lower.setOpen(false);
			lowerBlock.setBlockData(lower, false);
		}
		if(upperBlock.getBlockData() instanceof Door upper)
		{
			upper.setOpen(false);
			upperBlock.setBlockData(upper, false);
		}
		if(wasOpen)
		{
			try
			{
				world.playSound(
					new Location(world, plane.blockX() + 0.5D, plane.blockY() + 1.0D, plane.blockZ() + 0.5D),
					DimensionalDoorSounds.closeSound(material),
					SoundCategory.BLOCKS,
					Settings.portalSoundVolume(1.0F),
					1.0F);
			}
			catch(Throwable ex)
			{
				plugin.getLogger().log(Level.WARNING, "Could not play a dimensional-door close sound", ex);
			}
		}
	}

	void hideTransitVisual(UUID doorId)
	{
		try
		{
			visuals.hide(doorId);
		}
		catch(Throwable ex)
		{
			plugin.getLogger().log(Level.WARNING, "Could not remove a dimensional-door transit visual", ex);
		}
	}

	@Override
	public void close()
	{
		visuals.close();
		spatialIndex.clear();
		runtimes.clear();
	}

	private void confirmPlacement(PlacedDoorEndpoint endpoint)
	{
		if(guard.closed())
		{
			return;
		}
		RuntimeDoor runtime = runtimes.get(endpoint.identity().itemId());
		World world = world(endpoint.position());
		if(runtime != null && world != null && capture(endpoint, world).isPresent())
		{
			reconcile(runtime);
			guard.state().findMate(endpoint.identity()).ifPresent(mate -> scheduleReconcile(mate, 1L));
			return;
		}
		try
		{
			guard.mutate(() -> guard.state().removeEndpoint(endpoint.position()));
		}
		catch(IOException ex)
		{
			plugin.getLogger().log(Level.SEVERE, "Could not roll back a cancelled dimensional-door placement", ex);
		}
		remove(endpoint);
	}

	private void removeStaleEndpoint(RuntimeDoor runtime)
	{
		PlacedDoorEndpoint endpoint = runtime.endpoint();
		runtime.invalidate();
		Optional<PlacedDoorEndpoint> mate = guard.state().findMate(endpoint.identity());
		try
		{
			guard.mutate(() -> guard.state().removeEndpoint(endpoint.position()));
		}
		catch(IOException ex)
		{
			plugin.getLogger().log(Level.WARNING, "Could not remove stale dimensional-door endpoint", ex);
			visuals.hide(endpoint.identity().itemId());
			return;
		}
		remove(endpoint);
		mate.ifPresent(placedMate -> scheduleReconcile(placedMate, 1L));
	}

	private boolean destinationAvailable(DoorItemIdentity identity)
	{
		return switch(identity.kind())
		{
			case PAIR -> guard.state().findMate(identity).map(endpoint -> world(endpoint.position()) != null).orElse(false);
			case PERSONAL, PUBLIC -> pocketWorldService.world().isPresent();
			case RETURN -> true;
		};
	}

	private boolean convertLegacyIronDoor(PlacedDoorEndpoint endpoint, World world)
	{
		if(endpoint.identity().kind() != DoorKind.PUBLIC)
		{
			return true;
		}
		DoorPosition position = endpoint.position();
		Block lower = world.getBlockAt(position.x(), position.y(), position.z());
		if(lower.getType() != Material.IRON_DOOR)
		{
			return true;
		}
		Block upper = lower.getRelative(BlockFace.UP);
		if(upper.getType() != Material.IRON_DOOR
			|| !(lower.getBlockData() instanceof Door)
			|| !(upper.getBlockData() instanceof Door))
		{
			return true;
		}

		BlockData previousLower = lower.getBlockData().clone();
		BlockData previousUpper = upper.getBlockData().clone();
		try
		{
			Material material = DoorItemService.defaultMaterial(DoorKind.PUBLIC);
			lower.setBlockData(retypeDoor(previousLower, material), false);
			upper.setBlockData(retypeDoor(previousUpper, material), false);
			plugin.getLogger().info("Converted legacy iron dimensional door "
				+ endpoint.identity().itemId() + " to " + material + ".");
			return true;
		}
		catch(RuntimeException exception)
		{
			try
			{
				lower.setBlockData(previousLower, false);
				upper.setBlockData(previousUpper, false);
			}
			catch(RuntimeException restoreFailure)
			{
				exception.addSuppressed(restoreFailure);
			}
			plugin.getLogger().log(Level.SEVERE,
				"Could not convert legacy iron dimensional door " + endpoint.identity().itemId(), exception);
			return false;
		}
	}

	private static Door retypeDoor(BlockData sourceData, Material material)
	{
		if(!(sourceData instanceof Door source) || !(material.createBlockData() instanceof Door target))
		{
			throw new IllegalArgumentException("Door material and block data are required");
		}
		target.setFacing(source.getFacing());
		target.setHalf(source.getHalf());
		target.setHinge(source.getHinge());
		target.setOpen(source.isOpen());
		target.setPowered(source.isPowered());
		return target;
	}
}
