package art.arcane.wormholes.portal.vanilla;

import java.util.Set;

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
	private static final int END_CANCEL_RADIUS = 6;

	boolean covers(Location location)
	{
		if(location == null || location.getWorld() == null || Wormholes.portalManager == null)
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
		if(loc == null || loc.getWorld() == null || Wormholes.portalManager == null)
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
}
