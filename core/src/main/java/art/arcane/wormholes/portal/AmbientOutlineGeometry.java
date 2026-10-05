package art.arcane.wormholes.portal;

import java.util.ArrayList;
import java.util.List;

import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.render.ProjectionCellKey;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import art.arcane.wormholes.util.Axis;

public final class AmbientOutlineGeometry
{
	static final int SAMPLES_PER_EDGE = 3;

	private long cachedRevision = Long.MIN_VALUE;
	private Axis cachedAxis;
	private PortalGeometry cachedStructure;
	private List<double[]> cachedPoints;

	public List<double[]> points(long revision, Axis normalAxis, PortalGeometry structure)
	{
		List<double[]> current = cachedPoints;
		if(current != null && cachedRevision == revision && cachedAxis == normalAxis && cachedStructure == structure)
		{
			return current;
		}

		List<double[]> built = build(structure.getBlockPositions(), normalAxis);
		cachedPoints = built;
		cachedRevision = revision;
		cachedAxis = normalAxis;
		cachedStructure = structure;
		return built;
	}

	public static List<double[]> build(List<GeometryVector> blockPositions, Axis normalAxis)
	{
		if(blockPositions == null || blockPositions.isEmpty() || normalAxis == null)
		{
			return List.of();
		}

		LongOpenHashSet occupied = new LongOpenHashSet(Math.max(16, blockPositions.size() * 2));
		List<int[]> cells = new ArrayList<int[]>(blockPositions.size());
		for(GeometryVector position : blockPositions)
		{
			if(position == null)
			{
				continue;
			}

			int x = position.getBlockX();
			int y = position.getBlockY();
			int z = position.getBlockZ();
			if(occupied.add(ProjectionCellKey.pack(x, y, z)))
			{
				cells.add(new int[] {x, y, z});
			}
		}

		if(cells.isEmpty())
		{
			return List.of();
		}

		List<double[]> outline = new ArrayList<double[]>(Math.max(16, cells.size() * 8));
		for(int[] cell : cells)
		{
			PortalBoundarySamples.append(outline, occupied, cell[0], cell[1], cell[2], normalAxis, SAMPLES_PER_EDGE,
				(x, y, z) -> new double[] {x, y, z});
		}

		return List.copyOf(outline);
	}

}
