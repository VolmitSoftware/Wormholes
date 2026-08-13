package art.arcane.wormholes.door;

import art.arcane.wormholes.Settings;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.platform.WormholesPlatform;
import art.arcane.wormholes.survival.doors.dimension.PocketWorldService;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.TrapDoor;
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
	private static final long ARRIVAL_AUTO_CLOSE_TICKS = 30L;

	private final Plugin plugin;
	private final DoorStateGuard guard;
	private final PocketWorldService pocketWorldService;
	private final DoorPortalVisualService visuals;
	private final DoorEntitySweep sweep;
	private final DoorAutoCloseBook autoClose;
	private final DoorSpatialIndex<RuntimeDoor> spatialIndex;
	private final ConcurrentHashMap<UUID, RuntimeDoor> runtimes;

	DoorRuntimeIndex(Plugin plugin, DoorStateGuard guard, PocketWorldService pocketWorldService)
	{
		this.plugin = Objects.requireNonNull(plugin, "plugin");
		this.guard = Objects.requireNonNull(guard, "guard");
		this.pocketWorldService = Objects.requireNonNull(pocketWorldService, "pocketWorldService");
		visuals = new DoorPortalVisualService(plugin);
		sweep = new DoorEntitySweep(plugin, guard::closed);
		autoClose = new DoorAutoCloseBook();
		spatialIndex = new DoorSpatialIndex<>();
		runtimes = new ConcurrentHashMap<>();
	}

	void attachMovementSink(DoorEntitySweep.MovementSink sink)
	{
		sweep.attach(sink);
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
		sweep.stop(doorId);
		autoClose.forget(doorId);
	}

	boolean replace(PlacedDoorEndpoint expected, PlacedDoorEndpoint updated)
	{
		Objects.requireNonNull(expected, "expected");
		Objects.requireNonNull(updated, "updated");
		if(!expected.identity().equals(updated.identity())
			|| !expected.position().equals(updated.position()))
		{
			throw new IllegalArgumentException("Runtime replacement must preserve door identity and position");
		}
		RuntimeDoor replacement = install(updated);
		World world = world(updated.position());
		if(world == null)
		{
			return true;
		}
		int chunkX = updated.position().x() >> 4;
		int chunkZ = updated.position().z() >> 4;
		if(WormholesPlatform.isOwnedByCurrentRegion(world, chunkX, chunkZ))
		{
			reconcile(replacement);
			return true;
		}
		return FoliaScheduler.runRegion(plugin, world, chunkX, chunkZ, () -> reconcile(replacement));
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
			invalidate(runtime);
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
		// The only fresh reading of the door there is, so it is also where a pending
		// auto-close is retired once a player has shut the door themselves.
		autoClose.observe(endpoint.identity().itemId(), snapshot.portalLive());
		boolean usable = snapshot.portalLive() && destinationAvailable(endpoint.identity());
		if(usable)
		{
			visuals.show(endpoint, snapshot);
		}
		else
		{
			visuals.hide(endpoint.identity().itemId());
		}
		// The only place the physical open state is re-read for every door, so it is
		// also where the object sweep is started and stopped.
		sweep.observe(endpoint, runtime, world, usable);
	}

	private void invalidate(RuntimeDoor runtime)
	{
		runtime.invalidate();
		sweep.stop(runtime.endpoint().identity().itemId());
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

	/**
	 * Drops every runtime attachment for doors in a chunk that is going away.
	 *
	 * <p>An unloaded chunk yields no entities and fires no block event, so a sweep
	 * left running over one never sees its door close and never stops. Nothing
	 * reschedules a reconcile there either, so the door has to be forgotten here
	 * and re-read when the chunk comes back.</p>
	 */
	void forgetUnloadedChunk(Chunk chunk)
	{
		visuals.unloadChunk(chunk);
		for(DoorSpatialIndex.Entry<RuntimeDoor> entry : spatialIndex.nearby(
			chunk.getWorld().getUID(), chunk.getX() << 4, chunk.getZ() << 4, 0))
		{
			invalidate(entry.value());
		}
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
		DoorForm form = endpoint.identity().form();
		Block lower = world.getBlockAt(position.x(), position.y(), position.z());
		if(!DoorSkin.isSupportedSkin(lower.getType(), form))
		{
			return Optional.empty();
		}
		return VanillaDoorSnapshot.capture(lower, endpoint.openState())
			.filter(snapshot -> snapshot.worldId().equals(position.worldId())
				&& snapshot.plane().form() == form);
	}

	World world(DoorPosition position)
	{
		return DoorWorlds.of(plugin.getServer(), position);
	}

	/**
	 * Returns one door to its dormant physical state. A closed-state contact
	 * surface is already shut and is left exactly as it is.
	 */
	void closePhysicalDoor(World world, DoorwayPlane plane, UUID doorId)
	{
		Objects.requireNonNull(doorId, "doorId");
		if(plane.contactSurface())
		{
			return;
		}
		setPhysicalDoorOpen(world, plane, false);
		autoClose.forget(doorId);
		sweep.stop(doorId);
		RuntimeDoor runtime = runtimes.get(doorId);
		if(runtime != null)
		{
			runtime.cycle().observe(false);
		}
	}

	/**
	 * Brings a destination door to its live state for an arriving traveler and
	 * schedules the matching restore. A door a player opened is left completely
	 * alone: the server only ever undoes what it did. One the server is already
	 * holding open has its close pushed back instead, so the timer armed for an
	 * earlier traveler never shuts the door on a later one mid-flight.
	 *
	 * <p>A closed-state contact surface is never swung at all.</p>
	 */
	void openForArrival(PlacedDoorEndpoint endpoint, World world, VanillaDoorSnapshot snapshot)
	{
		// Trapdoor arrivals never pass through the destination aperture, and swinging the
		// plate open would remove the floor an upward arrival was just validated against.
		if(guard.closed()
			|| snapshot.plane().form() != DoorForm.DOOR
			|| snapshot.plane().openState() != DoorOpenState.OPEN)
		{
			return;
		}
		UUID doorId = endpoint.identity().itemId();
		switch(autoClose.decideArrival(doorId, snapshot.portalLive()))
		{
			case OPEN -> openAndArm(endpoint, world, snapshot, doorId);
			case EXTEND ->
			{
				long extended = autoClose.arm(doorId);
				Wormholes.v("[door] ARRIVAL extend door=" + doorId + " token=" + extended);
				scheduleAutoClose(endpoint, extended, 0);
			}
			case LEAVE ->
			{
			}
		}
	}

	private void openAndArm(PlacedDoorEndpoint endpoint, World world, VanillaDoorSnapshot snapshot, UUID doorId)
	{
		try
		{
			setPhysicalDoorOpen(world, snapshot.plane(), true);
		}
		catch(Throwable ex)
		{
			plugin.getLogger().log(Level.WARNING, "Could not open a destination dimensional door", ex);
			return;
		}
		// Armed first so a reconcile that finds the door gone also drops the pending close.
		long token = autoClose.arm(doorId);
		reconcile(runtimes.get(doorId));
		Wormholes.v("[door] ARRIVAL open door=" + doorId + " token=" + token);
		scheduleAutoClose(endpoint, token, 0);
	}

	private void scheduleAutoClose(PlacedDoorEndpoint endpoint, long token, int deferrals)
	{
		UUID doorId = endpoint.identity().itemId();
		World world = world(endpoint.position());
		if(world == null || !FoliaScheduler.runRegion(plugin, world,
			endpoint.position().x() >> 4, endpoint.position().z() >> 4,
			() -> runAutoClose(endpoint, token, deferrals), ARRIVAL_AUTO_CLOSE_TICKS))
		{
			autoClose.forget(doorId);
		}
	}

	private void runAutoClose(PlacedDoorEndpoint endpoint, long token, int deferrals)
	{
		UUID doorId = endpoint.identity().itemId();
		RuntimeDoor runtime = runtimes.get(doorId);
		World world = world(endpoint.position());
		if(guard.closed() || runtime == null || world == null)
		{
			autoClose.forget(doorId);
			return;
		}
		// Capturing would force-load an unloaded chunk; the reload reconcile re-reads the door anyway.
		if(!world.isChunkLoaded(endpoint.position().x() >> 4, endpoint.position().z() >> 4))
		{
			autoClose.forget(doorId);
			return;
		}
		Optional<VanillaDoorSnapshot> captured = capture(endpoint, world);
		DoorAutoCloseBook.Decision decision = autoClose.decide(
			doorId,
			token,
			captured.isPresent() && captured.get().portalLive(),
			runtime.cycle().phase() == DoorOpenCycle.Phase.IN_TRANSIT,
			deferrals);
		switch(decision)
		{
			case CLOSE ->
			{
				closePhysicalDoor(world, captured.get().plane(), doorId);
				reconcile(runtime);
				Wormholes.v("[door] ARRIVAL close door=" + doorId + " token=" + token);
			}
			case DEFER -> scheduleAutoClose(endpoint, token, deferrals + 1);
			case ABANDONED -> Wormholes.v("[door] ARRIVAL close abandoned door=" + doorId + " token=" + token);
			case SUPERSEDED, ALREADY_CLOSED ->
			{
			}
		}
	}

	private void setPhysicalDoorOpen(World world, DoorwayPlane plane, boolean open)
	{
		Block block = world.getBlockAt(plane.blockX(), plane.blockY(), plane.blockZ());
		Material material = block.getType();
		boolean changed = plane.horizontal()
			? swingTrapdoor(block, open)
			: swingDoor(block, open);
		if(!changed)
		{
			return;
		}
		try
		{
			String sound = open
				? DimensionalDoorSounds.openSound(material)
				: DimensionalDoorSounds.closeSound(material);
			world.playSound(
				new Location(world, plane.blockX() + 0.5D, plane.blockY() + 1.0D, plane.blockZ() + 0.5D),
				sound,
				SoundCategory.BLOCKS,
				Settings.portalSoundVolume(1.0F),
				1.0F);
		}
		catch(Throwable ex)
		{
			plugin.getLogger().log(Level.WARNING, "Could not play a dimensional-door swing sound", ex);
		}
	}

	private static boolean swingDoor(Block lowerBlock, boolean open)
	{
		Block upperBlock = lowerBlock.getRelative(BlockFace.UP);
		boolean changed = lowerBlock.getBlockData() instanceof Door lower && lower.isOpen() != open;
		if(lowerBlock.getBlockData() instanceof Door lower)
		{
			lower.setOpen(open);
			lowerBlock.setBlockData(lower, false);
		}
		if(upperBlock.getBlockData() instanceof Door upper)
		{
			upper.setOpen(open);
			upperBlock.setBlockData(upper, false);
		}
		return changed;
	}

	private static boolean swingTrapdoor(Block block, boolean open)
	{
		if(!(block.getBlockData() instanceof TrapDoor plate) || plate.isOpen() == open)
		{
			return false;
		}
		plate.setOpen(open);
		block.setBlockData(plate, false);
		return true;
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
		sweep.close();
		autoClose.clear();
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
		invalidate(runtime);
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
}
