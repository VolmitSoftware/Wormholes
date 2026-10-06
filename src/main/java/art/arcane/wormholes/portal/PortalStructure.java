package art.arcane.wormholes.portal;

import art.arcane.wormholes.util.BukkitJsonDocuments;

import art.arcane.optics.math.Vec3;

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
import art.arcane.optics.math.Box;
import art.arcane.wormholes.util.Cuboid;
import art.arcane.optics.math.Face;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.collection.KMap;
import art.arcane.volmlib.util.collection.KSet;
import art.arcane.volmlib.util.bukkit.WorldIdentity;
import art.arcane.volmlib.util.json.JSONObject;
import art.arcane.optics.aperture.ApertureCells;
import art.arcane.optics.aperture.CellAperture;

public class PortalStructure implements IWritable, CellAperture
{
	private Box captureZone;
	private final ApertureCells geometry = new ApertureCells();
	private Box box;
	private World world;
	private KMap<Face, Box> faceCache = new KMap<>();

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

	public Box getBox()
	{
		if(box == null)
		{
			Location min = corner(Face.W, Face.D, Face.N);
			Location max = corner(Face.E, Face.U, Face.S);
			box = new Box(min.getX(), max.getX(), min.getY(), max.getY(), min.getZ(), max.getZ());
		}

		return box;
	}

	public Location getCenter()
	{
		Location cached = centerCache;
		if(cached == null)
		{
			Vec3 center = geometry.getApertureCenter();
			cached = new Location(getWorld(), center.x(), center.y(), center.z());
			centerCache = cached;
		}
		return cached.clone();
	}

	@Override
	public Vec3 getApertureCenter()
	{
		return geometry.getApertureCenter();
	}

	public Location randomCellCentre()
	{
		Vec3 centre = geometry.randomCellCentre();
		return centre == null ? null : new Location(getWorld(), centre.x(), centre.y(), centre.z());
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
			cornerCache.add(corner(Face.W, Face.U, Face.N));
			cornerCache.add(corner(Face.W, Face.U, Face.S));
			cornerCache.add(corner(Face.W, Face.D, Face.N));
			cornerCache.add(corner(Face.W, Face.D, Face.S));
			cornerCache.add(corner(Face.E, Face.U, Face.N));
			cornerCache.add(corner(Face.E, Face.U, Face.S));
			cornerCache.add(corner(Face.E, Face.D, Face.N));
			cornerCache.add(corner(Face.E, Face.D, Face.S));
		}

		return cornerCache;
	}

	private Location corner(Face x, Face y, Face z)
	{
		Vec3 v = getArea().getCornerVector(x, y, z);
		return new Location(getWorld(), v.getX(), v.getY(), v.getZ());
	}

	public Box getFace(Face face)
	{
		if(!faceCache.containsKey(face))
		{
			faceCache.put(face, getArea().getFace(face));
		}

		return faceCache.get(face);
	}

	public Box getArea()
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
        ArrayList<Vec3> cells = new ArrayList<>(blocks.size());
        World blockWorld = null;
        for(Block block : blocks) {
            if(block == null || block.getWorld() == null) { continue; }
            blockWorld = block.getWorld();
            cells.add(new Vec3(block.getX(), block.getY(), block.getZ()));
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
		for(Vec3 block : geometry.getBlockPositions())
		{
			copy.add(BukkitGeometry.bukkit(block));
		}
		return copy;
	}

	public List<Box> getCachedApertureFaces(Face face)
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

	public Box getCaptureZone()
	{
		return captureZone;
	}

	public void rebuildCaptureZone()
	{
		captureZone = geometry.captureZone(Settings.CAPTURE_ZONE_RADIUS);
	}

    public ApertureCells geometry() {
        return geometry;
    }

}
