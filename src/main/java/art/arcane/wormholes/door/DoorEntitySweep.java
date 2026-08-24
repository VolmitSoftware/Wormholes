package art.arcane.wormholes.door;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.platform.BukkitRegionTaskProvider;
import art.arcane.wormholes.platform.WormholesPlatform;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Vehicle;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Per-tick candidate feed for travelers that never fire a movement event.
 *
 * <p>Players, mobs, and vehicles arrive through {@code PlayerMoveEvent},
 * Paper's {@code EntityMoveEvent}, and {@code VehicleMoveEvent}. Projectiles,
 * dropped items, and experience orbs fire nothing, so an open door sweeps the
 * air around itself once per tick and synthesizes the movement segment those
 * events would have carried: {@code position - velocity} to {@code position}.
 * The rest of the pipeline - crossing math, eligibility, ledger dedupe - is
 * unchanged.</p>
 *
 * <p>A sweep exists only while its door presents a live portal, so a server with
 * no usable dimensional door schedules nothing at all.</p>
 */
final class DoorEntitySweep implements AutoCloseable
{
	/** Wide enough that a 3 blocks-per-tick arrow is sampled at least once near the plane. */
	private static final double HORIZONTAL_REACH = 4.5D;
	/** The aperture spans two blocks around its centre, plus headroom for steep shots. */
	private static final double VERTICAL_REACH = 3.0D;
	private static final long SWEEP_PERIOD_TICKS = 1L;
	private static final long RETRY_DELAY_TICKS = 1L;

	interface MovementSink
	{
		void accept(Entity traveler, Location from, Location to);
	}

	@FunctionalInterface
	interface RegionDispatch
	{
		boolean run(
			World world,
			int chunkX,
			int chunkZ,
			Runnable task,
			Runnable retired,
			long delayTicks);
	}

	@FunctionalInterface
	interface RetryDispatch
	{
		boolean run(Runnable task, long delayTicks);
	}

	interface ChunkAccess
	{
		boolean loaded(World world, int chunkX, int chunkZ);

		boolean owned(World world, int chunkX, int chunkZ);

		Entity[] entities(World world, int chunkX, int chunkZ);
	}

	@FunctionalInterface
	interface ClipWarning
	{
		void accept(DoorwayPlane plane);
	}

	record Dependencies(
		Logger logger,
		BooleanSupplier closed,
		RegionDispatch regions,
		RetryDispatch retries,
		ChunkAccess chunks,
		ClipWarning clipWarning)
	{
		Dependencies
		{
			Objects.requireNonNull(logger, "logger");
			Objects.requireNonNull(closed, "closed");
			Objects.requireNonNull(regions, "regions");
			Objects.requireNonNull(retries, "retries");
			Objects.requireNonNull(chunks, "chunks");
			Objects.requireNonNull(clipWarning, "clipWarning");
		}
	}

	private final Logger logger;
	private final BooleanSupplier closed;
	private final RegionDispatch regions;
	private final RetryDispatch retries;
	private final ChunkAccess chunkAccess;
	private final ClipWarning clipWarning;
	private final ConcurrentHashMap<UUID, ActiveDoor> active;
	private final ConcurrentHashMap<ChunkKey, ChunkSweep> shared;
	private final AtomicBoolean warnedClip;
	private final Object lifecycleLock;

	private volatile MovementSink sink;

	DoorEntitySweep(Plugin plugin, BooleanSupplier closed)
	{
		this(productionDependencies(plugin, closed));
	}

	DoorEntitySweep(Dependencies dependencies)
	{
		Dependencies required = Objects.requireNonNull(dependencies, "dependencies");
		logger = required.logger();
		closed = required.closed();
		regions = required.regions();
		retries = required.retries();
		chunkAccess = required.chunks();
		clipWarning = required.clipWarning();
		active = new ConcurrentHashMap<>();
		shared = new ConcurrentHashMap<>();
		warnedClip = new AtomicBoolean();
		lifecycleLock = new Object();
	}

	void attach(MovementSink movementSink)
	{
		sink = Objects.requireNonNull(movementSink, "movementSink");
	}

	int activeSweeps()
	{
		return active.size();
	}

	int activeChunkSweeps()
	{
		return shared.size();
	}

	void chunkLoaded(World world, int chunkX, int chunkZ)
	{
		if(world == null || sink == null || closed.getAsBoolean())
		{
			return;
		}
		ChunkSweep chunkSweep = shared.get(new ChunkKey(world.getUID(), chunkX, chunkZ));
		if(chunkSweep == null || chunkSweep.world() != world)
		{
			return;
		}
		chunkSweep.state().compareAndSet(SweepState.PAUSED, SweepState.IDLE);
		schedule(chunkSweep);
	}

	void observe(PlacedDoorEndpoint endpoint, RuntimeDoor runtime, World world, boolean active)
	{
		if(active)
		{
			start(endpoint, runtime, world);
		}
		else
		{
			stop(endpoint.identity().itemId());
		}
	}

	void stop(UUID doorId)
	{
		UUID requiredDoorId = Objects.requireNonNull(doorId, "doorId");
		ActiveDoor removed;
		synchronized(lifecycleLock)
		{
			removed = active.remove(requiredDoorId);
			if(removed != null)
			{
				unlink(removed);
			}
		}
		if(removed != null)
		{
			Wormholes.v("[door] SWEEP stop door=" + requiredDoorId + " active=" + active.size());
		}
	}

	@Override
	public void close()
	{
		synchronized(lifecycleLock)
		{
			active.clear();
			shared.clear();
			sink = null;
		}
	}

	private void start(PlacedDoorEndpoint endpoint, RuntimeDoor runtime, World world)
	{
		if(endpoint == null || runtime == null || world == null || sink == null || closed.getAsBoolean())
		{
			return;
		}
		DoorwayPlane plane = runtime.plane();
		if(plane == null)
		{
			return;
		}

		UUID doorId = endpoint.identity().itemId();
		ActiveDoor registration;
		boolean started;
		List<ChunkSweep> idle = new ArrayList<>(4);
		synchronized(lifecycleLock)
		{
			if(sink == null || closed.getAsBoolean())
			{
				return;
			}
			ActiveDoor current = active.get(doorId);
			if(current != null && current.matches(runtime, world, plane))
			{
				registration = current;
				started = false;
			}
			else
			{
				if(current != null)
				{
					active.remove(doorId, current);
					unlink(current);
				}
				registration = ActiveDoor.create(doorId, runtime, world, plane);
				active.put(doorId, registration);
				started = true;
			}
			for(ChunkKey key : registration.targets())
			{
				ChunkSweep chunkSweep = shared.get(key);
				if(chunkSweep == null)
				{
					chunkSweep = new ChunkSweep(key, world);
					shared.put(key, chunkSweep);
				}
				chunkSweep.doors().put(doorId, registration);
				if(chunkSweep.state().get() == SweepState.IDLE)
				{
					idle.add(chunkSweep);
				}
			}
		}

		for(ChunkSweep chunkSweep : idle)
		{
			schedule(chunkSweep);
		}
		if(started && active.get(doorId) == registration)
		{
			Wormholes.v("[door] SWEEP start door=" + doorId + " active=" + active.size());
		}
	}

	private void schedule(ChunkSweep chunkSweep)
	{
		if(!current(chunkSweep)
			|| !chunkSweep.state().compareAndSet(SweepState.IDLE, SweepState.SCHEDULED))
		{
			return;
		}
		AtomicBoolean pending = new AtomicBoolean(true);
		Runnable task = () ->
		{
			if(pending.compareAndSet(true, false)
				&& chunkSweep.state().compareAndSet(SweepState.SCHEDULED, SweepState.IDLE))
			{
				run(chunkSweep);
			}
		};
		Runnable retired = () ->
		{
			if(pending.compareAndSet(true, false)
				&& chunkSweep.state().compareAndSet(SweepState.SCHEDULED, SweepState.IDLE))
			{
				retry(chunkSweep);
			}
		};
		boolean accepted;
		try
		{
			accepted = regions.run(
				chunkSweep.world(),
				chunkSweep.key().chunkX(),
				chunkSweep.key().chunkZ(),
				task,
				retired,
				SWEEP_PERIOD_TICKS);
		}
		catch(Throwable ex)
		{
			logger.log(Level.WARNING, "Could not schedule a dimensional-door object sweep", ex);
			accepted = false;
		}
		if(!accepted)
		{
			retired.run();
		}
	}

	private void run(ChunkSweep chunkSweep)
	{
		if(!current(chunkSweep))
		{
			return;
		}
		boolean repeat = true;
		try
		{
			repeat = sweepChunk(chunkSweep);
		}
		catch(Throwable ex)
		{
			logger.log(Level.FINE, "Could not sweep a dimensional-door chunk for objects", ex);
		}
		if(!current(chunkSweep))
		{
			return;
		}
		if(!repeat)
		{
			chunkSweep.state().compareAndSet(SweepState.IDLE, SweepState.PAUSED);
			return;
		}
		if(current(chunkSweep))
		{
			schedule(chunkSweep);
		}
	}

	private boolean sweepChunk(ChunkSweep chunkSweep)
	{
		MovementSink movementSink = sink;
		if(movementSink == null || closed.getAsBoolean())
		{
			return true;
		}
		World world = chunkSweep.world();
		ChunkKey key = chunkSweep.key();
		if(!chunkAccess.loaded(world, key.chunkX(), key.chunkZ()))
		{
			return false;
		}
		DoorwayPlane representative = representativePlane(chunkSweep);
		if(!chunkAccess.owned(world, key.chunkX(), key.chunkZ()))
		{
			warnClip(representative);
			return true;
		}

		Routes routes = routes(chunkSweep);
		warnClip(routes.clipped());
		if(routes.centers().isEmpty())
		{
			return true;
		}
		for(Entity candidate : chunkAccess.entities(world, key.chunkX(), key.chunkZ()))
		{
			if(isSweepable(candidate))
			{
				feed(movementSink, routes.centers(), candidate);
			}
		}
		return true;
	}

	private Routes routes(ChunkSweep chunkSweep)
	{
		List<DoorVec3> centers = new ArrayList<>(chunkSweep.doors().size());
		Map<ChunkKey, Boolean> ownership = new HashMap<>();
		ownership.put(chunkSweep.key(), Boolean.TRUE);
		DoorwayPlane clipped = null;
		for(ActiveDoor door : chunkSweep.doors().values())
		{
			if(active.get(door.doorId()) != door)
			{
				continue;
			}
			DoorwayPlane currentPlane = door.runtime().plane();
			if(!shouldSweep(currentPlane, door.runtime().cycle(), true))
			{
				retire(door);
				continue;
			}
			Boolean ownsOrigin = ownership.get(door.origin());
			if(ownsOrigin == null)
			{
				ownsOrigin = Boolean.valueOf(chunkAccess.owned(
					door.world(), door.origin().chunkX(), door.origin().chunkZ()));
				ownership.put(door.origin(), ownsOrigin);
			}
			if(!ownsOrigin.booleanValue())
			{
				if(clipped == null)
				{
					clipped = currentPlane;
				}
				continue;
			}
			DoorVec3 center = currentPlane.equals(door.plane())
				? door.center()
				: currentPlane.center();
			if(contains(span(center), chunkSweep.key()))
			{
				centers.add(center);
			}
		}
		return new Routes(centers, clipped);
	}

	private DoorwayPlane representativePlane(ChunkSweep chunkSweep)
	{
		for(ActiveDoor door : chunkSweep.doors().values())
		{
			if(active.get(door.doorId()) == door)
			{
				return door.runtime().plane();
			}
		}
		return null;
	}

	private void feed(MovementSink movementSink, List<DoorVec3> centers, Entity candidate)
	{
		try
		{
			if(candidate.isDead() || !candidate.isValid())
			{
				return;
			}
			Location to = candidate.getLocation();
			if(to.getWorld() == null || !withinReach(centers, to.getX(), to.getY(), to.getZ()))
			{
				return;
			}
			Vector velocity = candidate.getVelocity();
			Location from = to.clone().subtract(velocity);
			movementSink.accept(candidate, from, to);
		}
		catch(Throwable ex)
		{
			logger.log(Level.FINE, "Could not evaluate a swept dimensional-door object", ex);
		}
	}

	private boolean current(ChunkSweep chunkSweep)
	{
		return sink != null
			&& !closed.getAsBoolean()
			&& shared.get(chunkSweep.key()) == chunkSweep
			&& !chunkSweep.doors().isEmpty();
	}

	private void retry(ChunkSweep chunkSweep)
	{
		if(!current(chunkSweep)
			|| !chunkSweep.state().compareAndSet(SweepState.IDLE, SweepState.RETRY_SCHEDULED))
		{
			return;
		}
		Runnable retry = () ->
		{
			if(chunkSweep.state().compareAndSet(SweepState.RETRY_SCHEDULED, SweepState.IDLE))
			{
				schedule(chunkSweep);
			}
		};
		boolean accepted;
		try
		{
			accepted = retries.run(retry, RETRY_DELAY_TICKS);
		}
		catch(Throwable ex)
		{
			logger.log(Level.WARNING, "Could not schedule a dimensional-door sweep retry", ex);
			accepted = false;
		}
		if(!accepted)
		{
			chunkSweep.state().compareAndSet(SweepState.RETRY_SCHEDULED, SweepState.IDLE);
		}
	}

	private void retire(ActiveDoor door)
	{
		synchronized(lifecycleLock)
		{
			if(active.remove(door.doorId(), door))
			{
				unlink(door);
			}
		}
	}

	private void unlink(ActiveDoor door)
	{
		for(ChunkKey key : door.targets())
		{
			ChunkSweep chunkSweep = shared.get(key);
			if(chunkSweep == null)
			{
				continue;
			}
			chunkSweep.doors().remove(door.doorId(), door);
			if(chunkSweep.doors().isEmpty())
			{
				shared.remove(key, chunkSweep);
			}
		}
	}

	private void warnClip(DoorwayPlane plane)
	{
		if(plane != null && warnedClip.compareAndSet(false, true))
		{
			clipWarning.accept(plane);
		}
	}

	static boolean shouldSweep(DoorwayPlane plane, DoorOpenCycle cycle, boolean chunkLoaded)
	{
		return plane != null && cycle != null && chunkLoaded && cycle.portalActive();
	}

	static ChunkSpan span(DoorVec3 center)
	{
		return new ChunkSpan(
			chunkOf(center.x() - HORIZONTAL_REACH),
			chunkOf(center.z() - HORIZONTAL_REACH),
			chunkOf(center.x() + HORIZONTAL_REACH),
			chunkOf(center.z() + HORIZONTAL_REACH));
	}

	static boolean withinReach(DoorVec3 center, double x, double y, double z)
	{
		return Math.abs(x - center.x()) <= HORIZONTAL_REACH
			&& Math.abs(y - center.y()) <= VERTICAL_REACH
			&& Math.abs(z - center.z()) <= HORIZONTAL_REACH;
	}

	static boolean isSweepable(Entity candidate)
	{
		if(candidate instanceof LivingEntity || candidate instanceof Vehicle)
		{
			return false;
		}
		return candidate instanceof Projectile
			|| candidate instanceof Item
			|| candidate instanceof ExperienceOrb;
	}

	private static Dependencies productionDependencies(Plugin plugin, BooleanSupplier closed)
	{
		Plugin activePlugin = Objects.requireNonNull(plugin, "plugin");
		BooleanSupplier closedState = Objects.requireNonNull(closed, "closed");
		return new Dependencies(
			activePlugin.getLogger(),
			closedState,
			(world, chunkX, chunkZ, task, retired, delayTicks) -> BukkitRegionTaskProvider.run(
				world, chunkX, chunkZ, task, retired, delayTicks),
			(task, delayTicks) -> scheduleRetry(activePlugin, task, delayTicks),
			new BukkitChunkAccess(),
			DoorEntitySweep::logClip);
	}

	private static boolean scheduleRetry(Plugin plugin, Runnable task, long delayTicks)
	{
		if(FoliaScheduler.runGlobal(plugin, task, delayTicks))
		{
			return true;
		}
		CompletableFuture.delayedExecutor(
			Math.max(1L, delayTicks) * 50L,
			TimeUnit.MILLISECONDS).execute(task);
		return true;
	}

	private static void logClip(DoorwayPlane plane)
	{
		Wormholes.w("[door] SWEEP clipped at a region boundary near "
			+ plane.blockX() + "," + plane.blockY() + "," + plane.blockZ()
			+ "; objects approaching from the far side are not swept");
	}

	private static boolean withinReach(List<DoorVec3> centers, double x, double y, double z)
	{
		for(DoorVec3 center : centers)
		{
			if(withinReach(center, x, y, z))
			{
				return true;
			}
		}
		return false;
	}

	private static boolean contains(ChunkSpan span, ChunkKey key)
	{
		return key.chunkX() >= span.minChunkX()
			&& key.chunkX() <= span.maxChunkX()
			&& key.chunkZ() >= span.minChunkZ()
			&& key.chunkZ() <= span.maxChunkZ();
	}

	private static int chunkOf(double blockCoordinate)
	{
		return Math.floorDiv((int) Math.floor(blockCoordinate), 16);
	}

	record ChunkSpan(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ)
	{
	}

	private record ChunkKey(UUID worldId, int chunkX, int chunkZ)
	{
		private ChunkKey
		{
			Objects.requireNonNull(worldId, "worldId");
		}
	}

	private record ActiveDoor(
		UUID doorId,
		RuntimeDoor runtime,
		World world,
		DoorwayPlane plane,
		DoorVec3 center,
		ChunkKey origin,
		List<ChunkKey> targets)
	{
		private ActiveDoor
		{
			Objects.requireNonNull(doorId, "doorId");
			Objects.requireNonNull(runtime, "runtime");
			Objects.requireNonNull(world, "world");
			Objects.requireNonNull(plane, "plane");
			Objects.requireNonNull(center, "center");
			Objects.requireNonNull(origin, "origin");
			targets = List.copyOf(targets);
		}

		private static ActiveDoor create(UUID doorId, RuntimeDoor runtime, World world, DoorwayPlane plane)
		{
			DoorVec3 center = plane.center();
			ChunkSpan span = span(center);
			UUID worldId = world.getUID();
			List<ChunkKey> targets = new ArrayList<>(
				(span.maxChunkX() - span.minChunkX() + 1) * (span.maxChunkZ() - span.minChunkZ() + 1));
			for(int chunkX = span.minChunkX(); chunkX <= span.maxChunkX(); chunkX++)
			{
				for(int chunkZ = span.minChunkZ(); chunkZ <= span.maxChunkZ(); chunkZ++)
				{
					targets.add(new ChunkKey(worldId, chunkX, chunkZ));
				}
			}
			PlacedDoorEndpoint endpoint = runtime.endpoint();
			return new ActiveDoor(
				doorId,
				runtime,
				world,
				plane,
				center,
				new ChunkKey(worldId, endpoint.position().x() >> 4, endpoint.position().z() >> 4),
				targets);
		}

		private boolean matches(RuntimeDoor candidateRuntime, World candidateWorld, DoorwayPlane candidatePlane)
		{
			return runtime == candidateRuntime && world == candidateWorld && plane.equals(candidatePlane);
		}
	}

	private record ChunkSweep(
		ChunkKey key,
		World world,
		ConcurrentHashMap<UUID, ActiveDoor> doors,
		AtomicReference<SweepState> state)
	{
		private ChunkSweep(ChunkKey key, World world)
		{
			this(key, world, new ConcurrentHashMap<>(), new AtomicReference<>(SweepState.IDLE));
		}

		private ChunkSweep
		{
			Objects.requireNonNull(key, "key");
			Objects.requireNonNull(world, "world");
			Objects.requireNonNull(doors, "doors");
			Objects.requireNonNull(state, "state");
		}
	}

	private record Routes(List<DoorVec3> centers, DoorwayPlane clipped)
	{
		private Routes
		{
			Objects.requireNonNull(centers, "centers");
		}
	}

	private enum SweepState
	{
		IDLE,
		SCHEDULED,
		PAUSED,
		RETRY_SCHEDULED
	}

	private static final class BukkitChunkAccess implements ChunkAccess
	{
		@Override
		public boolean loaded(World world, int chunkX, int chunkZ)
		{
			return world.isChunkLoaded(chunkX, chunkZ);
		}

		@Override
		public boolean owned(World world, int chunkX, int chunkZ)
		{
			return WormholesPlatform.isOwnedByCurrentRegion(world, chunkX, chunkZ);
		}

		@Override
		public Entity[] entities(World world, int chunkX, int chunkZ)
		{
			return world.getChunkAt(chunkX, chunkZ).getEntities();
		}
	}
}
