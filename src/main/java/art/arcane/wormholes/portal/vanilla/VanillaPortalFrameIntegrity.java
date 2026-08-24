package art.arcane.wormholes.portal.vanilla;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.Vector;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.portal.DimensionalPortalKind;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.util.AxisAlignedBB;

final class VanillaPortalFrameIntegrity
{
	private static final int REFUSAL_REPORT_INTERVAL_PASSES = 30;

	private final Map<UUID, CachedFramePositions> framePositionCache = new ConcurrentHashMap<UUID, CachedFramePositions>();
	private int refusedValidationPasses;

	void validate()
	{
		if(!Settings.REPLACE_NETHER_AND_END_PORTALS || Wormholes.portalManager == null)
		{
			return;
		}
		Map<FrameChunk, List<FrameCheck>> byChunk = new HashMap<FrameChunk, List<FrameCheck>>();
		Set<UUID> activePortals = new HashSet<UUID>();
		for(ILocalPortal portal : Wormholes.portalManager.getLocalPortals())
		{
			DimensionalPortalKind kind = portal.getDimensionalPortalKind();
			Material expected = kind == DimensionalPortalKind.NETHER ? Material.OBSIDIAN
					: kind == DimensionalPortalKind.END_SOURCE ? Material.END_PORTAL_FRAME : null;
			PortalStructure structure = portal.getStructure();
			if(expected == null || structure == null || structure.getWorld() == null || portal.isDestroyed())
			{
				continue;
			}
			activePortals.add(portal.getId());
			World world = structure.getWorld();
			CachedFramePositions cached = framePositionCache.get(portal.getId());
			if(cached == null || cached.structure() != structure)
			{
				cached = new CachedFramePositions(structure, expectedFramePositions(structure));
				framePositionCache.put(portal.getId(), cached);
			}
			for(FramePosition position : cached.positions())
			{
				FrameChunk chunk = new FrameChunk(world, position.x() >> 4, position.z() >> 4);
				byChunk.computeIfAbsent(chunk, ignored -> new ArrayList<FrameCheck>()).add(new FrameCheck(portal, position, expected));
			}
		}
		framePositionCache.keySet().retainAll(activePortals);
		int refused = 0;
		for(Map.Entry<FrameChunk, List<FrameCheck>> entry : byChunk.entrySet())
		{
			FrameChunk chunk = entry.getKey();
			List<FrameCheck> checks = List.copyOf(entry.getValue());
			if(!FoliaScheduler.runRegion(Wormholes.instance, chunk.world(), chunk.chunkX(), chunk.chunkZ(), () -> validateFrameChunk(chunk, checks)))
			{
				refused++;
			}
		}
		reportRefusedValidations(refused);
	}

	private void reportRefusedValidations(int refused)
	{
		if(refused <= 0)
		{
			refusedValidationPasses = 0;
			return;
		}
		if(refusedValidationPasses % REFUSAL_REPORT_INTERVAL_PASSES == 0)
		{
			Wormholes.w("[vanilla-portal] frame validation skipped " + refused + " chunk(s); the owning region refused the check");
		}
		refusedValidationPasses++;
	}

	void scheduleBreakCheck(Block broken, BooleanSupplier stillBroken)
	{
		Material brokenType = broken.getType();
		if(brokenType != Material.OBSIDIAN && brokenType != Material.END_PORTAL_FRAME)
		{
			return;
		}
		ILocalPortal portal = findFramedPortal(broken);
		if(portal != null)
		{
			boolean scheduled = FoliaScheduler.runRegion(Wormholes.instance, broken.getLocation(), () ->
			{
				if(stillBroken.getAsBoolean())
				{
					breakPortalPair(portal);
				}
			}, 1L);
			if(!scheduled)
			{
				Wormholes.w("[vanilla-portal] region refused the frame break check at " + broken.getX() + "," + broken.getY() + "," + broken.getZ()
						+ " in " + broken.getWorld().getName() + "; the periodic frame validation will catch it");
			}
		}
	}

	void breakDamagedFrames(List<Block> blocks)
	{
		Set<ILocalPortal> brokenPortals = new HashSet<ILocalPortal>();
		for(Block block : blocks)
		{
			Material material = block.getType();
			if(material != Material.OBSIDIAN && material != Material.END_PORTAL_FRAME)
			{
				continue;
			}
			ILocalPortal portal = findFramedPortal(block);
			if(portal != null)
			{
				brokenPortals.add(portal);
			}
		}
		for(ILocalPortal portal : brokenPortals)
		{
			portal.destroy();
		}
	}

	private static void breakPortalPair(ILocalPortal portal)
	{
		if(portal == null)
		{
			return;
		}
		portal.destroy();
		Wormholes.v(() -> "[vanilla-portal] frame broken -> portal pair destroyed");
	}

	private static void validateFrameChunk(FrameChunk chunk, List<FrameCheck> checks)
	{
		if(!chunk.world().isChunkLoaded(chunk.chunkX(), chunk.chunkZ()))
		{
			return;
		}
		for(FrameCheck check : checks)
		{
			ILocalPortal portal = check.portal();
			FramePosition position = check.position();
			if(!portal.isDestroyed() && chunk.world().getBlockAt(position.x(), position.y(), position.z()).getType() != check.expected())
			{
				portal.destroy();
			}
		}
	}

	private static ILocalPortal findFramedPortal(Block broken)
	{
		if(Wormholes.portalManager == null)
		{
			return null;
		}
		World world = broken.getWorld();
		for(ILocalPortal portal : Wormholes.portalManager.getLocalPortals())
		{
			if((broken.getType() == Material.OBSIDIAN && !VanillaPortalIndex.isManagedKind(portal, VanillaPortalIndex.NETHER_TAG, DimensionalPortalKind.NETHER))
					|| (broken.getType() == Material.END_PORTAL_FRAME && !VanillaPortalIndex.isManagedKind(portal, VanillaPortalIndex.END_TAG, DimensionalPortalKind.END_SOURCE)))
			{
				continue;
			}
			PortalStructure structure = portal.getStructure();
			if(structure == null || structure.getWorld() == null || !world.equals(structure.getWorld()))
			{
				continue;
			}
			if(isOpenFrameBlock(broken, structure))
			{
				return portal;
			}
		}
		return null;
	}

	private static boolean isOpenFrameBlock(Block broken, PortalStructure structure)
	{
		int x = broken.getX();
		int y = broken.getY();
		int z = broken.getZ();
		if(structure.containsBlock(x, y, z))
		{
			return false;
		}
		AxisAlignedBB area = structure.getArea();
		if(area == null)
		{
			return false;
		}
		double sx = area.sizeX();
		double sy = area.sizeY();
		double sz = area.sizeZ();
		if(sz <= sx && sz <= sy)
		{
			return adjacentPortalCell(structure, x, y, z, true, true, false);
		}
		if(sy <= sx && sy <= sz)
		{
			return adjacentPortalCell(structure, x, y, z, true, false, true);
		}
		return adjacentPortalCell(structure, x, y, z, false, true, true);
	}

	private static boolean adjacentPortalCell(PortalStructure structure, int x, int y, int z, boolean varyX, boolean varyY, boolean varyZ)
	{
		for(int a = -1; a <= 1; a++)
		{
			for(int b = -1; b <= 1; b++)
			{
				if(a == 0 && b == 0)
				{
					continue;
				}
				int nx = x;
				int ny = y;
				int nz = z;
				if(varyX && varyY)
				{
					nx = x + a;
					ny = y + b;
				}
				else if(varyX && varyZ)
				{
					nx = x + a;
					nz = z + b;
				}
				else
				{
					ny = y + a;
					nz = z + b;
				}
				if(structure.containsBlock(nx, ny, nz))
				{
					return true;
				}
			}
		}
		return false;
	}

	private static Set<FramePosition> expectedFramePositions(PortalStructure structure)
	{
		Set<FramePosition> cells = new HashSet<FramePosition>();
		for(Vector vector : structure.getBlockPositions())
		{
			cells.add(new FramePosition(vector.getBlockX(), vector.getBlockY(), vector.getBlockZ()));
		}
		return expectedFramePositions(cells);
	}

	static Set<FramePosition> expectedFramePositions(Set<FramePosition> cells)
	{
		if(cells.isEmpty())
		{
			return Set.of();
		}
		int minX = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int minY = Integer.MAX_VALUE;
		int maxY = Integer.MIN_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxZ = Integer.MIN_VALUE;
		for(FramePosition cell : cells)
		{
			minX = Math.min(minX, cell.x());
			maxX = Math.max(maxX, cell.x());
			minY = Math.min(minY, cell.y());
			maxY = Math.max(maxY, cell.y());
			minZ = Math.min(minZ, cell.z());
			maxZ = Math.max(maxZ, cell.z());
		}
		Set<FramePosition> frame = new HashSet<FramePosition>();
		for(FramePosition cell : cells)
		{
			if(minZ == maxZ)
			{
				addFrameNeighbor(cells, frame, cell.x() - 1, cell.y(), cell.z());
				addFrameNeighbor(cells, frame, cell.x() + 1, cell.y(), cell.z());
				addFrameNeighbor(cells, frame, cell.x(), cell.y() - 1, cell.z());
				addFrameNeighbor(cells, frame, cell.x(), cell.y() + 1, cell.z());
			}
			else if(minY == maxY)
			{
				addFrameNeighbor(cells, frame, cell.x() - 1, cell.y(), cell.z());
				addFrameNeighbor(cells, frame, cell.x() + 1, cell.y(), cell.z());
				addFrameNeighbor(cells, frame, cell.x(), cell.y(), cell.z() - 1);
				addFrameNeighbor(cells, frame, cell.x(), cell.y(), cell.z() + 1);
			}
			else if(minX == maxX)
			{
				addFrameNeighbor(cells, frame, cell.x(), cell.y() - 1, cell.z());
				addFrameNeighbor(cells, frame, cell.x(), cell.y() + 1, cell.z());
				addFrameNeighbor(cells, frame, cell.x(), cell.y(), cell.z() - 1);
				addFrameNeighbor(cells, frame, cell.x(), cell.y(), cell.z() + 1);
			}
		}
		return Set.copyOf(frame);
	}

	private static void addFrameNeighbor(Set<FramePosition> cells, Set<FramePosition> frame, int x, int y, int z)
	{
		FramePosition position = new FramePosition(x, y, z);
		if(!cells.contains(position))
		{
			frame.add(position);
		}
	}

	record FramePosition(int x, int y, int z)
	{
	}

	private record FrameCheck(ILocalPortal portal, FramePosition position, Material expected)
	{
	}

	private record FrameChunk(World world, int chunkX, int chunkZ)
	{
	}

	private record CachedFramePositions(PortalStructure structure, Set<FramePosition> positions)
	{
	}
}
