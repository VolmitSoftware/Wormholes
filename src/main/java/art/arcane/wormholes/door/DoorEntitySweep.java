package art.arcane.wormholes.door;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.Wormholes;
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

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;

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

	interface MovementSink
	{
		void accept(Entity traveler, Location from, Location to);
	}

	private final Plugin plugin;
	private final BooleanSupplier closed;
	private final ConcurrentHashMap<UUID, Long> active;
	private final AtomicLong sequence;
	private final AtomicBoolean warnedClip;

	private volatile MovementSink sink;

	DoorEntitySweep(Plugin plugin, BooleanSupplier closed)
	{
		this.plugin = Objects.requireNonNull(plugin, "plugin");
		this.closed = Objects.requireNonNull(closed, "closed");
		active = new ConcurrentHashMap<>();
		sequence = new AtomicLong();
		warnedClip = new AtomicBoolean();
	}

	void attach(MovementSink movementSink)
	{
		sink = Objects.requireNonNull(movementSink, "movementSink");
	}

	int activeSweeps()
	{
		return active.size();
	}

	/**
	 * Starts or stops the sweep for one door from a freshly observed portal-active
	 * state, which can be the shut surface rather than the open aperture.
	 */
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
		if(active.remove(Objects.requireNonNull(doorId, "doorId")) != null)
		{
			Wormholes.v("[door] SWEEP stop door=" + doorId + " active=" + active.size());
		}
	}

	@Override
	public void close()
	{
		active.clear();
	}

	private void start(PlacedDoorEndpoint endpoint, RuntimeDoor runtime, World world)
	{
		if(sink == null || world == null || closed.getAsBoolean() || runtime.plane() == null)
		{
			return;
		}
		UUID doorId = endpoint.identity().itemId();
		if(active.containsKey(doorId))
		{
			return;
		}
		long token = sequence.incrementAndGet();
		if(active.putIfAbsent(doorId, Long.valueOf(token)) != null)
		{
			return;
		}
		int chunkX = endpoint.position().x() >> 4;
		int chunkZ = endpoint.position().z() >> 4;
		Runnable[] holder = new Runnable[1];
		holder[0] = () ->
		{
			if(!shouldContinue(doorId, token, runtime, world, chunkX, chunkZ))
			{
				active.remove(doorId, Long.valueOf(token));
				return;
			}
			sweepOnce(runtime, world);
			if(!FoliaScheduler.runRegion(plugin, world, chunkX, chunkZ, holder[0], SWEEP_PERIOD_TICKS))
			{
				active.remove(doorId, Long.valueOf(token));
			}
		};
		if(!FoliaScheduler.runRegion(plugin, world, chunkX, chunkZ, holder[0], SWEEP_PERIOD_TICKS))
		{
			active.remove(doorId, Long.valueOf(token));
			return;
		}
		Wormholes.v("[door] SWEEP start door=" + doorId + " active=" + active.size());
	}

	private boolean shouldContinue(UUID doorId, long token, RuntimeDoor runtime, World world, int chunkX, int chunkZ)
	{
		Long current = active.get(doorId);
		return sink != null
			&& !closed.getAsBoolean()
			&& current != null
			&& current.longValue() == token
			&& shouldSweep(runtime.plane(), runtime.cycle(), world.isChunkLoaded(chunkX, chunkZ));
	}

	/**
	 * Whether a sweep is still worth running.
	 *
	 * <p>An unloaded chunk holds no entities to feed and fires no block event, so
	 * nothing there would ever observe the door shut again: a sweep left running on
	 * one spins for the rest of the session.</p>
	 */
	static boolean shouldSweep(DoorwayPlane plane, DoorOpenCycle cycle, boolean chunkLoaded)
	{
		return plane != null && cycle != null && chunkLoaded && cycle.portalActive();
	}

	/**
	 * Sweeps chunk by chunk rather than through one wide box.
	 *
	 * <p>A 4.5-block reach around a door near a chunk border spills into its
	 * neighbours, which on Folia may belong to another region. One box query over
	 * all of them is refused outright, silently discarding every tick for as long
	 * as the door stands open; per-chunk queries keep the owned chunks working and
	 * clip only what is genuinely out of reach.</p>
	 */
	private void sweepOnce(RuntimeDoor runtime, World world)
	{
		DoorwayPlane plane = runtime.plane();
		MovementSink movementSink = sink;
		if(plane == null || movementSink == null)
		{
			return;
		}
		DoorVec3 center = plane.center();
		ChunkSpan span = span(center);
		boolean clipped = false;
		for(int chunkX = span.minChunkX(); chunkX <= span.maxChunkX(); chunkX++)
		{
			for(int chunkZ = span.minChunkZ(); chunkZ <= span.maxChunkZ(); chunkZ++)
			{
				clipped |= !sweepChunk(movementSink, world, center, chunkX, chunkZ);
			}
		}
		if(clipped)
		{
			warnClip(plane);
		}
	}

	/** Returns false when this chunk could not be read from the door's own region. */
	private boolean sweepChunk(MovementSink movementSink, World world, DoorVec3 center, int chunkX, int chunkZ)
	{
		try
		{
			if(!world.isChunkLoaded(chunkX, chunkZ))
			{
				return true;
			}
			if(!WormholesPlatform.isOwnedByCurrentRegion(world, chunkX, chunkZ))
			{
				return false;
			}
			for(Entity candidate : world.getChunkAt(chunkX, chunkZ).getEntities())
			{
				if(isSweepable(candidate))
				{
					feed(movementSink, center, candidate);
				}
			}
			return true;
		}
		catch(Throwable ex)
		{
			plugin.getLogger().log(Level.FINE, "Could not sweep a dimensional-door chunk for objects", ex);
			// an exception is not a region-boundary clip; do not misdirect the clip warning
			return true;
		}
	}

	private void warnClip(DoorwayPlane plane)
	{
		if(warnedClip.compareAndSet(false, true))
		{
			Wormholes.w("[door] SWEEP clipped at a region boundary near "
				+ plane.blockX() + "," + plane.blockY() + "," + plane.blockZ()
				+ "; objects approaching from the far side are not swept");
		}
	}

	private void feed(MovementSink movementSink, DoorVec3 center, Entity candidate)
	{
		try
		{
			if(candidate.isDead() || !candidate.isValid())
			{
				return;
			}
			Location to = candidate.getLocation();
			if(to.getWorld() == null || !withinReach(center, to.getX(), to.getY(), to.getZ()))
			{
				return;
			}
			Vector velocity = candidate.getVelocity();
			Location from = to.clone().subtract(velocity);
			movementSink.accept(candidate, from, to);
		}
		catch(Throwable ex)
		{
			plugin.getLogger().log(Level.FINE, "Could not evaluate a swept dimensional-door object", ex);
		}
	}

	/** The chunk rectangle the reach box covers, so every query is aimed at a known chunk. */
	static ChunkSpan span(DoorVec3 center)
	{
		return new ChunkSpan(
			chunkOf(center.x() - HORIZONTAL_REACH),
			chunkOf(center.z() - HORIZONTAL_REACH),
			chunkOf(center.x() + HORIZONTAL_REACH),
			chunkOf(center.z() + HORIZONTAL_REACH));
	}

	/** A chunk holds far more than the reach box, so each candidate is boxed again. */
	static boolean withinReach(DoorVec3 center, double x, double y, double z)
	{
		return Math.abs(x - center.x()) <= HORIZONTAL_REACH
			&& Math.abs(y - center.y()) <= VERTICAL_REACH
			&& Math.abs(z - center.z()) <= HORIZONTAL_REACH;
	}

	private static int chunkOf(double blockCoordinate)
	{
		return Math.floorDiv((int) Math.floor(blockCoordinate), 16);
	}

	record ChunkSpan(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ)
	{
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
}
