package art.arcane.wormholes.portal;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.util.Vector;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import art.arcane.wormholes.util.Axis;

public final class AmbientOutlineGeometry
{
	static final int SAMPLES_PER_EDGE = 3;

	private long cachedRevision = Long.MIN_VALUE;
	private Axis cachedAxis;
	private List<double[]> cachedPoints;

	public List<double[]> points(long revision, Axis normalAxis, List<Vector> blockPositions)
	{
		List<double[]> current = cachedPoints;
		if(current != null && cachedRevision == revision && cachedAxis == normalAxis)
		{
			return current;
		}

		List<double[]> built = build(blockPositions, normalAxis);
		cachedPoints = built;
		cachedRevision = revision;
		cachedAxis = normalAxis;
		return built;
	}

	public static List<double[]> build(List<Vector> blockPositions, Axis normalAxis)
	{
		if(blockPositions == null || blockPositions.isEmpty() || normalAxis == null)
		{
			return List.of();
		}

		LongOpenHashSet occupied = new LongOpenHashSet(Math.max(16, blockPositions.size() * 2));
		List<int[]> cells = new ArrayList<int[]>(blockPositions.size());
		for(Vector position : blockPositions)
		{
			if(position == null)
			{
				continue;
			}

			int x = position.getBlockX();
			int y = position.getBlockY();
			int z = position.getBlockZ();
			if(occupied.add(packCell(x, y, z)))
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
			addCellBoundary(outline, occupied, cell[0], cell[1], cell[2], normalAxis);
		}

		return List.copyOf(outline);
	}

	private static void addCellBoundary(List<double[]> outline, LongOpenHashSet occupied, int x, int y, int z, Axis normalAxis)
	{
		switch(normalAxis)
		{
			case X ->
			{
				if(!occupied.contains(packCell(x, y - 1, z)))
				{
					addLine(outline, x + 0.5D, y, z, x + 0.5D, y, z + 1.0D);
				}
				if(!occupied.contains(packCell(x, y + 1, z)))
				{
					addLine(outline, x + 0.5D, y + 1.0D, z, x + 0.5D, y + 1.0D, z + 1.0D);
				}
				if(!occupied.contains(packCell(x, y, z - 1)))
				{
					addLine(outline, x + 0.5D, y, z, x + 0.5D, y + 1.0D, z);
				}
				if(!occupied.contains(packCell(x, y, z + 1)))
				{
					addLine(outline, x + 0.5D, y, z + 1.0D, x + 0.5D, y + 1.0D, z + 1.0D);
				}
			}
			case Y ->
			{
				if(!occupied.contains(packCell(x - 1, y, z)))
				{
					addLine(outline, x, y + 0.5D, z, x, y + 0.5D, z + 1.0D);
				}
				if(!occupied.contains(packCell(x + 1, y, z)))
				{
					addLine(outline, x + 1.0D, y + 0.5D, z, x + 1.0D, y + 0.5D, z + 1.0D);
				}
				if(!occupied.contains(packCell(x, y, z - 1)))
				{
					addLine(outline, x, y + 0.5D, z, x + 1.0D, y + 0.5D, z);
				}
				if(!occupied.contains(packCell(x, y, z + 1)))
				{
					addLine(outline, x, y + 0.5D, z + 1.0D, x + 1.0D, y + 0.5D, z + 1.0D);
				}
			}
			case Z ->
			{
				if(!occupied.contains(packCell(x - 1, y, z)))
				{
					addLine(outline, x, y, z + 0.5D, x, y + 1.0D, z + 0.5D);
				}
				if(!occupied.contains(packCell(x + 1, y, z)))
				{
					addLine(outline, x + 1.0D, y, z + 0.5D, x + 1.0D, y + 1.0D, z + 0.5D);
				}
				if(!occupied.contains(packCell(x, y - 1, z)))
				{
					addLine(outline, x, y, z + 0.5D, x + 1.0D, y, z + 0.5D);
				}
				if(!occupied.contains(packCell(x, y + 1, z)))
				{
					addLine(outline, x, y + 1.0D, z + 0.5D, x + 1.0D, y + 1.0D, z + 0.5D);
				}
			}
		}
	}

	private static void addLine(List<double[]> points, double x0, double y0, double z0, double x1, double y1, double z1)
	{
		for(int sample = 0; sample < SAMPLES_PER_EDGE; sample++)
		{
			double t = (sample + 0.5D) / SAMPLES_PER_EDGE;
			points.add(new double[] {x0 + ((x1 - x0) * t), y0 + ((y1 - y0) * t), z0 + ((z1 - z0) * t)});
		}
	}

	private static long packCell(int x, int y, int z)
	{
		return (((long) x & 0x3FFFFFFL) << 38) | ((((long) y) & 0xFFFL) << 26) | (((long) z) & 0x3FFFFFFL);
	}
}
