package art.arcane.wormholes.portal;

import java.util.Comparator;
import java.util.Objects;

final class LocalPortalDestinationModel
{
	static final int ENTRIES_PER_PAGE = 45;

	private LocalPortalDestinationModel()
	{
	}

	static Comparator<Entry> comparator(SortMode mode)
	{
		SortMode requiredMode = Objects.requireNonNull(mode, "mode");
		Comparator<Entry> byName = Comparator.comparing(Entry::name, String.CASE_INSENSITIVE_ORDER);
		Comparator<Entry> byWorld = Comparator.comparing(Entry::world, String.CASE_INSENSITIVE_ORDER);
		return switch(requiredMode)
		{
			case SMART -> Comparator
					.comparing((Entry entry) -> !entry.linked())
					.thenComparing(Entry::remote)
					.thenComparing(entry -> !entry.open())
					.thenComparingDouble(Entry::distanceSquared)
					.thenComparing(byWorld)
					.thenComparing(byName);
			case NAME -> byName.thenComparing(byWorld);
			case WORLD -> byWorld.thenComparing(byName);
			case DISTANCE -> Comparator
					.comparingDouble(Entry::distanceSquared)
					.thenComparing(byName)
					.thenComparing(byWorld);
		};
	}

	static int pageCount(int entryCount)
	{
		return Math.max(1, (entryCount + ENTRIES_PER_PAGE - 1) / ENTRIES_PER_PAGE);
	}

	static int clampPage(int page, int pageCount)
	{
		return Math.max(0, Math.min(pageCount - 1, page));
	}

	static int pageStart(int page)
	{
		return page * ENTRIES_PER_PAGE;
	}

	static int pageEnd(int entryCount, int page)
	{
		return Math.min(entryCount, pageStart(page) + ENTRIES_PER_PAGE);
	}

	enum SortMode
	{
		SMART,
		NAME,
		WORLD,
		DISTANCE;

		SortMode next()
		{
			SortMode[] modes = values();
			return modes[(ordinal() + 1) % modes.length];
		}
	}

	record Entry(String name, String world, double distanceSquared, boolean linked, boolean remote, boolean open)
	{
		Entry
		{
			Objects.requireNonNull(name, "name");
			Objects.requireNonNull(world, "world");
		}
	}
}
