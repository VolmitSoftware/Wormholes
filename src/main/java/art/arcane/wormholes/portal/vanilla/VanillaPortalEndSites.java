package art.arcane.wormholes.portal.vanilla;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import org.bukkit.World;

import art.arcane.wormholes.Wormholes;

final class VanillaPortalEndSites
{
	static final int WINDOW_HALF = 1;
	static final int COUNTERPART_RISE = 10;
	private static final int DESTINATION_X = 12;
	private static final int DESTINATION_Z = 9;
	private static final int[][] DESTINATIONS = new int[][] {
			{ 12, 9 }, { 9, 12 }, { 13, 7 }, { 7, 13 },
			{ -12, 9 }, { -9, 12 }, { -13, 7 }, { -7, 13 },
			{ -12, -9 }, { -9, -12 }, { -13, -7 }, { -7, -13 },
			{ 12, -9 }, { 9, -12 }, { 13, -7 }, { 7, -13 }
	};
	private static final int MAX_FALLBACK_STEPS = 128;
	private final VanillaPortalIndex index;
	private final Object lock = new Object();
	private final Set<BuildTarget> pending = new HashSet<BuildTarget>();

	VanillaPortalEndSites(VanillaPortalIndex index)
	{
		this.index = index;
	}

	BuildTarget reserve(World world)
	{
		synchronized(lock)
		{
			UUID worldId = world.getUID();
			for(EndTarget target : primaryEndTargets())
			{
				if(!index.hasEndArrivalNear(world, target.x(), target.z()) && !isPending(worldId, target))
				{
					BuildTarget reservation = new BuildTarget(worldId, target);
					pending.add(reservation);
					return reservation;
				}
			}
			EndTarget fallback = new EndTarget(20, 20);
			for(int step = 0; step <= MAX_FALLBACK_STEPS; step++)
			{
				if(endWindowFitsSingleChunk(fallback) && !index.hasEndArrivalNear(world, fallback.x(), fallback.z()) && !isPending(worldId, fallback))
				{
					BuildTarget reservation = new BuildTarget(worldId, fallback);
					pending.add(reservation);
					return reservation;
				}
				fallback = nextEndFallbackTarget(fallback);
			}
			Wormholes.w("[vanilla-portal] no free End arrival site within " + MAX_FALLBACK_STEPS + " candidate windows in "
					+ world.getName() + "; end portal left one-sided");
			return null;
		}
	}

	void release(BuildTarget target)
	{
		synchronized(lock)
		{
			pending.remove(target);
		}
	}

	private boolean isPending(UUID worldId, EndTarget target)
	{
		for(BuildTarget reserved : pending)
		{
			if(reserved.worldId().equals(worldId) && endWindowsOverlap(reserved.target(), target))
			{
				return true;
			}
		}
		return false;
	}

	static EndDestinationPlan endDestinationPlan(int surfaceY, int minHeight, int maxHeight)
	{
		return endDestinationPlan(DESTINATION_X, DESTINATION_Z, surfaceY, minHeight, maxHeight);
	}

	static EndDestinationPlan endDestinationPlan(int x, int z, int surfaceY, int minHeight, int maxHeight)
	{
		int y = Math.max(minHeight + 5, Math.min(maxHeight - 4, surfaceY + COUNTERPART_RISE));
		return new EndDestinationPlan(x, y, z);
	}

	static boolean endWindowsOverlap(EndTarget a, EndTarget b)
	{
		int diameter = WINDOW_HALF * 2;
		return Math.abs(a.x() - b.x()) <= diameter && Math.abs(a.z() - b.z()) <= diameter;
	}

	static boolean endWindowFitsSingleChunk(EndTarget target)
	{
		return ((target.x() - WINDOW_HALF) >> 4) == ((target.x() + WINDOW_HALF) >> 4)
				&& ((target.z() - WINDOW_HALF) >> 4) == ((target.z() + WINDOW_HALF) >> 4);
	}

	static EndTarget nextEndFallbackTarget(EndTarget target)
	{
		int step = WINDOW_HALF * 2 + 2;
		EndTarget next = new EndTarget(target.x() + step, target.z());
		while(!endWindowFitsSingleChunk(next))
		{
			next = new EndTarget(next.x() + step, next.z());
		}
		return next;
	}

	static EndTarget selectEndTarget(Predicate<EndTarget> available)
	{
		for(EndTarget target : primaryEndTargets())
		{
			if(available.test(target))
			{
				return target;
			}
		}
		return new EndTarget(20, 20);
	}

	static List<EndTarget> primaryEndTargets()
	{
		List<EndTarget> targets = new ArrayList<EndTarget>(DESTINATIONS.length);
		for(int[] coordinates : DESTINATIONS)
		{
			targets.add(new EndTarget(coordinates[0], coordinates[1]));
		}
		return List.copyOf(targets);
	}

	record EndTarget(int x, int z)
	{
	}

	record EndDestinationPlan(int x, int y, int z)
	{
	}

	record BuildTarget(UUID worldId, EndTarget target)
	{
	}
}
