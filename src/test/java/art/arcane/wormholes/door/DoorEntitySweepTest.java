package art.arcane.wormholes.door;

import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Door;
import org.bukkit.World;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DoorEntitySweepTest
{
	private static final UUID WORLD_ID = new UUID(17L, 19L);
	private static final DoorwayPlane PLANE = new DoorwayPlane(0, 64, 0, BlockFace.NORTH);

	private static Entity stub(Class<?>... interfaces)
	{
		return (Entity) Proxy.newProxyInstance(
			DoorEntitySweepTest.class.getClassLoader(),
			interfaces,
			(proxy, method, arguments) -> switch(method.getName())
			{
				case "toString" -> "stub";
				case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
				case "equals" -> Boolean.valueOf(proxy == arguments[0]);
				default -> null;
			});
	}

	@Test
	void projectilesItemsAndOrbsAreSwept()
	{
		assertTrue(DoorEntitySweep.isSweepable(stub(Arrow.class)));
		assertTrue(DoorEntitySweep.isSweepable(stub(Projectile.class)));
		assertTrue(DoorEntitySweep.isSweepable(stub(Item.class)));
		assertTrue(DoorEntitySweep.isSweepable(stub(ExperienceOrb.class)));
	}

	@Test
	void travelersWithTheirOwnMovementEventsAreNotSwept()
	{
		assertFalse(DoorEntitySweep.isSweepable(stub(Player.class)));
		assertFalse(DoorEntitySweep.isSweepable(stub(LivingEntity.class)));
		assertFalse(DoorEntitySweep.isSweepable(stub(Boat.class)));
	}

	@Test
	void unrelatedEntitiesAreNotSwept()
	{
		assertFalse(DoorEntitySweep.isSweepable(stub(FallingBlock.class)));
		assertFalse(DoorEntitySweep.isSweepable(stub(Entity.class)));
	}

	@Test
	void aLivingEntityIsNeverSweptEvenIfItAlsoLooksLikeAProjectile()
	{
		assertFalse(DoorEntitySweep.isSweepable(stub(LivingEntity.class, Projectile.class)));
	}

	@Test
	void aSweepRunsOnlyWhileItsDoorPresentsALivePortal()
	{
		DoorOpenCycle cycle = new DoorOpenCycle();
		cycle.observe(true);

		assertTrue(DoorEntitySweep.shouldSweep(PLANE, cycle, true));

		cycle.observe(false);

		assertFalse(DoorEntitySweep.shouldSweep(PLANE, cycle, true));
	}

	@Test
	void aSweepStopsWhenItsChunkUnloadsEvenThoughTheDoorStillReadsLive()
	{
		DoorOpenCycle cycle = new DoorOpenCycle();
		cycle.observe(true);

		// nothing in an unloaded chunk ever observes the door shut again, so without
		// this guard the sweep would spin for the rest of the session
		assertFalse(DoorEntitySweep.shouldSweep(PLANE, cycle, false));
	}

	@Test
	void aDoorWithNoCapturedPlaneIsNeverSwept()
	{
		DoorOpenCycle cycle = new DoorOpenCycle();
		cycle.observe(true);

		assertFalse(DoorEntitySweep.shouldSweep(null, cycle, true));
	}

	@Test
	void theReachBoxIsResolvedToTheChunksItActuallyCovers()
	{
		DoorEntitySweep.ChunkSpan middle = DoorEntitySweep.span(new DoorVec3(8.5D, 70.0D, 8.5D));

		assertEquals(0, middle.minChunkX());
		assertEquals(0, middle.minChunkZ());
		assertEquals(0, middle.maxChunkX());
		assertEquals(0, middle.maxChunkZ());

		// a door on a chunk corner reaches into the neighbours a single box query would refuse
		DoorEntitySweep.ChunkSpan corner = DoorEntitySweep.span(new DoorVec3(0.5D, 70.0D, 0.5D));

		assertEquals(-1, corner.minChunkX());
		assertEquals(-1, corner.minChunkZ());
		assertEquals(0, corner.maxChunkX());
		assertEquals(0, corner.maxChunkZ());
	}

	@Test
	void onlyCandidatesInsideTheReachBoxSurviveAWholeChunkOfEntities()
	{
		DoorVec3 center = new DoorVec3(8.5D, 70.0D, 8.5D);

		assertTrue(DoorEntitySweep.withinReach(center, 8.5D, 70.0D, 8.5D));
		assertTrue(DoorEntitySweep.withinReach(center, 12.9D, 72.9D, 4.1D));
		// a chunk is sixteen blocks wide, so its far side sits well outside the box
		assertFalse(DoorEntitySweep.withinReach(center, 15.5D, 70.0D, 8.5D));
		assertFalse(DoorEntitySweep.withinReach(center, 8.5D, 74.0D, 8.5D));
		assertFalse(DoorEntitySweep.withinReach(center, 8.5D, 70.0D, 1.5D));
	}

	@Test
	void oneThousandCornerDoorsShareFourChunkTasksAndOneReadPerChunkTick()
	{
		Harness harness = new Harness();
		harness.access.put(0, 0,
			candidate(Arrow.class, harness.world, 0.5D, 65.0D, 0.5D),
			candidate(Arrow.class, harness.world, 15.5D, 65.0D, 15.5D),
			stub(Player.class),
			stub(Boat.class));

		for(int index = 0; index < 1_000; index++)
		{
			harness.observe(index, 0, 0);
		}

		assertEquals(1_000, harness.sweep.activeSweeps());
		assertEquals(4, harness.sweep.activeChunkSweeps());
		assertEquals(4, harness.scheduler.attempts.get());
		assertEquals(4, harness.scheduler.pending.size());

		harness.scheduler.runGeneration();

		assertEquals(4, harness.access.totalReads());
		assertEquals(1, harness.movements.get());
		assertEquals(4, harness.scheduler.pending.size());
	}

	@Test
	void stoppingTheLastDoorRetiresTheSharedChainBeforeItsPendingTickRuns()
	{
		Harness harness = new Harness();
		ObservedDoor first = harness.observe(1, 8, 8);
		ObservedDoor second = harness.observe(2, 8, 8);

		harness.sweep.stop(first.endpoint().identity().itemId());

		assertEquals(1, harness.sweep.activeSweeps());
		assertEquals(1, harness.sweep.activeChunkSweeps());

		harness.sweep.stop(second.endpoint().identity().itemId());
		harness.scheduler.runGeneration();

		assertEquals(0, harness.sweep.activeSweeps());
		assertEquals(0, harness.sweep.activeChunkSweeps());
		assertEquals(0, harness.access.totalReads());
		assertEquals(0, harness.scheduler.pending.size());
	}

	@Test
	void rejectedInitialChunkDispatchRetriesWithoutLosingTheMembership()
	{
		Harness harness = new Harness();
		harness.scheduler.accept = false;

		harness.observe(1, 8, 8);

		assertEquals(1, harness.scheduler.attempts.get());
		assertEquals(1, harness.sweep.activeSweeps());
		assertEquals(1, harness.sweep.activeChunkSweeps());
		assertEquals(0, harness.scheduler.pending.size());
		assertEquals(1, harness.retries.pending.size());

		harness.scheduler.accept = true;
		harness.retries.runGeneration();

		assertEquals(2, harness.scheduler.attempts.get());
		assertEquals(1, harness.scheduler.pending.size());

		harness.scheduler.runGeneration();

		assertEquals(1, harness.access.totalReads());
		assertEquals(1, harness.scheduler.pending.size());
	}

	@Test
	void stoppingADoorMakesItsQueuedRejectionRetryANoOp()
	{
		Harness harness = new Harness();
		harness.scheduler.accept = false;
		ObservedDoor door = harness.observe(1, 8, 8);

		harness.sweep.stop(door.endpoint().identity().itemId());
		harness.scheduler.accept = true;
		harness.retries.runGeneration();

		assertEquals(1, harness.scheduler.attempts.get());
		assertEquals(0, harness.scheduler.pending.size());
		assertEquals(0, harness.sweep.activeSweeps());
		assertEquals(0, harness.sweep.activeChunkSweeps());
	}

	@Test
	void retiringEveryAcceptedRegionTaskRetriesEachCornerChunkExactlyOnce()
	{
		Harness harness = new Harness();
		harness.observe(1, 0, 0);

		harness.scheduler.retireGenerationTwice();

		assertEquals(1, harness.sweep.activeSweeps());
		assertEquals(4, harness.sweep.activeChunkSweeps());
		assertEquals(0, harness.scheduler.pending.size());
		assertEquals(4, harness.retries.pending.size());
		assertEquals(0, harness.access.totalReads());

		harness.retries.runGeneration();

		assertEquals(4, harness.scheduler.pending.size());
		assertEquals(8, harness.scheduler.attempts.get());
	}

	@Test
	void unloadedTargetPausesUntilItsChunkLoadResumesTheSharedSweep()
	{
		Harness harness = new Harness();
		harness.access.loaded = false;
		harness.observe(1, 8, 8);

		harness.scheduler.runGeneration();
		harness.scheduler.runGeneration();

		assertEquals(1, harness.scheduler.attempts.get());
		assertEquals(0, harness.scheduler.pending.size());
		assertEquals(0, harness.access.totalReads());
		assertEquals(1, harness.sweep.activeSweeps());
		assertEquals(1, harness.sweep.activeChunkSweeps());

		harness.access.loaded = true;
		harness.sweep.chunkLoaded(harness.world, 0, 0);
		harness.sweep.chunkLoaded(harness.world, 0, 0);

		assertEquals(2, harness.scheduler.attempts.get());
		assertEquals(1, harness.scheduler.pending.size());

		harness.scheduler.runGeneration();

		assertEquals(1, harness.access.totalReads());
		assertEquals(1, harness.scheduler.pending.size());
	}

	@Test
	void anInvalidatedRuntimeCleansEverySharedMembershipOnTheNextTick()
	{
		Harness harness = new Harness();
		ObservedDoor door = harness.observe(1, 0, 0);

		door.runtime().invalidate();
		harness.scheduler.runGeneration();

		assertEquals(0, harness.sweep.activeSweeps());
		assertEquals(0, harness.sweep.activeChunkSweeps());
		assertEquals(0, harness.scheduler.pending.size());
		assertEquals(0, harness.access.totalReads());
	}

	@Test
	void closeMakesAlreadyAcceptedTicksNoOps()
	{
		Harness harness = new Harness();
		harness.observe(1, 8, 8);

		harness.sweep.close();
		harness.scheduler.runGeneration();

		assertEquals(0, harness.sweep.activeSweeps());
		assertEquals(0, harness.sweep.activeChunkSweeps());
		assertEquals(0, harness.scheduler.pending.size());
		assertEquals(0, harness.access.totalReads());
	}

	@Test
	void anUnownedSweepClipsOnceWithoutReadingTheChunkEntityArray()
	{
		Harness harness = new Harness();
		harness.access.owned = false;
		harness.observe(1, 8, 8);

		harness.scheduler.runGeneration();
		harness.scheduler.runGeneration();

		assertEquals(1, harness.warnings.get());
		assertEquals(0, harness.access.totalReads());
		assertEquals(1, harness.scheduler.pending.size());
	}

	private static Entity candidate(
		Class<? extends Entity> type,
		World world,
		double x,
		double y,
		double z)
	{
		return (Entity) Proxy.newProxyInstance(
			DoorEntitySweepTest.class.getClassLoader(),
			new Class<?>[]{type},
			(proxy, method, arguments) -> switch(method.getName())
			{
				case "isDead" -> Boolean.FALSE;
				case "isValid" -> Boolean.TRUE;
				case "getLocation" -> new org.bukkit.Location(world, x, y, z);
				case "getVelocity" -> new Vector(0.0D, 0.0D, 1.0D);
				case "toString" -> "candidate";
				case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
				case "equals" -> Boolean.valueOf(proxy == arguments[0]);
				default -> null;
			});
	}

	private record ObservedDoor(PlacedDoorEndpoint endpoint, RuntimeDoor runtime)
	{
	}

	private static final class Harness
	{
		private final AtomicBoolean closed;
		private final AtomicInteger movements;
		private final AtomicInteger warnings;
		private final RecordingScheduler scheduler;
		private final RecordingRetryScheduler retries;
		private final RecordingChunkAccess access;
		private final World world;
		private final DoorEntitySweep sweep;

		private Harness()
		{
			closed = new AtomicBoolean();
			movements = new AtomicInteger();
			warnings = new AtomicInteger();
			scheduler = new RecordingScheduler();
			retries = new RecordingRetryScheduler();
			access = new RecordingChunkAccess();
			world = world();
			Logger logger = Logger.getLogger(DoorEntitySweepTest.class.getName());
			logger.setLevel(Level.OFF);
			sweep = new DoorEntitySweep(new DoorEntitySweep.Dependencies(
				logger,
				closed::get,
				scheduler,
				retries,
				access,
				plane -> warnings.incrementAndGet()));
			sweep.attach((traveler, from, to) -> movements.incrementAndGet());
		}

		private ObservedDoor observe(int index, int blockX, int blockZ)
		{
			UUID doorId = new UUID(23L, index + 1L);
			PlacedDoorEndpoint endpoint = new PlacedDoorEndpoint(
				new DoorPosition(WORLD_ID, "minecraft:overworld", blockX, 64, blockZ),
				DoorItemIdentity.publicDoor(doorId));
			DoorwayPlane plane = new DoorwayPlane(blockX, 64, blockZ, BlockFace.NORTH);
			RuntimeDoor runtime = new RuntimeDoor(endpoint);
			runtime.update(new VanillaDoorSnapshot(WORLD_ID, plane, Door.Hinge.LEFT, true, false));
			sweep.observe(endpoint, runtime, world, true);
			return new ObservedDoor(endpoint, runtime);
		}

		private static World world()
		{
			return (World) Proxy.newProxyInstance(
				World.class.getClassLoader(),
				new Class<?>[]{World.class},
				(proxy, method, arguments) -> switch(method.getName())
				{
					case "getUID" -> WORLD_ID;
					case "toString" -> "world";
					case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
					case "equals" -> Boolean.valueOf(proxy == arguments[0]);
					default -> null;
				});
		}
	}

	private static final class RecordingRetryScheduler implements DoorEntitySweep.RetryDispatch
	{
		private final List<Runnable> pending = new ArrayList<>();

		@Override
		public boolean run(Runnable task, long delayTicks)
		{
			pending.add(task);
			return true;
		}

		private void runGeneration()
		{
			List<Runnable> generation = List.copyOf(pending);
			pending.clear();
			for(Runnable task : generation)
			{
				task.run();
			}
		}
	}

	private static final class RecordingScheduler implements DoorEntitySweep.RegionDispatch
	{
		private final AtomicInteger attempts = new AtomicInteger();
		private final List<Scheduled> pending = new ArrayList<>();
		private boolean accept = true;

		@Override
		public boolean run(
			World world,
			int chunkX,
			int chunkZ,
			Runnable task,
			Runnable retired,
			long delayTicks)
		{
			attempts.incrementAndGet();
			if(!accept)
			{
				return false;
			}
			pending.add(new Scheduled(task, retired));
			return true;
		}

		private void runGeneration()
		{
			List<Scheduled> generation = List.copyOf(pending);
			pending.clear();
			for(Scheduled scheduled : generation)
			{
				scheduled.task().run();
			}
		}

		private void retireGenerationTwice()
		{
			List<Scheduled> generation = List.copyOf(pending);
			pending.clear();
			for(Scheduled scheduled : generation)
			{
				scheduled.retired().run();
				scheduled.retired().run();
			}
		}
	}

	private static final class RecordingChunkAccess implements DoorEntitySweep.ChunkAccess
	{
		private final Map<ChunkCoordinate, Entity[]> candidates = new HashMap<>();
		private final Map<ChunkCoordinate, AtomicInteger> reads = new HashMap<>();
		private boolean loaded = true;
		private boolean owned = true;

		private void put(int chunkX, int chunkZ, Entity... entities)
		{
			candidates.put(new ChunkCoordinate(chunkX, chunkZ), entities);
		}

		private int totalReads()
		{
			int total = 0;
			for(AtomicInteger count : reads.values())
			{
				total += count.get();
			}
			return total;
		}

		@Override
		public boolean loaded(World world, int chunkX, int chunkZ)
		{
			return loaded;
		}

		@Override
		public boolean owned(World world, int chunkX, int chunkZ)
		{
			return owned;
		}

		@Override
		public Entity[] entities(World world, int chunkX, int chunkZ)
		{
			ChunkCoordinate key = new ChunkCoordinate(chunkX, chunkZ);
			reads.computeIfAbsent(key, ignored -> new AtomicInteger()).incrementAndGet();
			return candidates.getOrDefault(key, new Entity[0]);
		}
	}

	private record Scheduled(Runnable task, Runnable retired)
	{
	}

	private record ChunkCoordinate(int chunkX, int chunkZ)
	{
	}
}
