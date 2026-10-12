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

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.portal.DimensionalPortalKind;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;

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
			PortalStructure structure = portal.getStructure();
			if((!kind.isNetherPortal() && kind != DimensionalPortalKind.END_SOURCE) || structure == null || structure.getWorld() == null || portal.isDestroyed())
			{
				continue;
			}
			activePortals.add(portal.getId());
			World world = structure.getWorld();
			CachedFramePositions cached = framePositionCache.get(portal.getId());
			long revision = structure.getBuiltGeometry().getRevision();
			Face ambiguousNormal = kind == DimensionalPortalKind.SHAPED_NETHER ? portal.getDirection() : null;
			if(cached == null || cached.structure() != structure || cached.revision() != revision || cached.ambiguousNormal() != ambiguousNormal)
			{
				cached = new CachedFramePositions(structure, revision, ambiguousNormal, expectedFramePositions(structure, ambiguousNormal));
				framePositionCache.put(portal.getId(), cached);
			}
			for(FramePosition position : cached.positions())
			{
				FrameChunk chunk = new FrameChunk(world, position.x() >> 4, position.z() >> 4);
				byChunk.computeIfAbsent(chunk, ignored -> new ArrayList<FrameCheck>()).add(new FrameCheck(portal, position, kind));
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
		if(!isFrameMaterial(DimensionalPortalKind.SHAPED_NETHER, brokenType))
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
			if(!isFrameMaterial(DimensionalPortalKind.SHAPED_NETHER, material))
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
			if(!portal.isDestroyed() && !isFrameMaterial(check.kind(), chunk.world().getBlockAt(position.x(), position.y(), position.z()).getType()))
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
			if(!isFrameMaterial(portal.getDimensionalPortalKind(), broken.getType()))
			{
				continue;
			}
			PortalStructure structure = portal.getStructure();
			if(structure == null || structure.getWorld() == null || !world.equals(structure.getWorld()))
			{
				continue;
			}
			Set<FramePosition> cells = builtCells(structure);
			if(portal.getDimensionalPortalKind() == DimensionalPortalKind.SHAPED_NETHER
					? expectedFramePositions(cells, portal.getDirection()).contains(new FramePosition(broken.getX(), broken.getY(), broken.getZ())) : isOpenFrameBlock(broken, cells))
			{
				return portal;
			}
		}
		return null;
	}

	static boolean isFrameMaterial(DimensionalPortalKind kind, Material material)
	{
		return switch(kind)
		{
			case NETHER -> material == Material.OBSIDIAN;
			case END_SOURCE -> material == Material.END_PORTAL_FRAME;
			case SHAPED_NETHER -> material != Material.AIR && material != Material.CAVE_AIR && material != Material.VOID_AIR && material != Material.FIRE
					&& material != Material.SOUL_FIRE && material != Material.NETHER_PORTAL;
			default -> false;
		};
	}

	private static boolean isOpenFrameBlock(Block broken, Set<FramePosition> cells)
	{
		int x = broken.getX();
		int y = broken.getY();
		int z = broken.getZ();
		if(cells.isEmpty() || cells.contains(new FramePosition(x, y, z)))
		{
			return false;
		}
		Face normal = physicalNormal(cells, null);
		return adjacentPortalCell(cells, x, y, z, normal.x() == 0, normal.y() == 0, normal.z() == 0);
	}

	private static boolean adjacentPortalCell(Set<FramePosition> cells, int x, int y, int z, boolean varyX, boolean varyY, boolean varyZ)
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
				if(cells.contains(new FramePosition(nx, ny, nz)))
				{
					return true;
				}
			}
		}
		return false;
	}

	static Set<FramePosition> expectedFramePositions(PortalStructure structure, Face ambiguousNormal)
	{
		return expectedFramePositions(builtCells(structure), ambiguousNormal);
	}

	private static Set<FramePosition> builtCells(PortalStructure structure)
	{
		Set<FramePosition> cells = new HashSet<FramePosition>();
		for(Vec3d vector : structure.getBuiltGeometry().getBlockPositions())
		{
			cells.add(new FramePosition(vector.blockX(), vector.blockY(), vector.blockZ()));
		}
		return cells;
	}

	static Set<FramePosition> expectedFramePositions(Set<FramePosition> cells, Face ambiguousNormal)
	{
		if(cells.isEmpty())
		{
			return Set.of();
		}
		Face normal = physicalNormal(cells, ambiguousNormal);
		Set<FramePosition> frame = new HashSet<FramePosition>();
		for(FramePosition cell : cells)
		{
			if(normal.x() == 0)
			{
				addFrameNeighbor(cells, frame, cell.x() - 1, cell.y(), cell.z());
				addFrameNeighbor(cells, frame, cell.x() + 1, cell.y(), cell.z());
			}
			if(normal.y() == 0)
			{
				addFrameNeighbor(cells, frame, cell.x(), cell.y() - 1, cell.z());
				addFrameNeighbor(cells, frame, cell.x(), cell.y() + 1, cell.z());
			}
			if(normal.z() == 0)
			{
				addFrameNeighbor(cells, frame, cell.x(), cell.y(), cell.z() - 1);
				addFrameNeighbor(cells, frame, cell.x(), cell.y(), cell.z() + 1);
			}
		}
		return Set.copyOf(frame);
	}

	private static Face physicalNormal(Set<FramePosition> cells, Face ambiguousNormal)
	{
		FramePosition first = cells.iterator().next();
		int minX = first.x();
		int maxX = minX;
		int minY = first.y();
		int maxY = minY;
		int minZ = first.z();
		int maxZ = minZ;
		for(FramePosition cell : cells)
		{
			minX = Math.min(minX, cell.x());
			maxX = Math.max(maxX, cell.x());
			minY = Math.min(minY, cell.y());
			maxY = Math.max(maxY, cell.y());
			minZ = Math.min(minZ, cell.z());
			maxZ = Math.max(maxZ, cell.z());
		}
		int xDepth = maxX - minX;
		int yDepth = maxY - minY;
		int zDepth = maxZ - minZ;
		int flatAxes = (xDepth == 0 ? 1 : 0) + (yDepth == 0 ? 1 : 0) + (zDepth == 0 ? 1 : 0);
		if(flatAxes > 1 && ambiguousNormal != null)
		{
			return ambiguousNormal;
		}
		if(xDepth <= yDepth && xDepth <= zDepth)
		{
			return Face.E;
		}
		return yDepth <= zDepth ? Face.U : Face.N;
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

	private record FrameCheck(ILocalPortal portal, FramePosition position, DimensionalPortalKind kind)
	{
	}

	private record FrameChunk(World world, int chunkX, int chunkZ)
	{
	}

	private record CachedFramePositions(PortalStructure structure, long revision, Face ambiguousNormal, Set<FramePosition> positions)
	{
	}
}
