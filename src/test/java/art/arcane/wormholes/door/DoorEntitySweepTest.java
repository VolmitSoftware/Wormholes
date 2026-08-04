package art.arcane.wormholes.door;

import org.bukkit.block.BlockFace;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DoorEntitySweepTest
{
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
}
