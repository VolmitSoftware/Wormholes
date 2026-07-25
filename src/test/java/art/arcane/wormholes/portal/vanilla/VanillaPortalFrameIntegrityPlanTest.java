package art.arcane.wormholes.portal.vanilla;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

public final class VanillaPortalFrameIntegrityPlanTest
{
	@Test
	public void verticalPortalRequiresEveryOrthogonalFrameBlock()
	{
		Set<VanillaPortalFrameIntegrity.FramePosition> cells = new HashSet<VanillaPortalFrameIntegrity.FramePosition>();
		for(int x = 0; x < 2; x++)
		{
			for(int y = 0; y < 3; y++)
			{
				cells.add(new VanillaPortalFrameIntegrity.FramePosition(x, y, 0));
			}
		}

		Set<VanillaPortalFrameIntegrity.FramePosition> frame = VanillaPortalFrameIntegrity.expectedFramePositions(cells);

		assertEquals(10, frame.size());
		assertTrue(frame.contains(new VanillaPortalFrameIntegrity.FramePosition(-1, 1, 0)));
		assertTrue(frame.contains(new VanillaPortalFrameIntegrity.FramePosition(1, 3, 0)));
		assertFalse(frame.contains(new VanillaPortalFrameIntegrity.FramePosition(-1, -1, 0)));
	}

	@Test
	public void horizontalEndWindowRequiresTwelveVanillaFrameBlocks()
	{
		Set<VanillaPortalFrameIntegrity.FramePosition> cells = new HashSet<VanillaPortalFrameIntegrity.FramePosition>();
		for(int x = -1; x <= 1; x++)
		{
			for(int z = -1; z <= 1; z++)
			{
				cells.add(new VanillaPortalFrameIntegrity.FramePosition(x, 64, z));
			}
		}

		Set<VanillaPortalFrameIntegrity.FramePosition> frame = VanillaPortalFrameIntegrity.expectedFramePositions(cells);

		assertEquals(12, frame.size());
		assertTrue(frame.contains(new VanillaPortalFrameIntegrity.FramePosition(0, 64, -2)));
		assertFalse(frame.contains(new VanillaPortalFrameIntegrity.FramePosition(-2, 64, -2)));
	}

	@Test
	public void pendingBuildFootprintsCannotOverlap()
	{
		assertEquals(3, VanillaPortalNetherSites.netherBuildHalfExtent(2, 3));
		assertEquals(15, VanillaPortalNetherSites.netherBuildHalfExtent(21, 21));
		assertEquals(7, VanillaPortalNetherSites.netherBuildSpacing(2, 3));
		assertEquals(31, VanillaPortalNetherSites.netherBuildSpacing(21, 21));
		assertTrue(VanillaPortalNetherSites.netherFootprintsOverlap(0, 0, 4, 9, 0, 4));
		assertFalse(VanillaPortalNetherSites.netherFootprintsOverlap(0, 0, 4, 10, 0, 4));
		assertTrue(VanillaPortalNetherSites.netherFootprintOverlapsStructureBounds(7, 0, 2, -10.0D, 10.0D, 0.0D, 0.0D));
		assertFalse(VanillaPortalNetherSites.netherFootprintOverlapsStructureBounds(17, 0, 2, -10.0D, 10.0D, 0.0D, 0.0D));
		assertTrue(VanillaPortalEndSites.endWindowsOverlap(new VanillaPortalEndSites.EndTarget(12, 9), new VanillaPortalEndSites.EndTarget(13, 7)));
		assertFalse(VanillaPortalEndSites.endWindowsOverlap(new VanillaPortalEndSites.EndTarget(12, 9), new VanillaPortalEndSites.EndTarget(15, 9)));
	}

	@Test
	public void reservedFootprintContainsEveryGeneratedMutationForBothAxes()
	{
		int[] sizes = {1, 2, 7, 8, 14, 15, 21};
		for(int size : sizes)
		{
			int halfExtent = VanillaPortalNetherSites.netherBuildHalfExtent(size, size);
			for(boolean alongX : new boolean[] {false, true})
			{
				for(PortalSiteBuilder.NetherMutation mutation : PortalSiteBuilder.planNetherMutations(0, 64, 0, alongX, size, size))
				{
					assertTrue(Math.abs(mutation.x()) <= halfExtent);
					assertTrue(Math.abs(mutation.z()) <= halfExtent);
				}
			}
		}
	}
}
