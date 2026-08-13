package art.arcane.wormholes.portal.vanilla;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.portal.DimensionalPortalKind;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.PortalStructure;

final class VanillaPortalIndex
{
	static final String NETHER_TAG = "Nether Portal";
	static final String END_TAG = "End Portal";
	static final int END_SOURCE_SCAN_RADIUS = 4;
	private static final int END_CANCEL_RADIUS = 6;

	private final Set<PendingCoverage> pending = ConcurrentHashMap.newKeySet();

	boolean covers(Location location)
	{
		if(location == null || location.getWorld() == null)
		{
			return false;
		}
		if(coversPending(location))
		{
			return true;
		}
		if(Wormholes.portalManager == null)
		{
			return false;
		}
		for(ILocalPortal portal : Wormholes.portalManager.getLocalPortals())
		{
			PortalStructure structure = portal.getStructure();
			if(structure == null || !location.getWorld().equals(structure.getWorld()))
			{
				continue;
			}
			if(structure.contains(location))
			{
				return true;
			}
		}
		return false;
	}

	boolean coversCells(Set<Block> cells)
	{
		if(Wormholes.portalManager == null || cells.isEmpty())
		{
			return false;
		}
		Location probe = cells.iterator().next().getLocation();
		for(ILocalPortal portal : Wormholes.portalManager.getLocalPortals())
		{
			PortalStructure structure = portal.getStructure();
			if(structure == null || !probe.getWorld().equals(structure.getWorld()))
			{
				continue;
			}
			if(structure.contains(probe))
			{
				return true;
			}
		}
		return false;
	}

	boolean nearEndWindow(Location loc)
	{
		if(loc == null || loc.getWorld() == null)
		{
			return false;
		}
		if(nearPendingEndWindow(loc))
		{
			return true;
		}
		if(Wormholes.portalManager == null)
		{
			return false;
		}
		for(ILocalPortal portal : Wormholes.portalManager.getLocalPortals())
		{
			if(!isManagedKind(portal, END_TAG, DimensionalPortalKind.END_SOURCE)
					&& !isManagedKind(portal, END_TAG, DimensionalPortalKind.END_ARRIVAL))
			{
				continue;
			}
			PortalStructure structure = portal.getStructure();
			if(structure == null || !loc.getWorld().equals(structure.getWorld()))
			{
				continue;
			}
			Location c = portal.getCenter();
			if(c == null)
			{
				continue;
			}
			double dx = c.getX() - loc.getX();
			double dy = c.getY() - loc.getY();
			double dz = c.getZ() - loc.getZ();
			if(dx * dx + dy * dy + dz * dz <= END_CANCEL_RADIUS * END_CANCEL_RADIUS)
			{
				return true;
			}
		}
		return false;
	}

	ILocalPortal findReusableEndArrival(World world)
	{
		return findLinkable(world, END_TAG, DimensionalPortalKind.END_ARRIVAL, 0, 0, 0, 32, false);
	}

	ILocalPortal findLinkable(World world, String tag, DimensionalPortalKind kind, int x, int y, int z, int radius, boolean includeY)
	{
		if(Wormholes.portalManager == null)
		{
			return null;
		}
		ILocalPortal nearest = null;
		double nearestDistance = Double.MAX_VALUE;
		double radiusSquared = (double) radius * radius;
		for(ILocalPortal portal : Wormholes.portalManager.getLocalPortals())
		{
			PortalStructure structure = portal.getStructure();
			if(structure == null || !world.equals(structure.getWorld()))
			{
				continue;
			}
			if(!isManagedKind(portal, tag, kind) || portal.getDimensionalCounterpartId() != null || portal.getTunnel() != null)
			{
				continue;
			}
			Location c = portal.getCenter();
			if(c == null)
			{
				continue;
			}
			double dx = c.getX() - x;
			double dy = includeY ? c.getY() - y : 0.0D;
			double dz = c.getZ() - z;
			double horizontalDistance = dx * dx + dz * dz;
			if(horizontalDistance > radiusSquared)
			{
				continue;
			}
			double distance = horizontalDistance + dy * dy;
			if(distance < nearestDistance || (distance == nearestDistance && nearest != null && portal.getId().compareTo(nearest.getId()) < 0))
			{
				nearest = portal;
				nearestDistance = distance;
			}
		}
		return nearest;
	}

	boolean hasEndArrivalNear(World world, int x, int z)
	{
		if(Wormholes.portalManager == null)
		{
			return false;
		}
		for(ILocalPortal portal : Wormholes.portalManager.getLocalPortals())
		{
			if(!isManagedKind(portal, END_TAG, DimensionalPortalKind.END_ARRIVAL) || portal.getWorld() == null || !world.equals(portal.getWorld()))
			{
				continue;
			}
			Location center = portal.getCenter();
			if(center != null && Math.abs(center.getX() - x) <= 3.0D && Math.abs(center.getZ() - z) <= 3.0D)
			{
				return true;
			}
		}
		return false;
	}

	static boolean isManagedKind(ILocalPortal portal, String legacyTag, DimensionalPortalKind kind)
	{
		DimensionalPortalKind savedKind = portal.getDimensionalPortalKind();
		return savedKind == kind || (savedKind == DimensionalPortalKind.NONE && legacyTag.equals(portal.getName()));
	}

	PendingCoverage registerPending(Set<Block> cells)
	{
		if(cells == null || cells.isEmpty())
		{
			return null;
		}
		World world = null;
		Set<PendingCell> pendingCells = new HashSet<PendingCell>();
		for(Block cell : cells)
		{
			if(cell == null || cell.getWorld() == null)
			{
				continue;
			}
			if(world == null)
			{
				world = cell.getWorld();
			}
			else if(!world.equals(cell.getWorld()))
			{
				continue;
			}
			pendingCells.add(new PendingCell(cell.getX(), cell.getY(), cell.getZ()));
		}
		return addPending(world, pendingCells, false, 0, 0, 0);
	}

	PendingCoverage registerPendingEnd(Location frame)
	{
		if(frame == null || frame.getWorld() == null)
		{
			return null;
		}
		World world = frame.getWorld();
		int originX = frame.getBlockX();
		int originY = frame.getBlockY();
		int originZ = frame.getBlockZ();
		Set<PendingCell> pendingCells = new HashSet<PendingCell>();
		for(int dx = -END_SOURCE_SCAN_RADIUS; dx <= END_SOURCE_SCAN_RADIUS; dx++)
		{
			for(int dz = -END_SOURCE_SCAN_RADIUS; dz <= END_SOURCE_SCAN_RADIUS; dz++)
			{
				pendingCells.add(new PendingCell(originX + dx, originY, originZ + dz));
			}
		}
		return addPending(world, pendingCells, true, originX, originY, originZ);
	}

	void releasePending(PendingCoverage coverage)
	{
		if(coverage != null)
		{
			pending.remove(coverage);
		}
	}

	private PendingCoverage addPending(World world, Set<PendingCell> cells, boolean endWindow, int endX, int endY, int endZ)
	{
		if(world == null || cells.isEmpty())
		{
			return null;
		}
		PendingCoverage coverage = new PendingCoverage(world, Set.copyOf(cells), endWindow, endX, endY, endZ);
		pending.add(coverage);
		return coverage;
	}

	private boolean coversPending(Location location)
	{
		for(PendingCoverage coverage : pending)
		{
			if(coverage.covers(location))
			{
				return true;
			}
		}
		return false;
	}

	private boolean nearPendingEndWindow(Location loc)
	{
		for(PendingCoverage coverage : pending)
		{
			if(coverage.nearEndWindow(loc))
			{
				return true;
			}
		}
		return false;
	}

	static final class PendingCoverage
	{
		private final World world;
		private final Set<PendingCell> cells;
		private final boolean endWindow;
		private final int endX;
		private final int endY;
		private final int endZ;

		private PendingCoverage(World world, Set<PendingCell> cells, boolean endWindow, int endX, int endY, int endZ)
		{
			this.world = world;
			this.cells = cells;
			this.endWindow = endWindow;
			this.endX = endX;
			this.endY = endY;
			this.endZ = endZ;
		}

		private boolean covers(Location location)
		{
			return world.equals(location.getWorld())
					&& cells.contains(new PendingCell(location.getBlockX(), location.getBlockY(), location.getBlockZ()));
		}

		private boolean nearEndWindow(Location loc)
		{
			if(!endWindow || !world.equals(loc.getWorld()))
			{
				return false;
			}
			double dx = (endX + 0.5D) - loc.getX();
			double dy = (endY + 0.5D) - loc.getY();
			double dz = (endZ + 0.5D) - loc.getZ();
			return dx * dx + dy * dy + dz * dz <= END_CANCEL_RADIUS * END_CANCEL_RADIUS;
		}
	}

	private record PendingCell(int x, int y, int z)
	{
	}
}
