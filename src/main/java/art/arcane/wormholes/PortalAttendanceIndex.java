package art.arcane.wormholes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Location;

import art.arcane.wormholes.papi.PortalProximityIndex;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.util.AxisAlignedBB;

final class PortalAttendanceIndex
{
	static final double BASE_RANGE = 64.0D;
	private static final int LEAF_SIZE = 8;
	private static final double FACING_EPSILON = 1.0E-12D;

	private final Map<UUID, WorldIndex> worlds;

	private PortalAttendanceIndex(Map<UUID, WorldIndex> worlds)
	{
		this.worlds = worlds;
	}

	static PortalAttendanceIndex capture(List<ILocalPortal> portals)
	{
		Map<UUID, List<Entry>> mutableWorlds = new HashMap<UUID, List<Entry>>();
		for(int portalIndex = 0; portalIndex < portals.size(); portalIndex++)
		{
			ILocalPortal portal = portals.get(portalIndex);
			if(portal == null)
			{
				continue;
			}
			Location center = portal.getCenter();
			if(center == null || center.getWorld() == null)
			{
				continue;
			}
			if(!Double.isFinite(center.getX()) || !Double.isFinite(center.getY()) || !Double.isFinite(center.getZ()))
			{
				continue;
			}
			UUID worldId = center.getWorld().getUID();
			Entry entry = new Entry(portalIndex, center.getX(), center.getY(), center.getZ(), thresholdSquared(portal));
			mutableWorlds.computeIfAbsent(worldId, ignored -> new ArrayList<Entry>()).add(entry);
		}
		Map<UUID, WorldIndex> frozenWorlds = new HashMap<UUID, WorldIndex>(mutableWorlds.size() * 2);
		for(Map.Entry<UUID, List<Entry>> world : mutableWorlds.entrySet())
		{
			frozenWorlds.put(world.getKey(), new WorldIndex(world.getValue()));
		}
		return new PortalAttendanceIndex(Map.copyOf(frozenWorlds));
	}

	int offerNearest(
		PortalProximityIndex matches,
		UUID playerId,
		UUID worldId,
		double x,
		double y,
		double z,
		float yaw,
		float pitch)
	{
		if(matches == null || playerId == null || worldId == null
			|| !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
		{
			return 0;
		}
		WorldIndex world = worlds.get(worldId);
		if(world == null)
		{
			return 0;
		}
		Query query = Query.create(x, y, z, yaw, pitch);
		SearchState result = world.search(query);
		if(result.bestPortalIndex >= 0)
		{
			matches.offer(playerId, result.bestPortalIndex, result.bestDistanceSquared,
				result.bestFacing ? 1.0D : -1.0D);
		}
		return result.evaluated;
	}

	static double threshold(ILocalPortal portal)
	{
		AxisAlignedBB area = portal.getArea();
		if(area == null)
		{
			return BASE_RANGE;
		}
		double sizeX = area.sizeX();
		double sizeY = area.sizeY();
		double sizeZ = area.sizeZ();
		double diagonalSquared = (sizeX * sizeX) + (sizeY * sizeY) + (sizeZ * sizeZ);
		if(!Double.isFinite(diagonalSquared) || diagonalSquared < 0.0D)
		{
			return BASE_RANGE;
		}
		return BASE_RANGE + (0.5D * Math.sqrt(diagonalSquared));
	}

	private static double thresholdSquared(ILocalPortal portal)
	{
		double threshold = threshold(portal);
		return threshold * threshold;
	}

	private static final class WorldIndex
	{
		private final Entry[] entries;
		private final Node root;

		private WorldIndex(List<Entry> source)
		{
			entries = source.toArray(Entry[]::new);
			root = build(entries, 0, entries.length);
		}

		private SearchState search(Query query)
		{
			SearchState state = new SearchState();
			search(root, entries, query, state);
			return state;
		}

		private static void search(Node node, Entry[] entries, Query query, SearchState state)
		{
			if(node == null || cannotImprove(node, query, state))
			{
				return;
			}
			if(node.left == null)
			{
				for(int index = node.from; index < node.to; index++)
				{
					state.offer(entries[index], query);
				}
				return;
			}

			Node first = node.left;
			Node second = node.right;
			double firstDistance = first.minimumDistanceSquared(query);
			double secondDistance = second.minimumDistanceSquared(query);
			if(secondDistance < firstDistance
				|| (secondDistance == firstDistance && second.minimumPortalIndex < first.minimumPortalIndex))
			{
				first = node.right;
				second = node.left;
			}
			search(first, entries, query, state);
			search(second, entries, query, state);
		}

		private static boolean cannotImprove(Node node, Query query, SearchState state)
		{
			double minimumDistanceSquared = node.minimumDistanceSquared(query);
			if(strictlyGreater(minimumDistanceSquared, node.maximumThresholdSquared))
			{
				return true;
			}
			if(state.bestPortalIndex < 0)
			{
				return false;
			}
			boolean mightFace = node.mightContainFacing(query);
			if(state.bestFacing && !mightFace)
			{
				return true;
			}
			if(!state.bestFacing && mightFace)
			{
				return false;
			}
			if(strictlyGreater(minimumDistanceSquared, state.bestDistanceSquared))
			{
				return true;
			}
			return minimumDistanceSquared == state.bestDistanceSquared
				&& node.minimumPortalIndex >= state.bestPortalIndex;
		}

		private static Node build(Entry[] entries, int from, int to)
		{
			Node bounds = Node.bounds(entries, from, to);
			if(to - from <= LEAF_SIZE)
			{
				return bounds;
			}
			int axis = bounds.longestAxis();
			Arrays.sort(entries, from, to, comparator(axis));
			int middle = from + ((to - from) / 2);
			Node left = build(entries, from, middle);
			Node right = build(entries, middle, to);
			return bounds.withChildren(left, right);
		}

		private static Comparator<Entry> comparator(int axis)
		{
			return switch(axis)
			{
				case 0 -> Comparator.comparingDouble(Entry::x).thenComparingInt(Entry::portalIndex);
				case 1 -> Comparator.comparingDouble(Entry::y).thenComparingInt(Entry::portalIndex);
				default -> Comparator.comparingDouble(Entry::z).thenComparingInt(Entry::portalIndex);
			};
		}
	}

	private static final class Node
	{
		private final int from;
		private final int to;
		private final double minimumX;
		private final double minimumY;
		private final double minimumZ;
		private final double maximumX;
		private final double maximumY;
		private final double maximumZ;
		private final double maximumThresholdSquared;
		private final int minimumPortalIndex;
		private final Node left;
		private final Node right;

		private Node(
			int from,
			int to,
			double minimumX,
			double minimumY,
			double minimumZ,
			double maximumX,
			double maximumY,
			double maximumZ,
			double maximumThresholdSquared,
			int minimumPortalIndex,
			Node left,
			Node right)
		{
			this.from = from;
			this.to = to;
			this.minimumX = minimumX;
			this.minimumY = minimumY;
			this.minimumZ = minimumZ;
			this.maximumX = maximumX;
			this.maximumY = maximumY;
			this.maximumZ = maximumZ;
			this.maximumThresholdSquared = maximumThresholdSquared;
			this.minimumPortalIndex = minimumPortalIndex;
			this.left = left;
			this.right = right;
		}

		private static Node bounds(Entry[] entries, int from, int to)
		{
			double minimumX = Double.POSITIVE_INFINITY;
			double minimumY = Double.POSITIVE_INFINITY;
			double minimumZ = Double.POSITIVE_INFINITY;
			double maximumX = Double.NEGATIVE_INFINITY;
			double maximumY = Double.NEGATIVE_INFINITY;
			double maximumZ = Double.NEGATIVE_INFINITY;
			double maximumThresholdSquared = 0.0D;
			int minimumPortalIndex = Integer.MAX_VALUE;
			for(int index = from; index < to; index++)
			{
				Entry entry = entries[index];
				minimumX = Math.min(minimumX, entry.x());
				minimumY = Math.min(minimumY, entry.y());
				minimumZ = Math.min(minimumZ, entry.z());
				maximumX = Math.max(maximumX, entry.x());
				maximumY = Math.max(maximumY, entry.y());
				maximumZ = Math.max(maximumZ, entry.z());
				maximumThresholdSquared = Math.max(maximumThresholdSquared, entry.thresholdSquared());
				minimumPortalIndex = Math.min(minimumPortalIndex, entry.portalIndex());
			}
			return new Node(from, to, minimumX, minimumY, minimumZ, maximumX, maximumY, maximumZ,
				maximumThresholdSquared, minimumPortalIndex, null, null);
		}

		private Node withChildren(Node left, Node right)
		{
			return new Node(from, to, minimumX, minimumY, minimumZ, maximumX, maximumY, maximumZ,
				maximumThresholdSquared, minimumPortalIndex, left, right);
		}

		private int longestAxis()
		{
			double xLength = maximumX - minimumX;
			double yLength = maximumY - minimumY;
			double zLength = maximumZ - minimumZ;
			if(xLength >= yLength && xLength >= zLength)
			{
				return 0;
			}
			return yLength >= zLength ? 1 : 2;
		}

		private double minimumDistanceSquared(Query query)
		{
			double dx = axisDistance(query.x(), minimumX, maximumX);
			double dy = axisDistance(query.y(), minimumY, maximumY);
			double dz = axisDistance(query.z(), minimumZ, maximumZ);
			return (dx * dx) + (dy * dy) + (dz * dz);
		}

		private boolean mightContainFacing(Query query)
		{
			double centerX = (minimumX + maximumX) * 0.5D;
			double centerY = (minimumY + maximumY) * 0.5D;
			double centerZ = (minimumZ + maximumZ) * 0.5D;
			double halfX = (maximumX - minimumX) * 0.5D;
			double halfY = (maximumY - minimumY) * 0.5D;
			double halfZ = (maximumZ - minimumZ) * 0.5D;
			double radiusSquared = (halfX * halfX) + (halfY * halfY) + (halfZ * halfZ);
			double dx = centerX - query.x();
			double dy = centerY - query.y();
			double dz = centerZ - query.z();
			double distanceSquared = (dx * dx) + (dy * dy) + (dz * dz);
			if(!Double.isFinite(radiusSquared) || !Double.isFinite(distanceSquared) || distanceSquared <= radiusSquared)
			{
				return true;
			}
			double distance = Math.sqrt(distanceSquared);
			double radius = Math.sqrt(radiusSquared);
			double centerCosine = ((query.lookX() * dx) + (query.lookY() * dy) + (query.lookZ() * dz)) / distance;
			centerCosine = Math.max(-1.0D, Math.min(1.0D, centerCosine));
			double sineAlpha = Math.min(1.0D, radius / distance);
			double cosineAlpha = Math.sqrt(Math.max(0.0D, 1.0D - (sineAlpha * sineAlpha)));
			if(centerCosine >= cosineAlpha)
			{
				return true;
			}
			double sineTheta = Math.sqrt(Math.max(0.0D, 1.0D - (centerCosine * centerCosine)));
			double maximumCosine = (centerCosine * cosineAlpha) + (sineTheta * sineAlpha);
			return maximumCosine + FACING_EPSILON >= PortalProximityIndex.FACING_COSINE;
		}

		private static double axisDistance(double value, double minimum, double maximum)
		{
			if(value < minimum)
			{
				return minimum - value;
			}
			return value > maximum ? value - maximum : 0.0D;
		}
	}

	private static final class SearchState
	{
		private int bestPortalIndex = -1;
		private double bestDistanceSquared = Double.POSITIVE_INFINITY;
		private boolean bestFacing;
		private int evaluated;

		private void offer(Entry entry, Query query)
		{
			evaluated++;
			double dx = entry.x() - query.x();
			double dy = entry.y() - query.y();
			double dz = entry.z() - query.z();
			double distanceSquared = (dx * dx) + (dy * dy) + (dz * dz);
			if(distanceSquared > entry.thresholdSquared())
			{
				return;
			}
			double facingCosine = PortalProximityIndex.facingCosine(
				query.yaw(), query.pitch(), dx, dy, dz, distanceSquared);
			boolean facing = facingCosine >= PortalProximityIndex.FACING_COSINE;
			if(bestPortalIndex < 0
				|| (facing && !bestFacing)
				|| (facing == bestFacing && (distanceSquared < bestDistanceSquared
					|| (distanceSquared == bestDistanceSquared && entry.portalIndex() < bestPortalIndex))))
			{
				bestPortalIndex = entry.portalIndex();
				bestDistanceSquared = distanceSquared;
				bestFacing = facing;
			}
		}
	}

	private record Entry(int portalIndex, double x, double y, double z, double thresholdSquared)
	{
	}

	private record Query(double x, double y, double z, float yaw, float pitch, double lookX, double lookY, double lookZ)
	{
		private static Query create(double x, double y, double z, float yaw, float pitch)
		{
			double yawRadians = Math.toRadians(yaw);
			double pitchRadians = Math.toRadians(pitch);
			double cosinePitch = Math.cos(pitchRadians);
			return new Query(x, y, z, yaw, pitch,
				-cosinePitch * Math.sin(yawRadians),
				-Math.sin(pitchRadians),
				cosinePitch * Math.cos(yawRadians));
		}
	}

	private static boolean strictlyGreater(double left, double right)
	{
		if(left <= right)
		{
			return false;
		}
		if(!Double.isFinite(left) || !Double.isFinite(right))
		{
			return true;
		}
		double tolerance = Math.max(Math.ulp(left), Math.ulp(right)) * 8.0D;
		return left - right > tolerance;
	}
}
