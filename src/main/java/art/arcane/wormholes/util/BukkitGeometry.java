package art.arcane.wormholes.util;

import art.arcane.optics.math.Vec3;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.util.Vector;

import java.util.Collection;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;

public final class BukkitGeometry {
    private BukkitGeometry() {
    }

    public static Vec3 vector(Vector vector) {
        return vector == null ? null : new Vec3(vector.getX(), vector.getY(), vector.getZ());
    }

    public static Vec3 vector(Location location) {
        return new Vec3(location.getX(), location.getY(), location.getZ());
    }

    public static Vector bukkit(Vec3 vector) {
        return new Vector(vector.x(), vector.y(), vector.z());
    }

    public static Vector bukkit(Face direction) {
        return new Vector(direction.x(), direction.y(), direction.z());
    }

    public static Location location(Vec3 vector, World world) {
        return new Location(world, vector.x(), vector.y(), vector.z());
    }

    public static Box bounds(Cuboid cuboid) {
        Vector minimum = cuboid.getCornerVector(Face.W, Face.D, Face.N);
        Vector maximum = cuboid.getCornerVector(Face.E, Face.U, Face.S);
        return new Box(minimum.getX(), maximum.getX(), minimum.getY(), maximum.getY(), minimum.getZ(), maximum.getZ());
    }

    public static Collection<Entity> entities(Box bounds, World world) {
        return world.getNearbyEntities(location(bounds.center(), world), bounds.sizeX() / 2.0D, bounds.sizeY() / 2.0D, bounds.sizeZ() / 2.0D);
    }
}
