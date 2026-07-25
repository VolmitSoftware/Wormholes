package art.arcane.wormholes.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.portal.LocalPortalTestSupport.FakeEntity;
import art.arcane.wormholes.portal.rtp.RtpSettings;
import art.arcane.wormholes.service.WormholesTelemetry;

public final class LocalPortalRtpRefusalTest
{
	private static final String REFUSED_REASON = "TRAVERSAL_RTP_BEGIN_REFUSED";

	@Test
	public void anRtpRuntimeThatRefusesToBeginBouncesTheTravelerInsteadOfDroppingThem()
	{
		World world = LocalPortalTestSupport.world("rtp-refused-begin");
		LocalPortal portal = rtpPortal(world);
		FakeEntity traveler = FakeEntity.entity("rtp-refused", anchor(world));
		Traversive traversive = LocalPortalTestSupport.traversive(portal, traveler.entity(), anchorVector());
		long before = refusals();

		portal.traversal().completeRtpDispatch(traveler.entity(), traversive, false);

		assertTrue(LocalPortal.isTeleportCoolingDown(traveler.id(), System.currentTimeMillis()),
				"a refused random-teleport must run the bounce funnel, not drop the subject");
		assertTrue(LocalPortal.isReentryLatched(traveler.id()),
				"a refused random-teleport must arm the rejected-reentry latch so the portal cannot re-trigger");
		assertEquals(LocalPortal.sourceRejectionVelocity(traversive).getZ(), traveler.velocity().getZ(), 1.0E-9D,
				"the bounced traveler must be pushed back out of the source portal");
		assertEquals(before + 1L, refusals(), "a terminal traversal failure must increment the failure counter");
		LocalPortal.clearReentryLatch(traveler.id());
		LocalPortal.clearTeleportCooldown(traveler.id());
	}

	private static long refusals()
	{
		return WormholesTelemetry.failureBreakdown().getOrDefault(REFUSED_REASON, Long.valueOf(0L)).longValue();
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
