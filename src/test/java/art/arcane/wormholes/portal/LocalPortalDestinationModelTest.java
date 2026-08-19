package art.arcane.wormholes.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import art.arcane.wormholes.portal.LocalPortalDestinationModel.Entry;
import art.arcane.wormholes.portal.LocalPortalDestinationModel.SortMode;

public final class LocalPortalDestinationModelTest
{
	@Test
	public void smartSortKeepsLinkedFirstThenLocalsByDistanceThenRemotes()
	{
		Entry linkedRemote = remote("zeta", "hub", true, true);
		Entry farLocal = local("alpha", "world", 900.0D, false);
		Entry nearLocal = local("mid", "world", 25.0D, false);
		Entry closedRemote = remote("beta", "hub", false, false);
		Entry openRemote = remote("gamma", "hub", false, true);
		List<Entry> entries = new ArrayList<Entry>(List.of(farLocal, closedRemote, linkedRemote, openRemote, nearLocal));

		entries.sort(LocalPortalDestinationModel.comparator(SortMode.SMART));

		assertEquals(List.of(linkedRemote, nearLocal, farLocal, openRemote, closedRemote), entries);
	}

	@Test
	public void nameSortOrdersAlphabeticallyIgnoringCaseAndLinkState()
	{
		Entry charlie = local("charlie", "world", 1.0D, true);
		Entry alpha = local("Alpha", "world", 900.0D, false);
		Entry bravo = remote("bravo", "hub", false, true);
		List<Entry> entries = new ArrayList<Entry>(List.of(charlie, bravo, alpha));

		entries.sort(LocalPortalDestinationModel.comparator(SortMode.NAME));

		assertEquals(List.of(alpha, bravo, charlie), entries);
	}

	@Test
	public void worldSortGroupsByWorldThenName()
	{
		Entry netherB = local("b", "the_nether", 1.0D, false);
		Entry overworldZ = local("z", "overworld", 1.0D, false);
		Entry netherA = local("a", "the_nether", 5.0D, false);
		List<Entry> entries = new ArrayList<Entry>(List.of(netherB, overworldZ, netherA));

		entries.sort(LocalPortalDestinationModel.comparator(SortMode.WORLD));

		assertEquals(List.of(overworldZ, netherA, netherB), entries);
	}

	@Test
	public void distanceSortPlacesNearestFirstAndRemotesLast()
	{
		Entry near = local("near", "world", 4.0D, false);
		Entry far = local("far", "world", 400.0D, true);
		Entry remote = remote("remote", "hub", true, true);
		List<Entry> entries = new ArrayList<Entry>(List.of(remote, far, near));

		entries.sort(LocalPortalDestinationModel.comparator(SortMode.DISTANCE));

		assertEquals(List.of(near, far, remote), entries);
	}

	@Test
	public void sortModeCyclesThroughAllModesAndWraps()
	{
		assertEquals(SortMode.NAME, SortMode.SMART.next());
		assertEquals(SortMode.WORLD, SortMode.NAME.next());
		assertEquals(SortMode.DISTANCE, SortMode.WORLD.next());
		assertEquals(SortMode.SMART, SortMode.DISTANCE.next());
	}

	@Test
	public void pagingMathCoversEmptyExactAndOverflowCounts()
	{
		assertEquals(1, LocalPortalDestinationModel.pageCount(0));
		assertEquals(1, LocalPortalDestinationModel.pageCount(45));
		assertEquals(2, LocalPortalDestinationModel.pageCount(46));
		assertEquals(3, LocalPortalDestinationModel.pageCount(91));
	}

	@Test
	public void pageClampAndSliceBoundsStayInsideTheEntryList()
	{
		assertEquals(0, LocalPortalDestinationModel.clampPage(-3, 2));
		assertEquals(1, LocalPortalDestinationModel.clampPage(9, 2));
		assertEquals(45, LocalPortalDestinationModel.pageStart(1));
		assertEquals(46, LocalPortalDestinationModel.pageEnd(46, 1));
		assertEquals(45, LocalPortalDestinationModel.pageEnd(46, 0));
	}

	private static Entry local(String name, String world, double distanceSquared, boolean linked)
	{
		return new Entry(name, world, distanceSquared, linked, false, true);
	}

	private static Entry remote(String name, String server, boolean linked, boolean open)
	{
		return new Entry(name, server, Double.MAX_VALUE, linked, true, open);
	}
}
