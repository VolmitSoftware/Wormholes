package art.arcane.wormholes.portal;

import java.util.ArrayList;
import java.util.List;

import art.arcane.optics.math.Vec3d;
import art.arcane.optics.math.CellKeys;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import art.arcane.optics.math.Axis;
import art.arcane.optics.aperture.ApertureCells;
import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.aperture.BoundarySamples;
import art.arcane.optics.aperture.ShapeBoundarySamples;

public final class AmbientOutlineGeometry
{
	static final int SAMPLES_PER_EDGE = 3;
	private static final double SHAPE_SAMPLE_SPACING = 1.0D / SAMPLES_PER_EDGE;

	private long cachedRevision = Long.MIN_VALUE;
	private Axis cachedAxis;
	private ApertureCells cachedStructure;
	private ApertureDescriptor cachedShape;
	private List<double[]> cachedPoints;

	public List<double[]> points(long revision, Axis normalAxis, ApertureCells structure, ApertureDescriptor shapeOutline)
	{
		List<double[]> current = cachedPoints;
		if(current != null && cachedRevision == revision && cachedAxis == normalAxis && cachedStructure == structure && cachedShape == shapeOutline)
		{
			return current;
		}

		List<double[]> built = shapeOutline == null || shapeOutline.shape().isFull()
			? build(structure.getBlockPositions(), normalAxis)
			: shaped(shapeOutline);
		cachedPoints = built;
		cachedRevision = revision;
		cachedAxis = normalAxis;
		cachedStructure = structure;
		cachedShape = shapeOutline;
		return built;
	}

	public static List<double[]> build(List<Vec3d> blockPositions, Axis normalAxis)
	{
		if(blockPositions == null || blockPositions.isEmpty() || normalAxis == null)
		{
			return List.of();
		}

		LongOpenHashSet occupied = new LongOpenHashSet(Math.max(16, blockPositions.size() * 2));
		List<int[]> cells = new ArrayList<int[]>(blockPositions.size());
		for(Vec3d position : blockPositions)
		{
			if(position == null)
			{
				continue;
			}

			int x = position.blockX();
			int y = position.blockY();
			int z = position.blockZ();
			if(occupied.add(CellKeys.pack(x, y, z)))
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
			BoundarySamples.append(outline, occupied, cell[0], cell[1], cell[2], normalAxis, SAMPLES_PER_EDGE,
				(x, y, z) -> new double[] {x, y, z});
		}

		return List.copyOf(outline);
	}

	private static List<double[]> shaped(ApertureDescriptor shapeOutline)
	{
		List<double[]> outline = new ArrayList<double[]>();
		ShapeBoundarySamples.append(outline, shapeOutline, SHAPE_SAMPLE_SPACING, (x, y, z) -> new double[] {x, y, z});
		return List.copyOf(outline);
	}
}
