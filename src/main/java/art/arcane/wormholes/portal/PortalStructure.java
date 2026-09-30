package art.arcane.wormholes.portal;

import art.arcane.wormholes.util.BukkitJsonDocuments;

import art.arcane.wormholes.geometry.GeometryVector;

import art.arcane.wormholes.util.GeometryPersistence;

import art.arcane.wormholes.util.BukkitGeometry;

import java.util.List;
import java.util.Set;
import java.util.ArrayList;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.Vector;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Cuboid;
import art.arcane.wormholes.util.Direction;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.collection.KMap;
import art.arcane.volmlib.util.collection.KSet;
import art.arcane.volmlib.util.bukkit.WorldIdentity;
import art.arcane.volmlib.util.json.JSONObject;

public class PortalStructure implements IWritable, PortalCellAperture
{
	private AxisAlignedBB captureZone;
	private final PortalGeometry geometry = new PortalGeometry();
	private AxisAlignedBB box;
	private World world;
	private KMap<Direction, AxisAlignedBB> faceCache = new KMap<>();

	private KSet<Location> cornerCache;
	private volatile Location centerCache;




	@Override
	public void saveJSON(JSONObject j)
	{
        JSONObject encoded = new JSONObject(PortalStateCodec.writeGeometry(WorldIdentity.serialize(world), geometry));
        for(String key : encoded.keySet()) {
            j.put(key, encoded.get(key));
        }
	}

	@Override
	public void loadJSON(JSONObject j)
	{
        setWorld(WorldIdentity.resolve(j.getString("worldKey")).orElse(null));
        PortalStateCodec.readGeometry(BukkitJsonDocuments.values(j), geometry);
		rebuildCaptureZone();
		invalidateCache();
	}

	@Override
	public JSONObject toJSON()
	{
		JSONObject o = new JSONObject();
		saveJSON(o);

		return o;
	}

	public World getWorld()
	{
		return world;
	}

	public AxisAlignedBB getBox()
	{
		if(box == null)
		{
			Location min = corner(Direction.W, Direction.D, Direction.N);
			Location max = corner(Direction.E, Direction.U, Direction.S);
			box = new AxisAlignedBB(min.getX(), max.getX(), min.getY(), max.getY(), min.getZ(), max.getZ());
		}

		return box;
	}

	public Location getCenter()
	{
		Location cached = centerCache;
		if(cached == null)
		{
			Location min = corner(Direction.W, Direction.D, Direction.N);
			Location max = corner(Direction.E, Direction.U, Direction.S);
			cached = min.clone().add(max.clone().subtract(min).toVector().multiply(0.5));
			centerCache = cached;
		}
		return cached.clone();
	}

	@Override
	public GeometryVector getApertureCenter()
	{
		return BukkitGeometry.vector(getCenter());
	}

	public Location randomCellCentre()
	{
		GeometryVector block = geometry.randomBlockPosition();
		if(block == null)
		{
			AxisAlignedBB area = getArea();
			if(area == null)
			{
				return null;
			}
			block = area.random();
		}
		return new Location(getWorld(), Math.floor(block.x()) + 0.5D, Math.floor(block.y()) + 0.5D, Math.floor(block.z()) + 0.5D);
	}

	public void setWorld(World world)
	{
		this.world = world;
		centerCache = null;
	}

	public Set<Location> getCorners()
	{
		if(cornerCache == null)
		{
			cornerCache = new KSet<Location>();
			cornerCache.add(corner(Direction.W, Direction.U, Direction.N));
			cornerCache.add(corner(Direction.W, Direction.U, Direction.S));
			cornerCache.add(corner(Direction.W, Direction.D, Direction.N));
			cornerCache.add(corner(Direction.W, Direction.D, Direction.S));
			cornerCache.add(corner(Direction.E, Direction.U, Direction.N));
			cornerCache.add(corner(Direction.E, Direction.U, Direction.S));
			cornerCache.add(corner(Direction.E, Direction.D, Direction.N));
			cornerCache.add(corner(Direction.E, Direction.D, Direction.S));
		}

		return cornerCache;
	}

	private Location corner(Direction x, Direction y, Direction z)
	{
		GeometryVector v = getArea().getCornerVector(x, y, z);
		return new Location(getWorld(), v.getX(), v.getY(), v.getZ());
	}

	public AxisAlignedBB getFace(Direction face)
	{
		if(!faceCache.containsKey(face))
		{
			faceCache.put(face, getArea().getFace(face));
		}

		return faceCache.get(face);
	}

	public AxisAlignedBB getArea()
	{
		return geometry.getArea();
	}

	public long getRevision()
	{
		return geometry.getRevision();
	}

	public void setArea(Cuboid area)
	{
		geometry.setArea(BukkitGeometry.bounds(area));
		rebuildCaptureZone();
		invalidateCache();
	}

	public void setBlocks(Set<Block> blocks)
	{
        if(blocks == null || blocks.isEmpty()) { return; }
        ArrayList<GeometryVector> cells = new ArrayList<>(blocks.size());
        World blockWorld = null;
        for(Block block : blocks) {
            if(block == null || block.getWorld() == null) { continue; }
            blockWorld = block.getWorld();
            cells.add(new GeometryVector(block.getX(), block.getY(), block.getZ()));
        }
        if(cells.isEmpty()) { return; }
        setWorld(blockWorld);
        geometry.setBlocks(cells);
        rebuildCaptureZone();
        invalidateCache();
	}

	public boolean contains(Location location)
	{
		if(location == null || getArea() == null || !getArea().containsPrimitive(location.getX(), location.getY(), location.getZ()))
		{
			return false;
		}

		if(getWorld() != null && location.getWorld() != null && !getWorld().equals(location.getWorld()))
		{
			return false;
		}

		return containsBlock(location.getBlockX(), location.getBlockY(), location.getBlockZ());
	}

	public boolean containsBlock(int x, int y, int z)
	{
        return geometry.containsBlock(x, y, z);
	}

	public boolean containsOrAdjoinsBlock(int x, int y, int z)
	{
        return geometry.containsOrAdjoinsBlock(x, y, z);
	}

	public KList<Vector> getBlockPositions()
	{
		KList<Vector> copy = new KList<Vector>();
		for(GeometryVector block : geometry.getBlockPositions())
		{
			copy.add(BukkitGeometry.bukkit(block));
		}
		return copy;
	}

	public List<AxisAlignedBB> getCachedApertureFaces(Direction face)
	{
        return geometry.getCachedApertureFaces(face);
	}

	public boolean isFullCuboid()
	{
        return geometry.isFullCuboid();
	}

	private void invalidateCache()
	{
		faceCache.clear();

		cornerCache = null;
		box = null;
		centerCache = null;

	}

	public double getSize()
	{
		return getArea().volume();
	}

	public AxisAlignedBB getCaptureZone()
	{
		return captureZone;
	}

	public void rebuildCaptureZone()
	{
		captureZone = geometry.captureZone(Settings.CAPTURE_ZONE_RADIUS);
	}

    public PortalGeometry geometry() {
        return geometry;
    }

}
