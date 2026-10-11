package art.arcane.wormholes.modded.seamless;

import art.arcane.optics.aperture.Aperture;
import art.arcane.optics.aperture.CellAperture;
import art.arcane.optics.crossing.StraddleGeometry;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.Similarity;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.network.client.TravelMessage;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Objects;

public final class StraddleTracker {
    public static final double EXCLUSION_DEPTH = 10.0D;
    private static final double VELOCITY_STRETCH = 1.2D;
    private static final double BEHIND_PROBE = 0.5D;

    private StraddleTracker() {
    }

    public static Box stretched(Box box, Vec3d velocity, Vec3d previousOffset) {
        Box stretched = new Box(box);
        stretched.encapsulate(moved(box, velocity.multiply(VELOCITY_STRETCH)));
        stretched.encapsulate(moved(box, previousOffset));
        return stretched;
    }

    public static boolean qualifies(Box stretched, Aperture aperture) {
        Box area = aperture.getArea();
        return area != null && stretched.getXb() >= Math.floor(area.getXa()) && stretched.getXa() <= Math.floor(area.getXb()) + 1.0D
            && stretched.getYb() >= Math.floor(area.getYa()) && stretched.getYa() <= Math.floor(area.getYb()) + 1.0D
            && stretched.getZb() >= Math.floor(area.getZa()) && stretched.getZa() <= Math.floor(area.getZb()) + 1.0D;
    }

    public static boolean frontSide(Frame frame, Vec3d origin, Vec3d eye) {
        return signed(frame, origin, eye) > 0.0D;
    }

    public static Straddle create(Endpoint source, Endpoint destination, Level destinationLevel, Vec3d eye, double scale) {
        boolean front = frontSide(source.frame(), source.origin(), eye);
        Frame viewed = source.frame().view(front);
        Similarity toward = Similarity.between(viewed, source.origin(), destination.frame().view(front), destination.origin(), scale);
        Face normal = viewed.getNormal();
        Vec3d behind = source.origin().subtract(new Vec3d(normal.x(), normal.y(), normal.z()).multiply(BEHIND_PROBE));
        boolean exitFront = signed(destination.frame(), destination.origin(), toward.point(behind)) > 0.0D;
        Box slab = sourceSlab(source, front);
        return new Straddle(source.aperture(), source.frame(), source.origin(), front,
            slab, sourceOpening(source.aperture(), normal, slab), revision(source.aperture()), toward,
            destinationLevel, destination.frame(), destination.origin(), exitFront, destination.doorCollision());
    }

    public static void track(Entity entity, Endpoint source, Endpoint destination, Level destinationLevel, Vec3d eye, double scale) {
        Straddle current = straddle(entity);
        if (current == null || !current.matches(source, destination, destinationLevel, eye) || current.toward().scale() != scale) {
            register(entity, create(source, destination, destinationLevel, eye, scale));
        }
    }

    public static Straddle straddle(Entity entity) {
        return entity instanceof StraddleHolder holder ? holder.wormholesStraddle() : null;
    }

    public static void register(Entity entity, Straddle straddle) {
        if (entity instanceof StraddleHolder holder) {
            holder.wormholesStraddle(straddle);
        }
    }

    public static void clear(Entity entity) {
        if (entity instanceof StraddleHolder holder && holder.wormholesStraddle() != null) {
            holder.wormholesStraddle(null);
        }
    }

    private static Box moved(Box box, Vec3d offset) {
        return new Box(box.getXa() + offset.x(), box.getXb() + offset.x(), box.getYa() + offset.y(), box.getYb() + offset.y(),
            box.getZa() + offset.z(), box.getZb() + offset.z());
    }

    private static double signed(Frame frame, Vec3d origin, Vec3d point) {
        Face normal = frame.getNormal();
        return (point.x() - origin.x()) * normal.x() + (point.y() - origin.y()) * normal.y() + (point.z() - origin.z()) * normal.z();
    }

    private static Box sourceSlab(Endpoint source, boolean front) {
        Box slab = StraddleGeometry.exclusionSlab(source.aperture(), source.frame(), source.origin(), front, EXCLUSION_DEPTH);
        Face normal = source.frame().view(front).getNormal();
        double plane = source.origin().y();
        if (!normal.isVertical() || Math.abs(plane - Math.floor(plane) - 0.5D) > 0.001D) {
            return slab;
        }
        return normal.sign() > 0
            ? new Box(slab.getXa(), slab.getXb(), slab.getYa(), Math.floor(plane) + 1.0D, slab.getZa(), slab.getZb())
            : new Box(slab.getXa(), slab.getXb(), Math.floor(plane), slab.getYb(), slab.getZa(), slab.getZb());
    }

    private static VoxelShape sourceOpening(Aperture aperture, Face normal, Box slab) {
        VoxelShape opening = Shapes.empty();
        int axis = normal.axisIndex();
        for (Box face : aperture.getCachedApertureFaces(normal)) {
            double minX = axis == 0 ? slab.getXa() : Math.floor(face.getXa());
            double maxX = axis == 0 ? slab.getXb() : Math.floor(face.getXb()) + 1.0D;
            double minY = axis == 1 ? slab.getYa() : Math.floor(face.getYa());
            double maxY = axis == 1 ? slab.getYb() : Math.floor(face.getYb()) + 1.0D;
            double minZ = axis == 2 ? slab.getZa() : Math.floor(face.getZa());
            double maxZ = axis == 2 ? slab.getZb() : Math.floor(face.getZb()) + 1.0D;
            opening = Shapes.joinUnoptimized(opening, Shapes.create(new AABB(minX, minY, minZ, maxX, maxY, maxZ)), BooleanOp.OR);
        }
        return opening.optimize();
    }

    private static long revision(Aperture aperture) {
        return aperture instanceof CellAperture cells ? cells.getRevision() : 0L;
    }

    public record Endpoint(Aperture aperture, Frame frame, Vec3d origin, TravelMessage.DoorCollisionTarget doorCollision) {
        public Endpoint {
            Objects.requireNonNull(frame, "frame");
            Objects.requireNonNull(origin, "origin");
        }
    }

    public record Straddle(Aperture aperture, Frame frame, Vec3d origin, boolean frontSide, Box slab, VoxelShape sourceOpening, long sourceRevision, Similarity toward, Level destination,
                           Frame destinationFrame, Vec3d destinationOrigin, boolean exitFront, TravelMessage.DoorCollisionTarget doorCollision) {
        public Straddle {
            Objects.requireNonNull(frame, "frame");
            Objects.requireNonNull(origin, "origin");
            Objects.requireNonNull(slab, "slab");
            Objects.requireNonNull(sourceOpening, "sourceOpening");
            Objects.requireNonNull(toward, "toward");
            Objects.requireNonNull(destinationFrame, "destinationFrame");
            Objects.requireNonNull(destinationOrigin, "destinationOrigin");
        }

        public boolean matches(Endpoint source, Endpoint target, Level level, Vec3d eye) {
            return aperture == source.aperture() && sourceRevision == revision(source.aperture()) && destination == level
                && frame.equals(source.frame()) && origin.equals(source.origin())
                && destinationFrame.equals(target.frame()) && destinationOrigin.equals(target.origin())
                && Objects.equals(doorCollision, target.doorCollision())
                && frontSide == StraddleTracker.frontSide(source.frame(), source.origin(), eye);
        }

    }
}
