package art.arcane.wormholes.portal.vanilla;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

public final class VanillaPortalReplacerEndPlanTest
{
	@Test
	public void arrivalIsOffsetAndTenBlocksAboveSurface()
	{
		VanillaPortalEndSites.EndDestinationPlan plan = VanillaPortalEndSites.endDestinationPlan(64, -64, 320);

		assertEquals(12, plan.x());
		assertEquals(74, plan.y());
		assertEquals(9, plan.z());
		double distanceFromCenter = Math.hypot(plan.x(), plan.z());
		assertTrue(distanceFromCenter >= 15.0D && distanceFromCenter <= 17.0D);
	}

	@Test
	public void arrivalHeightStaysInsideWorldBounds()
	{
		assertEquals(316, VanillaPortalEndSites.endDestinationPlan(315, -64, 320).y());
		assertEquals(-59, VanillaPortalEndSites.endDestinationPlan(-100, -64, 320).y());
	}

	@Test
	public void primaryTargetsStayOffsetDistinctAndInsideSingleChunks()
	{
		List<VanillaPortalEndSites.EndTarget> targets = VanillaPortalEndSites.primaryEndTargets();

		assertEquals(16, targets.size());
		assertEquals(16, new HashSet<VanillaPortalEndSites.EndTarget>(targets).size());
		for(VanillaPortalEndSites.EndTarget target : targets)
		{
			double radius = Math.hypot(target.x(), target.z());
			assertTrue(radius >= 14.5D && radius <= 15.5D);
			assertEquals((target.x() - 1) >> 4, (target.x() + 1) >> 4);
			assertEquals((target.z() - 1) >> 4, (target.z() + 1) >> 4);
		}
	}

	@Test
	public void selectorAdvancesPastOccupiedTargetsAndHasAnOffsetFallback()
	{
		Set<VanillaPortalEndSites.EndTarget> occupied = new HashSet<VanillaPortalEndSites.EndTarget>();
		VanillaPortalEndSites.EndTarget first = VanillaPortalEndSites.primaryEndTargets().get(0);
		occupied.add(first);

		assertEquals(VanillaPortalEndSites.primaryEndTargets().get(1), VanillaPortalEndSites.selectEndTarget(target -> !occupied.contains(target)));
		occupied.addAll(VanillaPortalEndSites.primaryEndTargets());
		VanillaPortalEndSites.EndTarget fallback = VanillaPortalEndSites.selectEndTarget(target -> !occupied.contains(target));
		assertEquals(new VanillaPortalEndSites.EndTarget(20, 20), fallback);
		assertTrue(Math.hypot(fallback.x(), fallback.z()) > 20.0D);
	}

	@Test
	public void fallbackTargetsNeverCrossAChunkBoundary()
	{
		VanillaPortalEndSites.EndTarget target = new VanillaPortalEndSites.EndTarget(20, 20);
		for(int i = 0; i < 16; i++)
		{
			assertTrue(VanillaPortalEndSites.endWindowFitsSingleChunk(target));
			target = VanillaPortalEndSites.nextEndFallbackTarget(target);
		}
		assertEquals(new VanillaPortalEndSites.EndTarget(36, 20),
				VanillaPortalEndSites.nextEndFallbackTarget(new VanillaPortalEndSites.EndTarget(28, 20)));
	}
}
