package art.arcane.wormholes.portal.vanilla;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.bukkit.World;
import org.bukkit.block.Block;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.portal.DimensionalPortalKind;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.util.AxisAlignedBB;

final class VanillaPortalNetherSites
{
	private static final int EXISTING_FOOTPRINT_MARGIN = 4;
	private static final int[][] BUILD_OFFSETS = new int[][] {
			{ 0, 0 }, { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 },
			{ 1, 1 }, { 1, -1 }, { -1, 1 }, { -1, -1 },
			{ 2, 0 }, { -2, 0 }, { 0, 2 }, { 0, -2 },
			{ 2, 1 }, { 2, -1 }, { -2, 1 }, { -2, -1 },
			{ 1, 2 }, { -1, 2 }, { 1, -2 }, { -1, -2 }
	};
	private static final int MAX_FALLBACK_STEPS = 128;
	private final Object lock = new Object();
	private final Set<BuildTarget> pending = new HashSet<BuildTarget>();

	BuildTarget reserve(World world, int desiredX, int desiredZ, int interiorWidth, int interiorHeight, Set<Block> forbiddenPhysicalPortal)
	{
		int normalizedWidth = PortalSiteBuilder.netherInteriorWidth(interiorWidth);
		int halfExtent = netherBuildHalfExtent(normalizedWidth, interiorHeight);
		int spacing = netherBuildSpacing(normalizedWidth, interiorHeight);
		synchronized(lock)
		{
			UUID worldId = world.getUID();
			for(int[] offset : BUILD_OFFSETS)
			{
				int x = desiredX + offset[0] * spacing;
				int z = desiredZ + offset[1] * spacing;
				BuildTarget candidate = new BuildTarget(worldId, x, z, halfExtent);
				if(claim(world, candidate, forbiddenPhysicalPortal))
				{
					return candidate;
				}
			}
			int x = desiredX + spacing * 3;
			for(int step = 0; step <= MAX_FALLBACK_STEPS; step++)
			{
				BuildTarget fallback = new BuildTarget(worldId, x, desiredZ, halfExtent);
				if(claim(world, fallback, forbiddenPhysicalPortal))
				{
					return fallback;
				}
				x += spacing;
			}
			Wormholes.w("[vanilla-portal] no free nether counterpart site within " + (MAX_FALLBACK_STEPS * spacing)
					+ " blocks of " + desiredX + "," + desiredZ + " in " + world.getName() + "; vanilla portal left unchanged");
			return null;
		}
	}

	private boolean claim(World world, BuildTarget candidate, Set<Block> forbiddenPhysicalPortal)
	{
		return !hasManagedPortalConflict(world, candidate)
				&& !hasPhysicalPortalConflict(candidate, forbiddenPhysicalPortal)
				&& reserveFootprint(candidate);
	}

	void release(BuildTarget target)
	{
		synchronized(lock)
		{
			pending.remove(target);
		}
	}

	private boolean reserveFootprint(BuildTarget candidate)
	{
		for(BuildTarget existing : pending)
		{
			if(footprintsOverlap(candidate, existing))
			{
				return false;
			}
		}
		pending.add(candidate);
		return true;
	}

	private static boolean footprintsOverlap(BuildTarget a, BuildTarget b)
	{
		if(!a.worldId().equals(b.worldId()))
		{
			return false;
		}
		return netherFootprintsOverlap(a.x(), a.z(), a.halfExtent(), b.x(), b.z(), b.halfExtent());
	}

	private static boolean hasPhysicalPortalConflict(BuildTarget candidate, Set<Block> physicalPortal)
	{
		if(physicalPortal == null || physicalPortal.isEmpty())
		{
			return false;
		}
		int minX = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxZ = Integer.MIN_VALUE;
		for(Block block : physicalPortal)
		{
			minX = Math.min(minX, block.getX());
			maxX = Math.max(maxX, block.getX());
			minZ = Math.min(minZ, block.getZ());
			maxZ = Math.max(maxZ, block.getZ());
		}
		return netherFootprintOverlapsStructureBounds(candidate.x(), candidate.z(), candidate.halfExtent(), minX, maxX, minZ, maxZ);
	}

	private static boolean hasManagedPortalConflict(World world, BuildTarget candidate)
	{
		if(Wormholes.portalManager == null)
		{
			return false;
		}
		for(ILocalPortal portal : Wormholes.portalManager.getLocalPortals())
		{
			if(!VanillaPortalIndex.isManagedKind(portal, VanillaPortalIndex.NETHER_TAG, DimensionalPortalKind.NETHER) || !world.equals(portal.getWorld()))
			{
				continue;
			}
			PortalStructure structure = portal.getStructure();
			AxisAlignedBB area = structure == null ? null : structure.getArea();
			if(area == null)
			{
				continue;
			}
			if(netherFootprintOverlapsStructureBounds(candidate.x(), candidate.z(), candidate.halfExtent(),
					area.getXa(), area.getXb(), area.getZa(), area.getZb()))
			{
				return true;
			}
		}
		return false;
	}

	static int netherBuildHalfExtent(int interiorWidth, int interiorHeight)
	{
		int normalizedWidth = PortalSiteBuilder.netherInteriorWidth(interiorWidth);
		return (normalizedWidth + 1) / 2 + 1 + PortalSiteBuilder.netherPlatformPadding(normalizedWidth, interiorHeight);
	}

	static int netherBuildSpacing(int interiorWidth, int interiorHeight)
	{
		return Math.max(6, netherBuildHalfExtent(interiorWidth, interiorHeight) * 2 + 1);
	}

	static boolean netherFootprintsOverlap(int xA, int zA, int halfExtentA, int xB, int zB, int halfExtentB)
	{
		int separation = halfExtentA + halfExtentB + 1;
		return Math.abs(xA - xB) <= separation && Math.abs(zA - zB) <= separation;
	}

	static boolean netherFootprintOverlapsStructureBounds(int x, int z, int halfExtent, double xa, double xb, double za, double zb)
	{
		int existingMinX = (int) Math.floor(Math.min(xa, xb)) - EXISTING_FOOTPRINT_MARGIN;
		int existingMaxX = (int) Math.floor(Math.max(xa, xb)) + EXISTING_FOOTPRINT_MARGIN;
		int existingMinZ = (int) Math.floor(Math.min(za, zb)) - EXISTING_FOOTPRINT_MARGIN;
		int existingMaxZ = (int) Math.floor(Math.max(za, zb)) + EXISTING_FOOTPRINT_MARGIN;
		return x - halfExtent <= existingMaxX && x + halfExtent >= existingMinX
				&& z - halfExtent <= existingMaxZ && z + halfExtent >= existingMinZ;
	}

	record BuildTarget(UUID worldId, int x, int z, int halfExtent)
	{
	}
}
