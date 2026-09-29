package art.arcane.wormholes.portal;

import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ConcurrentHashMap;

public final class PortalGeometry implements PortalCellAperture {
    private final LongOpenHashSet blockKeys = new LongOpenHashSet();
    private final List<GeometryVector> blockPositions = new ArrayList<>();
    private final ConcurrentHashMap<Direction, List<AxisAlignedBB>> apertureFaceCache = new ConcurrentHashMap<>();
    private AxisAlignedBB area;
    private long revision;

    public void setArea(AxisAlignedBB area) {
        this.area = area;
        blockKeys.clear();
        blockPositions.clear();
        blockKeys.ensureCapacity(getBoundingBlockVolume());
        for (int x = (int) Math.floor(area.getXa()); x <= (int) Math.floor(area.getXb()); x++) {
            for (int y = (int) Math.floor(area.getYa()); y <= (int) Math.floor(area.getYb()); y++) {
                for (int z = (int) Math.floor(area.getZa()); z <= (int) Math.floor(area.getZb()); z++) {
                    addBlockCell(x, y, z);
                }
            }
        }
        invalidate();
    }

    public void restore(AxisAlignedBB area, Collection<GeometryVector> cells) {
        this.area = area;
        blockKeys.clear();
        blockPositions.clear();
        for (GeometryVector cell : cells) {
            addBlockCell(cell.getBlockX(), cell.getBlockY(), cell.getBlockZ());
        }
        invalidate();
    }

    public void setBlocks(Collection<GeometryVector> cells) {
        if (cells == null || cells.isEmpty()) {
            return;
        }
        blockKeys.clear();
        blockPositions.clear();
        int xa = Integer.MAX_VALUE;
        int ya = Integer.MAX_VALUE;
        int za = Integer.MAX_VALUE;
        int xb = Integer.MIN_VALUE;
        int yb = Integer.MIN_VALUE;
        int zb = Integer.MIN_VALUE;
        for (GeometryVector cell : cells) {
            int x = cell.getBlockX();
            int y = cell.getBlockY();
            int z = cell.getBlockZ();
            addBlockCell(x, y, z);
            xa = Math.min(xa, x);
            ya = Math.min(ya, y);
            za = Math.min(za, z);
            xb = Math.max(xb, x);
            yb = Math.max(yb, y);
            zb = Math.max(zb, z);
        }
        area = new AxisAlignedBB(xa, xb + 0.999D, ya, yb + 0.999D, za, zb + 0.999D);
        invalidate();
    }

    @Override
    public AxisAlignedBB getArea() {
        return area;
    }

    @Override
    public GeometryVector getApertureCenter() {
        return area.min().add(area.max().subtract(area.min()).multiply(0.5D));
    }

    public long getRevision() {
        return revision;
    }

    public List<GeometryVector> getBlockPositions() {
        return List.copyOf(blockPositions);
    }

    public GeometryVector randomBlockPosition() {
        int size = blockPositions.size();
        return size == 0 ? null : blockPositions.get(ThreadLocalRandom.current().nextInt(size));
    }

    public boolean contains(GeometryVector point) {
        return point != null && area != null && area.containsPrimitive(point.x(), point.y(), point.z())
            && containsBlock(point.getBlockX(), point.getBlockY(), point.getBlockZ());
    }

    public AxisAlignedBB captureZone(double radius) {
        GeometryVector padding = new GeometryVector(radius, radius, radius);
        return area == null ? null : new AxisAlignedBB(area.min().subtract(padding), area.max().add(padding));
    }

    private void addBlockCell(int x, int y, int z) {
        if (blockKeys.add(packBlockKey(x, y, z))) {
            blockPositions.add(new GeometryVector(x, y, z));
        }
    }

    private void invalidate() {
        apertureFaceCache.clear();
        revision++;
    }
	public boolean containsBlock(int x, int y, int z)
	{
		if(blockKeys.isEmpty())
		{
			return getArea() != null && getArea().containsPrimitive(x + 0.5D, y + 0.5D, z + 0.5D);
		}

		return blockKeys.contains(packBlockKey(x, y, z));
	}

	public boolean containsOrAdjoinsBlock(int x, int y, int z)
	{
		for(int offsetX = -1; offsetX <= 1; offsetX++)
		{
			for(int offsetY = -1; offsetY <= 1; offsetY++)
			{
				for(int offsetZ = -1; offsetZ <= 1; offsetZ++)
				{
					if(containsBlock(x + offsetX, y + offsetY, z + offsetZ))
					{
						return true;
					}
				}
			}
		}

		return false;
	}

	public List<AxisAlignedBB> getCachedApertureFaces(Direction face)
	{
		List<AxisAlignedBB> cached = apertureFaceCache.get(face);
		if(cached != null)
		{
			return cached;
		}

		List<AxisAlignedBB> faces = new ArrayList<>();
		if(blockPositions.isEmpty() || isFullCuboid())
		{
			faces.add(getArea().getFace(face));
		}
		else
		{
			for(GeometryVector block : blockPositions)
			{
				faces.add(getBlockBox(block.getBlockX(), block.getBlockY(), block.getBlockZ()).getFace(face));
			}
		}

		List<AxisAlignedBB> immutable = List.copyOf(faces);
		List<AxisAlignedBB> raced = apertureFaceCache.putIfAbsent(face, immutable);
		return raced == null ? immutable : raced;
	}

	public boolean isFullCuboid()
	{
		return !blockKeys.isEmpty() && blockKeys.size() == getBoundingBlockVolume();
	}

	private int getBoundingBlockVolume()
	{
		int xa = (int) Math.floor(getArea().getXa());
		int ya = (int) Math.floor(getArea().getYa());
		int za = (int) Math.floor(getArea().getZa());
		int xb = (int) Math.floor(getArea().getXb());
		int yb = (int) Math.floor(getArea().getYb());
		int zb = (int) Math.floor(getArea().getZb());
		return Math.max(0, (xb - xa + 1) * (yb - ya + 1) * (zb - za + 1));
	}

	private AxisAlignedBB getBlockBox(int x, int y, int z)
	{
		return new AxisAlignedBB(x, x + 0.999D, y, y + 0.999D, z, z + 0.999D);
	}

	public static long packBlockKey(int x, int y, int z)
	{
		return (((long) x & 0x3FFFFFFL) << 38) | ((((long) y) & 0xFFFL) << 26) | (((long) z) & 0x3FFFFFFL);
	}

	public static int unpackBlockX(long key)
	{
		long raw = (key >> 38) & 0x3FFFFFFL;
		return (int) ((raw << 38) >> 38);
	}

	public static int unpackBlockY(long key)
	{
		long raw = (key >> 26) & 0xFFFL;
		return (int) ((raw << 52) >> 52);
	}

	public static int unpackBlockZ(long key)
	{
		long raw = key & 0x3FFFFFFL;
		return (int) ((raw << 38) >> 38);
	}
}
