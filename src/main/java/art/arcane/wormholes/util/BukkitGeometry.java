package art.arcane.wormholes.util;

import art.arcane.wormholes.geometry.GeometryVector;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.util.Vector;

import java.util.Collection;

public final class BukkitGeometry {
    private BukkitGeometry() {
    }

    public static GeometryVector vector(Vector vector) {
        return vector == null ? null : new GeometryVector(vector.getX(), vector.getY(), vector.getZ());
    }

    public static GeometryVector vector(Location location) {
        return new GeometryVector(location.getX(), location.getY(), location.getZ());
    }

    public static Vector bukkit(GeometryVector vector) {
        return new Vector(vector.x(), vector.y(), vector.z());
    }

    public static Vector bukkit(Direction direction) {
        return new Vector(direction.x(), direction.y(), direction.z());
    }

    public static Location location(GeometryVector vector, World world) {
        return new Location(world, vector.x(), vector.y(), vector.z());
    }

    public static AxisAlignedBB bounds(Cuboid cuboid) {
        Vector minimum = cuboid.getCornerVector(Direction.W, Direction.D, Direction.N);
        Vector maximum = cuboid.getCornerVector(Direction.E, Direction.U, Direction.S);
        return new AxisAlignedBB(minimum.getX(), maximum.getX(), minimum.getY(), maximum.getY(), minimum.getZ(), maximum.getZ());
    }

    public static Collection<Entity> entities(AxisAlignedBB bounds, World world) {
        return world.getNearbyEntities(location(bounds.center(), world), bounds.sizeX() / 2.0D, bounds.sizeY() / 2.0D, bounds.sizeZ() / 2.0D);
    }
}
