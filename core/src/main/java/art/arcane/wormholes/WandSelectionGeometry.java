package art.arcane.wormholes;

public final class WandSelectionGeometry {
    public static final int MAX_DRAWN_CELLS = 4096;

    private WandSelectionGeometry() {
    }

	public static int[] selectionMin(int[] a, int[] b)
	{
		return new int[] { Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.min(a[2], b[2]) };
	}

	public static int[] selectionMax(int[] a, int[] b)
	{
		return new int[] { Math.max(a[0], b[0]), Math.max(a[1], b[1]), Math.max(a[2], b[2]) };
	}

	public static int flatAxis(int[] min, int[] max)
	{
		for(int axis = 0; axis < 3; axis++)
		{
			if(min[axis] == max[axis])
			{
				return axis;
			}
		}
		return -1;
	}

	public static long cellCount(int[] min, int[] max)
	{
		return (long) (max[0] - min[0] + 1) * (long) (max[1] - min[1] + 1) * (long) (max[2] - min[2] + 1);
	}

	public static float[] paneBox(int[] min, int[] max, int normalAxis, float thickness, float inset)
	{
		float[] box = new float[6];
		for(int axis = 0; axis < 3; axis++)
		{
			float extent = (float) (max[axis] - min[axis] + 1);
			if(axis == normalAxis)
			{
				box[axis] = thickness;
				box[axis + 3] = (extent - thickness) / 2.0f;
			}
			else
			{
				box[axis] = extent - (inset * 2.0f);
				box[axis + 3] = inset;
			}
		}
		return box;
	}

	public static boolean rayIntersectsBox(double ox, double oy, double oz, double dx, double dy, double dz, double minX, double minY, double minZ, double maxX, double maxY, double maxZ, double range)
	{
		double[] origin = new double[] { ox, oy, oz };
		double[] direction = new double[] { dx, dy, dz };
		double[] lower = new double[] { minX, minY, minZ };
		double[] upper = new double[] { maxX, maxY, maxZ };
		double tMin = 0.0D;
		double tMax = range;
		for(int axis = 0; axis < 3; axis++)
		{
			if(Math.abs(direction[axis]) < 1.0E-9D)
			{
				if(origin[axis] < lower[axis] || origin[axis] > upper[axis])
				{
					return false;
				}
				continue;
			}
			double inverse = 1.0D / direction[axis];
			double t1 = (lower[axis] - origin[axis]) * inverse;
			double t2 = (upper[axis] - origin[axis]) * inverse;
			tMin = Math.max(tMin, Math.min(t1, t2));
			tMax = Math.min(tMax, Math.max(t1, t2));
			if(tMin > tMax)
			{
				return false;
			}
		}
		return true;
	}

}
