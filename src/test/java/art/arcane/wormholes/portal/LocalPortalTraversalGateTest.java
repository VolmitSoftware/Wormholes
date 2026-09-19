package art.arcane.wormholes.portal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.util.AxisAlignedBB;

public final class LocalPortalTraversalGateTest
{
	@Test
	public void reentryRemainsLatchedWhileAnyPartOfEntityOverlapsPortal()
	{
		World world = LocalPortalTestSupport.world("reentry-overlap");
		LocalPortal portal = LocalPortalTestSupport.portal(world, PortalType.PORTAL);
		AxisAlignedBB area = portal.getArea();
		for(int axis = 0; axis < 3; axis++)
		{
			for(boolean positive : new boolean[] {false, true})
			{
				BoundingBox bounds = boundsAtFace(area, axis, positive, -0.01D);
				assertTrue(portal.traversal().isOccupyingPortal(entity(world, bounds)),
					"Entity still overlaps axis " + axis + " positive=" + positive);
			}
		}
	}

	@Test
	public void reentryReleasesAtPortalBoundaryWithoutExtraBlockClearance()
	{
		World world = LocalPortalTestSupport.world("reentry-clearance");
		LocalPortal portal = LocalPortalTestSupport.portal(world, PortalType.PORTAL);
		AxisAlignedBB area = portal.getArea();
		for(int axis = 0; axis < 3; axis++)
		{
			for(boolean positive : new boolean[] {false, true})
			{
				for(double clearance : new double[] {0.0D, 0.01D, 1.0D})
				{
					BoundingBox bounds = boundsAtFace(area, axis, positive, clearance);
					assertFalse(portal.traversal().isOccupyingPortal(entity(world, bounds)),
						"Entity cleared axis " + axis + " positive=" + positive + " by " + clearance);
				}
			}
		}
	}

	@Test
	public void overlappingCoordinatesInAnotherWorldDoNotHoldReentryLatch()
	{
		World world = LocalPortalTestSupport.world("reentry-origin");
		World otherWorld = LocalPortalTestSupport.world("reentry-other");
		LocalPortal portal = LocalPortalTestSupport.portal(world, PortalType.PORTAL);
		BoundingBox bounds = new BoundingBox(0.2D, 64.0D, 1.0D, 0.8D, 65.8D, 1.6D);
		assertTrue(portal.traversal().isOccupyingPortal(entity(world, bounds)));
		assertFalse(portal.traversal().isOccupyingPortal(entity(otherWorld, bounds)));
	}

	@Test
	public void rejectedReentryLatchExpiresQuicklyWhileArrivalLatchPersists()
	{
		long stamp = 1_000_000L;
		assertFalse(LocalPortalTransitRegistry.reentryLatchExpired(true, stamp, stamp + 2_000L));
		assertTrue(LocalPortalTransitRegistry.reentryLatchExpired(true, stamp, stamp + 2_500L));
		assertFalse(LocalPortalTransitRegistry.reentryLatchExpired(false, stamp, stamp + 2_500L));
		assertFalse(LocalPortalTransitRegistry.reentryLatchExpired(false, stamp, stamp + 59_999L));
		assertTrue(LocalPortalTransitRegistry.reentryLatchExpired(false, stamp, stamp + 60_000L));
	}

	@Test
	public void waitingLatchReleasesOutsidePortalAfterGraceAndArmedLatchReleasesImmediately()
	{
		long stamp = 5_000_000L;
		assertTrue(LocalPortalTransitRegistry.shouldReleaseReentryLatchOutsidePortal(true, stamp, stamp));
		assertFalse(LocalPortalTransitRegistry.shouldReleaseReentryLatchOutsidePortal(false, stamp, stamp + 1_999L));
		assertTrue(LocalPortalTransitRegistry.shouldReleaseReentryLatchOutsidePortal(false, stamp, stamp + 2_000L));
	}

	@Test
	public void settledArrivalLatchReleasesImmediatelyOutsideAndKeepsArrivalLifetimeInside()
	{
		UUID entityId = UUID.randomUUID();
		UUID portalId = UUID.randomUUID();
		try
		{
			LocalPortalTransitRegistry.latchArrivedReentry(entityId, portalId);
			LocalPortalTransitRegistry.ReentryLatch latch = LocalPortalTransitRegistry.activeReentryLatch(entityId, System.currentTimeMillis());
			assertNotNull(latch);
			assertTrue(latch.armed());
			assertFalse(latch.rejected());
			assertTrue(LocalPortalTransitRegistry.shouldReleaseReentryLatchOutsidePortal(latch.armed(), latch.stampMillis(), latch.stampMillis()));
			assertFalse(LocalPortalTransitRegistry.reentryLatchExpired(latch.rejected(), latch.stampMillis(), latch.stampMillis() + 2_500L));
			assertTrue(LocalPortalTransitRegistry.reentryLatchExpired(latch.rejected(), latch.stampMillis(), latch.stampMillis() + 60_000L));
		}
		finally
		{
			LocalPortalTransitRegistry.clearReentryLatch(entityId);
		}
	}

	@Test
	public void teleportInFlightTokenExpiresAfterTtlAndCanBeReacquired()
	{
		UUID entityId = UUID.nameUUIDFromBytes("in-flight-ttl".getBytes());
		long start = 10_000_000L;
		assertTrue(LocalPortal.markTeleportInFlight(entityId, start));
		assertFalse(LocalPortal.markTeleportInFlight(entityId, start + 29_999L));
		assertTrue(LocalPortal.isTeleportInFlight(entityId, start + 29_999L));
		assertTrue(LocalPortal.markTeleportInFlight(entityId, start + 30_000L));
		assertTrue(LocalPortal.clearTeleportInFlight(entityId));
		assertFalse(LocalPortal.isTeleportInFlight(entityId, start));
	}

	@Test
	public void staleInFlightTokenIsNotTreatedAsActive()
	{
		UUID entityId = UUID.nameUUIDFromBytes("in-flight-stale".getBytes());
		long start = 20_000_000L;
		assertTrue(LocalPortal.markTeleportInFlight(entityId, start));
		assertFalse(LocalPortal.isTeleportInFlight(entityId, start + 30_000L));
		assertFalse(LocalPortal.isTeleportInFlight(entityId, start + 30_001L));
		assertFalse(LocalPortal.clearTeleportInFlight(entityId));
	}

	@Test
	public void staleTokenCanStillBeClearedExplicitly()
	{
		UUID staleId = UUID.nameUUIDFromBytes("in-flight-explicit-clear".getBytes());
		long start = 30_000_000L;
		assertTrue(LocalPortal.markTeleportInFlight(staleId, start));
		assertTrue(LocalPortal.clearTeleportInFlight(staleId));
		assertFalse(LocalPortal.clearTeleportInFlight(staleId));
	}

	@Test
	public void inFlightDepartureIsSkippedUntilItsTerminalClearsTheToken()
	{
		UUID entityId = UUID.nameUUIDFromBytes("in-flight-skip".getBytes());
		long now = 40_000_000L;
		assertFalse(LocalPortal.isTeleportInFlight(entityId, now));
		assertTrue(LocalPortal.markTeleportInFlight(entityId, now));
		assertTrue(LocalPortal.isTeleportInFlight(entityId, now + 250L));
		LocalPortal.clearTeleportInFlight(entityId);
		assertFalse(LocalPortal.isTeleportInFlight(entityId, now + 300L));
	}

	private static BoundingBox boundsAtFace(AxisAlignedBB area, int axis, boolean positive, double clearance)
	{
		double[] minimum = {area.getXa(), area.getYa(), area.getZa()};
		double[] maximum = {area.getXb(), area.getYb(), area.getZb()};
		double[] entityMinimum = new double[3];
		double[] entityMaximum = new double[3];
		for(int coordinate = 0; coordinate < 3; coordinate++)
		{
			double center = (minimum[coordinate] + maximum[coordinate]) / 2.0D;
			entityMinimum[coordinate] = center - 0.3D;
			entityMaximum[coordinate] = center + 0.3D;
		}
		if(positive)
		{
			entityMinimum[axis] = maximum[axis] + clearance;
			entityMaximum[axis] = entityMinimum[axis] + 0.6D;
		}
		else
		{
			entityMaximum[axis] = minimum[axis] - clearance;
			entityMinimum[axis] = entityMaximum[axis] - 0.6D;
		}
		return new BoundingBox(entityMinimum[0], entityMinimum[1], entityMinimum[2],
			entityMaximum[0], entityMaximum[1], entityMaximum[2]);
	}

	private static Entity entity(World world, BoundingBox bounds)
	{
		Location location = new Location(world, bounds.getCenterX(), bounds.getMinY(), bounds.getCenterZ());
		return (Entity) Proxy.newProxyInstance(LocalPortalTraversalGateTest.class.getClassLoader(), new Class<?>[] {Entity.class},
			(proxy, method, arguments) -> switch(method.getName())
			{
				case "getLocation" -> location.clone();
				case "getWorld" -> world;
				case "getBoundingBox" -> bounds.clone();
				case "equals" -> Boolean.valueOf(proxy == arguments[0]);
				case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
				default -> LocalPortalTestSupport.defaultValue(method.getReturnType());
			});
	}
}
