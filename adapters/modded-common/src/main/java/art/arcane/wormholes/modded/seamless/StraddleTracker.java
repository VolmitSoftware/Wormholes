package art.arcane.wormholes.modded.seamless;

import art.arcane.optics.aperture.Aperture;
import art.arcane.optics.crossing.StraddleGeometry;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.Similarity;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.network.client.TravelMessage;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

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
        return area != null && stretched.intersects(area);
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
        return new Straddle(source.aperture(), source.frame(), source.origin(), front,
            StraddleGeometry.exclusionSlab(source.aperture(), source.frame(), source.origin(), front, EXCLUSION_DEPTH), toward,
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

    public record Endpoint(Aperture aperture, Frame frame, Vec3d origin, TravelMessage.DoorCollisionTarget doorCollision) {
        public Endpoint {
            Objects.requireNonNull(frame, "frame");
            Objects.requireNonNull(origin, "origin");
        }
    }

    public record Straddle(Aperture aperture, Frame frame, Vec3d origin, boolean frontSide, Box slab, Similarity toward, Level destination,
                           Frame destinationFrame, Vec3d destinationOrigin, boolean exitFront, TravelMessage.DoorCollisionTarget doorCollision) {
        public Straddle {
            Objects.requireNonNull(frame, "frame");
            Objects.requireNonNull(origin, "origin");
            Objects.requireNonNull(slab, "slab");
            Objects.requireNonNull(toward, "toward");
            Objects.requireNonNull(destinationFrame, "destinationFrame");
            Objects.requireNonNull(destinationOrigin, "destinationOrigin");
        }

        public boolean matches(Endpoint source, Endpoint target, Level level, Vec3d eye) {
            return aperture == source.aperture() && destination == level && frame.equals(source.frame()) && origin.equals(source.origin())
                && destinationFrame.equals(target.frame()) && destinationOrigin.equals(target.origin())
                && Objects.equals(doorCollision, target.doorCollision())
                && frontSide == StraddleTracker.frontSide(source.frame(), source.origin(), eye);
        }

        public boolean excludes(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
            return minX >= slab.getXa() && maxX <= slab.getXb() && minY >= slab.getYa() && maxY <= slab.getYb()
                && minZ >= slab.getZa() && maxZ <= slab.getZb();
        }
    }
}
