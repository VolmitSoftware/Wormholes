package art.arcane.wormholes.portal.vanilla;

import java.util.ArrayList;
import java.util.List;

public final class NetherSitePlan {
    private static final int MIN_INTERIOR_WIDTH = 1;
    private static final int MIN_INTERIOR_HEIGHT = 2;
    private static final int MAX_INTERIOR = 21;
    private static final int PLATFORM_SIZE_STEP = 7;
    private static final int MAX_PLATFORM_PADDING = 3;
    private static final int OPENING_CLEARANCE_HEIGHT = 3;

    private NetherSitePlan() {
    }

	public static List<Mutation> plan(Options options)
	{
		int centerX = options.centerX();
		int baseY = options.baseY();
		int centerZ = options.centerZ();
		boolean alongX = options.alongX();
		int interiorWidth = options.width();
		int interiorHeight = options.height();
		int width = netherInteriorWidth(interiorWidth);
		int height = Math.max(MIN_INTERIOR_HEIGHT, Math.min(MAX_INTERIOR, interiorHeight));
		int ax = alongX ? 1 : 0;
		int az = alongX ? 0 : 1;
		int nx = alongX ? 0 : 1;
		int nz = alongX ? 1 : 0;
		int bx = centerX - (alongX ? width / 2 : 0);
		int bz = centerZ - (alongX ? 0 : width / 2);
		int padding = netherPlatformPadding(width, height);
		List<Mutation> mutations = new ArrayList<Mutation>();

		for(int u = -1; u <= width; u++)
		{
			for(int v = -1; v <= height; v++)
			{
				if(isNetherFrameEdge(u, v, width, height))
				{
					mutations.add(new Mutation(bx + ax * u, baseY + v, bz + az * u, Material.OBSIDIAN, false, false));
				}
			}
		}

		for(int u = -1 - padding; u <= width + padding; u++)
		{
			for(int n = -padding; n <= padding; n++)
			{
				if(n == 0 && u >= -1 && u <= width)
				{
					continue;
				}
				mutations.add(new Mutation(bx + ax * u + nx * n, baseY - 1, bz + az * u + nz * n,
						Material.NETHERRACK, true, false));
			}
		}

		for(int u = -1 - padding; u <= width + padding; u++)
		{
			for(int v = 0; v < OPENING_CLEARANCE_HEIGHT; v++)
			{
				for(int n = -padding; n <= padding; n++)
				{
					if(n == 0 && u >= -1 && u <= width)
					{
						continue;
					}
					mutations.add(new Mutation(bx + ax * u + nx * n, baseY + v, bz + az * u + nz * n, Material.AIR, false, false));
				}
			}
		}

		for(int u = 0; u < width; u++)
		{
			for(int v = 0; v < height; v++)
			{
				mutations.add(new Mutation(bx + ax * u, baseY + v, bz + az * u, Material.AIR, false, true));
			}
		}
		return List.copyOf(mutations);
	}

	public static boolean isNetherFrameEdge(int u, int v, int width, int height)
	{
		return u == -1 || u == width || v == -1 || v == height;
	}

	public static int netherInteriorWidth(int requestedWidth)
	{
		return Math.max(MIN_INTERIOR_WIDTH, Math.min(MAX_INTERIOR, requestedWidth));
	}

	public static int netherPlatformPadding(int interiorWidth, int interiorHeight)
	{
		int size = Math.max(netherInteriorWidth(interiorWidth), Math.max(MIN_INTERIOR_HEIGHT, Math.min(MAX_INTERIOR, interiorHeight)));
		return Math.min(MAX_PLATFORM_PADDING, Math.max(1, (size + PLATFORM_SIZE_STEP - 1) / PLATFORM_SIZE_STEP));
	}

	public static boolean netherFrameFits(int baseY, int interiorHeight, int minHeight, int maxHeight)
	{
		int height = Math.max(MIN_INTERIOR_HEIGHT, Math.min(MAX_INTERIOR, interiorHeight));
		return baseY - 1 >= minHeight && baseY + height <= maxHeight - 3;
	}

    public record Options(int centerX, int baseY, int centerZ, boolean alongX, int width, int height) {
    }

    public record Mutation(int x, int y, int z, Material material, boolean preserveObsidian, boolean interior) {
    }

    public enum Material {
        AIR,
        OBSIDIAN,
        NETHERRACK
    }
}
