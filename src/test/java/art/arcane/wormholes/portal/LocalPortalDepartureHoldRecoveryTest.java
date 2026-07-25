package art.arcane.wormholes.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.portal.LocalPortalTestSupport.FakeEntity;
import art.arcane.wormholes.portal.rtp.RtpSettings;
import art.arcane.wormholes.service.WormholesTelemetry;

public final class LocalPortalDepartureHoldRecoveryTest
{
	@Test
	public void rtpHoldThatCannotBeScheduledAtAllBouncesTheTraveler()
	{
		World world = LocalPortalTestSupport.world("rtp-hold-entry");
		LocalPortal portal = rtpPortal(world);
		FakeEntity traveler = FakeEntity.entity("rtp-entry", anchor(world));
		Traversive traversive = LocalPortalTestSupport.traversive(portal, traveler.entity(), anchorVector());
		long now = System.currentTimeMillis();
		assertTrue(LocalPortal.markTeleportInFlight(traveler.id(), now));
		long before = failures("TRAVERSAL_RTP_HOLD_FAILED");

		new LocalPortalDepartureHold(portal, LocalPortalTestSupport.rejectingRuntime())
				.startRtpTraversalHold(traveler.entity(), traversive);

		assertEquals(before + 1L, failures("TRAVERSAL_RTP_HOLD_FAILED"),
				"a random-teleport hold that failed terminally must increment the failure counter");
		assertFalse(LocalPortal.isTeleportInFlight(traveler.id(), System.currentTimeMillis()),
				"a hold that never started must release the traversal claim");
		assertTrue(LocalPortal.isTeleportCoolingDown(traveler.id(), System.currentTimeMillis()),
				"a hold that never started must run the rejection funnel instead of dropping the traveler");
		assertTrue(LocalPortal.isReentryLatched(traveler.id()),
				"the bounced traveler must be latched so the portal does not immediately recapture them");
		LocalPortal.clearReentryLatch(traveler.id());
	}

	@Test
	public void rtpHoldThatLosesItsSchedulerMidLoopBouncesTheTraveler()
	{
		World world = LocalPortalTestSupport.world("rtp-hold-loop");
		LocalPortal portal = rtpPortal(world);
		FakeEntity traveler = FakeEntity.entity("rtp-loop", anchor(world));
		Traversive traversive = LocalPortalTestSupport.traversive(portal, traveler.entity(), anchorVector());
		long now = System.currentTimeMillis();
		assertTrue(LocalPortal.markTeleportInFlight(traveler.id(), now));

		new LocalPortalDepartureHold(portal, LocalPortalTestSupport.runOnceThenRejectingRuntime())
				.startRtpTraversalHold(traveler.entity(), traversive);

		assertFalse(LocalPortal.isTeleportInFlight(traveler.id(), System.currentTimeMillis()));
		assertTrue(LocalPortal.isTeleportCoolingDown(traveler.id(), System.currentTimeMillis()),
				"a hold that is dropped mid-loop must bounce the traveler it pinned");
		assertTrue(LocalPortal.isReentryLatched(traveler.id()));
		LocalPortal.clearReentryLatch(traveler.id());
	}

	@Test
	public void rtpHoldWithoutASourceWorldBouncesTheTravelerInsteadOfReturningSilently()
	{
		World world = LocalPortalTestSupport.world("rtp-hold-world");
		LocalPortal portal = rtpPortal(world);
		FakeEntity traveler = FakeEntity.entity("rtp-world", anchor(world));
		Traversive traversive = LocalPortalTestSupport.traversive(portal, traveler.entity(), anchorVector());
		portal.getStructure().setWorld(null);
		long now = System.currentTimeMillis();
		assertTrue(LocalPortal.markTeleportInFlight(traveler.id(), now));

		new LocalPortalDepartureHold(portal, LocalPortalTestSupport.rejectingRuntime())
				.startRtpTraversalHold(traveler.entity(), traversive);

		assertFalse(LocalPortal.isTeleportInFlight(traveler.id(), System.currentTimeMillis()),
				"a hold with no source world must release the traversal claim");
		assertTrue(LocalPortal.isTeleportCoolingDown(traveler.id(), System.currentTimeMillis()),
				"a hold with no source world must run the rejection funnel instead of dropping the traveler");
		LocalPortal.clearReentryLatch(traveler.id());
		LocalPortal.clearTeleportCooldown(traveler.id());
	}

	@Test
	public void crossServerDepartureHoldThatCannotBeScheduledReleasesTheHandoff()
	{
		World world = LocalPortalTestSupport.world("transfer-hold-entry");
		LocalPortal portal = LocalPortalTestSupport.portal(world, PortalType.PORTAL);
		FakeEntity traveler = FakeEntity.player("transfer-entry", anchor(world));
		Traversive traversive = LocalPortalTestSupport.traversive(portal, traveler.entity(), anchorVector());
		long now = System.currentTimeMillis();
		assertTrue(LocalPortal.markTeleportInFlight(traveler.id(), now));
		long before = failures("TRAVERSAL_DEPARTURE_HOLD_FAILED");

		new LocalPortalDepartureHold(portal, LocalPortalTestSupport.rejectingRuntime())
				.startPlayerDepartureHold(traveler.player(), traversive);

		assertEquals(before + 1L, failures("TRAVERSAL_DEPARTURE_HOLD_FAILED"),
				"a cross-server departure hold that failed terminally must increment the failure counter");
		assertFalse(LocalPortal.isTeleportInFlight(traveler.id(), System.currentTimeMillis()),
				"a departure hold that never started must release the cross-server claim");
	}

	@Test
	public void crossServerDepartureHoldThatLosesItsSchedulerMidLoopReleasesTheHandoff()
	{
		World world = LocalPortalTestSupport.world("transfer-hold-loop");
		LocalPortal portal = LocalPortalTestSupport.portal(world, PortalType.PORTAL);
		FakeEntity traveler = FakeEntity.player("transfer-loop", anchor(world));
		Traversive traversive = LocalPortalTestSupport.traversive(portal, traveler.entity(), anchorVector());
		long now = System.currentTimeMillis();
		assertTrue(LocalPortal.markTeleportInFlight(traveler.id(), now));

		new LocalPortalDepartureHold(portal, LocalPortalTestSupport.runOnceThenRejectingRuntime())
				.startPlayerDepartureHold(traveler.player(), traversive);

		assertFalse(LocalPortal.isTeleportInFlight(traveler.id(), System.currentTimeMillis()),
				"a departure hold dropped mid-loop must release the cross-server claim");
	}

	@Test
	public void crossServerDepartureHoldWithoutASourceWorldReleasesTheHandoff()
	{
		World world = LocalPortalTestSupport.world("transfer-hold-world");
		LocalPortal portal = LocalPortalTestSupport.portal(world, PortalType.PORTAL);
		FakeEntity traveler = FakeEntity.player("transfer-world", anchor(world));
		Traversive traversive = LocalPortalTestSupport.traversive(portal, traveler.entity(), anchorVector());
		portal.getStructure().setWorld(null);
		long now = System.currentTimeMillis();
		assertTrue(LocalPortal.markTeleportInFlight(traveler.id(), now));

		new LocalPortalDepartureHold(portal, LocalPortalTestSupport.rejectingRuntime())
				.startPlayerDepartureHold(traveler.player(), traversive);

		assertFalse(LocalPortal.isTeleportInFlight(traveler.id(), System.currentTimeMillis()),
				"a departure hold without a source world must release the cross-server claim");
	}

	private static long failures(String reason)
	{
		return WormholesTelemetry.failureBreakdown().getOrDefault(reason, Long.valueOf(0L)).longValue();
	}

	private static LocalPortal rtpPortal(World world)
	{
		LocalPortal portal = LocalPortalTestSupport.portal(world, PortalType.RTP);
		portal.setRtpSettings(RtpSettings.builder(world).radii(16, 64).soundEnabled(false).build());
		return portal;
	}

	private static Location anchor(World world)
	{
		return new Location(world, 0.5D, 65.0D, 1.0D, 0.0F, 0.0F);
	}

	private static Vector anchorVector()
	{
		return new Vector(0.5D, 65.0D, 1.0D);
	}
}
