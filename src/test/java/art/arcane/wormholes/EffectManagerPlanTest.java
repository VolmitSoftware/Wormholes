package art.arcane.wormholes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.config.VisualQualityProfile;

public final class EffectManagerPlanTest
{
	@Test
	public void formationDisplayCapsStayBoundedByQuality()
	{
		assertEquals(8, EffectManager.formationDisplayCap(VisualQualityProfile.PERFORMANCE));
		assertEquals(16, EffectManager.formationDisplayCap(VisualQualityProfile.BALANCED));
		assertEquals(18, EffectManager.formationDisplayCap(VisualQualityProfile.AUTO));
		assertEquals(24, EffectManager.formationDisplayCap(VisualQualityProfile.CINEMATIC));
	}

	@Test
	public void openingRingPointsStayBoundedByQuality()
	{
		assertEquals(6, EffectManager.openingRingPoints(VisualQualityProfile.PERFORMANCE));
		assertEquals(10, EffectManager.openingRingPoints(VisualQualityProfile.BALANCED));
		assertEquals(12, EffectManager.openingRingPoints(VisualQualityProfile.AUTO));
		assertEquals(16, EffectManager.openingRingPoints(VisualQualityProfile.CINEMATIC));
	}

	@Test
	public void glassShardVelocityAlwaysMovesOutwardAcrossEveryPortalPlane()
	{
		assertOutward(0, 1, 2);
		assertOutward(1, 0, 2);
		assertOutward(2, 0, 1);
	}

	@Test
	public void closingEffectsScaleWithVisualQuality()
	{
		EffectManager.CloseEffectPlan performance = EffectManager.closeEffectPlan(VisualQualityProfile.PERFORMANCE);
		EffectManager.CloseEffectPlan cinematic = EffectManager.closeEffectPlan(VisualQualityProfile.CINEMATIC);

		assertTrue(performance.branches() < cinematic.branches());
		assertTrue(performance.segments() < cinematic.segments());
		assertTrue(performance.shards() < cinematic.shards());
	}

	@Test
	public void kawooshScalesWithVisualQuality()
	{
		EffectManager.KawooshPlan performance = EffectManager.kawooshPlan(VisualQualityProfile.PERFORMANCE);
		EffectManager.KawooshPlan cinematic = EffectManager.kawooshPlan(VisualQualityProfile.CINEMATIC);

		assertTrue(performance.arms() <= cinematic.arms());
		assertTrue(performance.armPoints() < cinematic.armPoints());
		assertTrue(performance.impactReverse() < cinematic.impactReverse());
		assertTrue(performance.impactEndRod() < cinematic.impactEndRod());
		assertTrue(performance.surgeCount() < cinematic.surgeCount());
	}

	@Test
	public void openingImpactSoundsStayBelowFullVolume()
	{
		EffectManager.OpeningSoundPlan plan = EffectManager.openingSoundPlan();

		assertEquals(0.2f, plan.frameVolume());
		assertEquals(0.225f, plan.portalImpactVolume());
		assertEquals(0.2f, plan.beaconImpactVolume());
		assertEquals(0.075f, plan.sonicBoomVolume());
		assertTrue(plan.frameVolume() < 1.0f);
		assertTrue(plan.portalImpactVolume() < 1.0f);
		assertTrue(plan.beaconImpactVolume() < 1.0f);
		assertTrue(plan.sonicBoomVolume() < 1.0f);
	}

	@Test
	public void crackRadiusStaysOnRectangularPaneEllipse()
	{
		assertEquals(1.0D, EffectManager.ellipseRadius(1.0D, 5.0D, 0.0D), 0.000001D);
		assertEquals(5.0D, EffectManager.ellipseRadius(1.0D, 5.0D, Math.PI / 2.0D), 0.000001D);
	}

	@Test
	public void vortexMarkerMatchesWithinRadius()
	{
		UUID world = UUID.randomUUID();
		assertTrue(EffectManager.vortexMarkerMatches(world, 10.0D, 64.0D, 10.0D, 2000L, 1000L, world, 12.0D, 65.0D, 11.0D));
	}

	@Test
	public void vortexMarkerMissesBeyondRadius()
	{
		UUID world = UUID.randomUUID();
		assertFalse(EffectManager.vortexMarkerMatches(world, 10.0D, 64.0D, 10.0D, 2000L, 1000L, world, 15.0D, 64.0D, 10.0D));
	}

	@Test
	public void vortexMarkerMissesWhenExpired()
	{
		UUID world = UUID.randomUUID();
		assertFalse(EffectManager.vortexMarkerMatches(world, 10.0D, 64.0D, 10.0D, 1000L, 1000L, world, 10.0D, 64.0D, 10.0D));
	}

	@Test
	public void vortexMarkerMissesOnWorldMismatch()
	{
		assertFalse(EffectManager.vortexMarkerMatches(UUID.randomUUID(), 10.0D, 64.0D, 10.0D, 2000L, 1000L, UUID.randomUUID(), 10.0D, 64.0D, 10.0D));
	}

	@Test
	public void orphanSweepReportsChunksItCouldNotReach()
	{
		boolean[] taggedRemoved = new boolean[] { false };
		boolean[] untaggedRemoved = new boolean[] { false };
		Entity tagged = display(UUID.randomUUID(), Set.of("wormholes_fx"), taggedRemoved);
		Entity untagged = display(UUID.randomUUID(), Set.of(), untaggedRemoved);
		World world = sweepWorld(new int[] { 0, 1, 2 }, new Entity[] { tagged, untagged });
		List<Runnable> accepted = new ArrayList<Runnable>();

		int rejected = EffectDisplayRegistry.sweepOrphaned(world, (target, chunkX, chunkZ, task) ->
		{
			if(chunkX == 1)
			{
				return false;
			}
			accepted.add(task);
			return true;
		});

		assertEquals(1, rejected);
		assertEquals(2, accepted.size());

		accepted.get(0).run();
		assertTrue(taggedRemoved[0]);
		assertFalse(untaggedRemoved[0]);
	}

	@Test
	public void orphanSweepReportsNothingWhenEveryChunkIsReached()
	{
		World world = sweepWorld(new int[] { 0, 1 }, new Entity[0]);

		assertEquals(0, EffectDisplayRegistry.sweepOrphaned(world, (target, chunkX, chunkZ, task) -> true));
	}

	@Test
	public void refusedShutdownRemovalIsReportedInsteadOfSilentlyDropped()
	{
		EffectDisplayRegistry registry = new EffectDisplayRegistry();
		UUID displayId = UUID.randomUUID();
		boolean[] removed = new boolean[] { false };
		Entity entity = display(displayId, Set.of("wormholes_fx"), removed);
		List<Boolean> outcomes = new ArrayList<Boolean>();

		registry.drainDisplay(displayId, entity, false, outcomes::add, (display, removal, retired) -> false);

		assertEquals(1, outcomes.size());
		assertFalse(outcomes.get(0).booleanValue());
		assertFalse(removed[0]);
	}

	@Test
	public void retiredShutdownRemovalCompletesExactlyOnce()
	{
		EffectDisplayRegistry registry = new EffectDisplayRegistry();
		UUID displayId = UUID.randomUUID();
		boolean[] removed = new boolean[] { false };
		Entity entity = display(displayId, Set.of("wormholes_fx"), removed);
		List<Boolean> outcomes = new ArrayList<Boolean>();

		registry.drainDisplay(displayId, entity, false, outcomes::add, (display, removal, retired) ->
		{
			retired.run();
			return false;
		});

		assertEquals(1, outcomes.size());
		assertFalse(outcomes.get(0).booleanValue());
		assertFalse(removed[0]);
	}

	@Test
	public void ownedShutdownRemovalDeletesTheDisplayImmediately()
	{
		EffectDisplayRegistry registry = new EffectDisplayRegistry();
		UUID displayId = UUID.randomUUID();
		boolean[] removed = new boolean[] { false };
		Entity entity = display(displayId, Set.of("wormholes_fx"), removed);
		List<Boolean> outcomes = new ArrayList<Boolean>();

		registry.drainDisplay(displayId, entity, true, outcomes::add, (display, removal, retired) ->
		{
			fail("owned displays must not be scheduled");
			return false;
		});

		assertEquals(1, outcomes.size());
		assertTrue(outcomes.get(0).booleanValue());
		assertTrue(removed[0]);
	}

	@Test
	public void refusedOpenPreludeStillDeliversTheKawooshImpact()
	{
		List<Particle> spawned = new ArrayList<Particle>();
		World world = particleWorld(spawned);
		Location center = new Location(world, 8.0D, 64.0D, 8.0D);
		EffectPortalAnimator animator = new EffectPortalAnimator(new EffectDisplayRegistry());

		animator.playOpenPrelude(world, center, 1.0D, 3.0D, 3.0D, () -> true, () -> false);

		assertTrue(spawned.contains(Particle.FLASH));
	}

	private static World particleWorld(List<Particle> spawned)
	{
		InvocationHandler handler = (Object proxy, Method method, Object[] args) -> switch(method.getName())
		{
			case "spawnParticle" ->
			{
				spawned.add((Particle) args[0]);
				yield null;
			}
			case "equals" -> Boolean.valueOf(proxy == args[0]);
			case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
			case "toString" -> "particle-world";
			default -> throw new UnsupportedOperationException(method.getName());
		};
		return (World) Proxy.newProxyInstance(EffectManagerPlanTest.class.getClassLoader(),
			new Class<?>[] { World.class }, handler);
	}

	private static World sweepWorld(int[] chunkX, Entity[] entities)
	{
		Chunk[] chunks = new Chunk[chunkX.length];
		for(int i = 0; i < chunkX.length; i++)
		{
			chunks[i] = chunk(chunkX[i], entities);
		}
		InvocationHandler handler = (Object proxy, Method method, Object[] args) -> switch(method.getName())
		{
			case "getLoadedChunks" -> chunks;
			case "equals" -> Boolean.valueOf(proxy == args[0]);
			case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
			case "toString" -> "sweep-world";
			default -> throw new UnsupportedOperationException(method.getName());
		};
		return (World) Proxy.newProxyInstance(EffectManagerPlanTest.class.getClassLoader(),
			new Class<?>[] { World.class }, handler);
	}

	private static Chunk chunk(int x, Entity[] entities)
	{
		InvocationHandler handler = (Object proxy, Method method, Object[] args) -> switch(method.getName())
		{
			case "getX" -> Integer.valueOf(x);
			case "getZ" -> Integer.valueOf(0);
			case "getEntities" -> entities;
			case "equals" -> Boolean.valueOf(proxy == args[0]);
			case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
			case "toString" -> "chunk" + x;
			default -> throw new UnsupportedOperationException(method.getName());
		};
		return (Chunk) Proxy.newProxyInstance(EffectManagerPlanTest.class.getClassLoader(),
			new Class<?>[] { Chunk.class }, handler);
	}

	private static Entity display(UUID id, Set<String> tags, boolean[] removed)
	{
		InvocationHandler handler = (Object proxy, Method method, Object[] args) -> switch(method.getName())
		{
			case "getScoreboardTags" -> tags;
			case "getUniqueId" -> id;
			case "isValid" -> Boolean.valueOf(!removed[0]);
			case "remove" ->
			{
				removed[0] = true;
				yield null;
			}
			case "equals" -> Boolean.valueOf(proxy == args[0]);
			case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
			case "toString" -> "display" + id;
			default -> throw new UnsupportedOperationException(method.getName());
		};
		return (Entity) Proxy.newProxyInstance(EffectManagerPlanTest.class.getClassLoader(),
			new Class<?>[] { Display.class }, handler);
	}

	private static void assertOutward(int normalAxis, int planeA, int planeB)
	{
		double radialA = 0.6D;
		double radialB = 0.8D;
		double[] positive = EffectManager.outwardShardVelocity(normalAxis, planeA, planeB, radialA, radialB, 1.0D);
		double[] negative = EffectManager.outwardShardVelocity(normalAxis, planeA, planeB, radialA, radialB, -1.0D);
		assertTrue((positive[planeA] * radialA) + (positive[planeB] * radialB) > 0.0D);
		assertTrue((negative[planeA] * radialA) + (negative[planeB] * radialB) > 0.0D);
		assertTrue(positive[normalAxis] > 0.0D);
		assertTrue(negative[normalAxis] < 0.0D);
	}
}
