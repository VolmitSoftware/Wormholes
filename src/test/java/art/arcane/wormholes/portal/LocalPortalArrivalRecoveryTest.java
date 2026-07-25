package art.arcane.wormholes.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.portal.LocalPortalTestSupport.FakeEntity;

public final class LocalPortalArrivalRecoveryTest
{
	@Test
	public void remoteArrivalBounceCompletesEvenWhenTheReturnTeleportFails()
	{
		World world = LocalPortalTestSupport.world("remote-arrival-bounce");
		LocalPortal portal = LocalPortalTestSupport.portal(world, PortalType.PORTAL);
		FakeEntity traveler = FakeEntity.player("remote-arrival", new Location(world, 0.5D, 65.0D, 1.0D));
		Traversive traversive = LocalPortalTestSupport.traversive(portal, traveler.entity(), new Vector(0.5D, 65.0D, 1.0D));
		portal.getStructure().setWorld(null);
		Vector before = traveler.velocity();

		new LocalPortalTraversal(portal, LocalPortalTestSupport.failedTeleportRuntime(null)).rejectRemoteArrival(traveler.entity(), traversive);

		assertTrue(LocalPortal.isTeleportCoolingDown(traveler.id(), System.currentTimeMillis()),
				"a rejected remote arrival must stamp its cooldown even when the bounce teleport fails");
		assertNotEquals(before, traveler.velocity(),
				"a rejected remote arrival must still be pushed back out of the portal");
	}

	@Test
	public void remoteArrivalBounceCompletesWhenTheReturnTeleportThrows()
	{
		World world = LocalPortalTestSupport.world("remote-arrival-throw");
		LocalPortal portal = LocalPortalTestSupport.portal(world, PortalType.PORTAL);
		FakeEntity traveler = FakeEntity.player("remote-arrival-throw", new Location(world, 0.5D, 65.0D, 1.0D));
		Traversive traversive = LocalPortalTestSupport.traversive(portal, traveler.entity(), new Vector(0.5D, 65.0D, 1.0D));
		portal.getStructure().setWorld(null);
		Vector before = traveler.velocity();

		new LocalPortalTraversal(portal, LocalPortalTestSupport.failedTeleportRuntime(new IllegalStateException("teleport refused")))
				.rejectRemoteArrival(traveler.entity(), traversive);

		assertTrue(LocalPortal.isTeleportCoolingDown(traveler.id(), System.currentTimeMillis()),
				"a rejected remote arrival must stamp its cooldown even when the bounce teleport throws");
		assertNotEquals(before, traveler.velocity());
	}

	@Test
	public void arrivalKeepsItsInFlightClaimUntilTheCooldownAndLatchAreStamped()
	{
		World world = LocalPortalTestSupport.world("arrival-in-flight");
		LocalPortal portal = LocalPortalTestSupport.portal(world, PortalType.PORTAL);
		FakeEntity traveler = FakeEntity.entity("arrival-in-flight", new Location(world, 0.5D, 65.0D, 1.0D));
		Traversive traversive = LocalPortalTestSupport.traversive(portal, traveler.entity(), new Vector(0.5D, 65.0D, 1.0D));
		portal.getStructure().setWorld(null);
		List<Runnable> pending = new ArrayList<Runnable>();

		new LocalPortalTraversal(portal, LocalPortalTestSupport.deferringRuntime(pending, true)).receive(traversive);

		assertTrue(LocalPortal.isTeleportInFlight(traveler.id(), System.currentTimeMillis()),
				"the arrival must stay in flight until the cooldown and re-entry latch are stamped");
		assertFalse(LocalPortal.isTeleportCoolingDown(traveler.id(), System.currentTimeMillis()));
		assertEquals(1, pending.size());
		pending.forEach(Runnable::run);
		assertFalse(LocalPortal.isTeleportInFlight(traveler.id(), System.currentTimeMillis()));
		assertTrue(LocalPortal.isTeleportCoolingDown(traveler.id(), System.currentTimeMillis()));
		assertTrue(LocalPortal.isReentryLatched(traveler.id()));
		LocalPortal.clearReentryLatch(traveler.id());
	}

	@Test
	public void arrivalThatCannotBeSettledOnTheEntitySchedulerIsStillRecorded()
	{
		World world = LocalPortalTestSupport.world("arrival-settle-rejected");
		LocalPortal portal = LocalPortalTestSupport.portal(world, PortalType.PORTAL);
		FakeEntity traveler = FakeEntity.entity("arrival-settle-rejected", new Location(world, 0.5D, 65.0D, 1.0D));
		Traversive traversive = LocalPortalTestSupport.traversive(portal, traveler.entity(), new Vector(0.5D, 65.0D, 1.0D));
		portal.getStructure().setWorld(null);

		new LocalPortalTraversal(portal, LocalPortalTestSupport.teleportingRejectingRuntime()).receive(traversive);

		assertFalse(LocalPortal.isTeleportInFlight(traveler.id(), System.currentTimeMillis()),
				"a settle the scheduler refused must not strand the arrival in flight");
		assertTrue(LocalPortal.isTeleportCoolingDown(traveler.id(), System.currentTimeMillis()),
				"a settle the scheduler refused must still stamp the arrival cooldown");
		assertTrue(LocalPortal.isReentryLatched(traveler.id()),
				"a settle the scheduler refused must still latch re-entry");
		LocalPortal.clearReentryLatch(traveler.id());
	}
}
