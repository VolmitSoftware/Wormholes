package art.arcane.optics.entity;

import java.util.Objects;
import java.util.UUID;

import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.frame.ViewWindow;
import art.arcane.optics.math.Angles;
import art.arcane.optics.math.Face;
import art.arcane.optics.recursion.EntityPath;
import art.arcane.optics.scan.ProjectorPassRevision;
import art.arcane.optics.volume.ViewVolume;

public final class EntityProjection {
    private static final double PLANE_TOLERANCE = 0.25D;

    private final double[] point = new double[3];
    private final double[] vector = new double[3];
    private double x;
    private double y;
    private double z;
    private double lookX;
    private double lookY;
    private double lookZ;
    private double velocityX;
    private double velocityY;
    private double velocityZ;
    private float yaw;
    private float pitch;
    private int metadataTransform;

    public static UUID opaque(long secret, UUID id) {
        if (id == null) {
            return null;
        }
        long most = ProjectorPassRevision.mix(ProjectorPassRevision.mix(secret, id.getMostSignificantBits()), id.getLeastSignificantBits());
        long least = ProjectorPassRevision.mix(ProjectorPassRevision.mix(most, secret), id.getMostSignificantBits() ^ 0x5DEECE66DL);
        return new UUID((most & ~0xF000L) | 0x4000L, (least & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L);
    }

    public static void feetInto(OpticTransform transform, double x, double y, double z, double height, double[] out3) {
        double halfHeight = height * 0.5D;
        transform.pointInto(x, y + halfHeight, z, out3);
        out3[1] -= halfHeight;
    }

    public boolean project(EntitySnapshot visual, OpticTransform transform, ViewVolume frustum, boolean itemFrame, boolean hanging) {
        transform.pointInto(visual.x(), visibleY(visual, hanging), visual.z(), point);
        if (!frustum.containsPrimitive(point[0], point[1], point[2])) {
            return false;
        }
        finish(visual, transform, itemFrame, hanging);
        return true;
    }

    public boolean project(EntitySnapshot visual, EntityPath<?, ?> path, boolean itemFrame, boolean hanging) {
        if (!path.visible(visual, visibleY(visual, hanging), point)) {
            return false;
        }
        finish(visual, path.transform(), itemFrame, hanging);
        return true;
    }

    public Projected project(EntitySnapshot visual, ViewWindow window, boolean hanging, boolean itemFrame, long secret) {
        Objects.requireNonNull(visual, "visual");
        OpticTransform transform = window.transform();
        transform.pointInto(visual.x(), visibleY(visual, hanging), visual.z(), point);
        if (!inside(window, point)) {
            return null;
        }
        finish(visual, transform, itemFrame, hanging);
        UUID id = opaque(secret, visual.id());
        String playerName = visual.isPlayer() && transform.flipsWorldUp()
            ? PlayerNames.projectedProfileName(visual.playerName(), id, true)
            : visual.playerName();
        EntitySnapshot local = new EntitySnapshot(EntitySnapshot.MODE_FULL, 0, EntitySnapshot.FIELD_ALL_FULL, id, visual.typeKey(), x, y, z,
            visual.height(), lookX, lookY, lookZ, yaw, pitch, velocityX, velocityY, velocityZ, visual.onGround(), playerName,
            visual.textureValue(), visual.textureSignature(), opaque(secret, visual.passengerOf()), opaque(secret, visual.leashHolder()),
            visual.metadata(), visual.equipment(), EntitySnapshot.EMPTY);
        return new Projected(local, metadataTransform);
    }

    public Projected nativeModel(EntitySnapshot visual, ViewWindow window, boolean hanging, long secret) {
        Objects.requireNonNull(visual, "visual");
        window.transform().pointInto(visual.x(), visibleY(visual, hanging), visual.z(), point);
        if (!inside(window, point)) {
            return null;
        }
        EntitySnapshot source = new EntitySnapshot(EntitySnapshot.MODE_FULL, 0, EntitySnapshot.FIELD_ALL_FULL, opaque(secret, visual.id()),
            visual.typeKey(), visual.x(), visual.y(), visual.z(), visual.height(), visual.lookX(), visual.lookY(), visual.lookZ(),
            visual.yaw(), visual.pitch(), visual.velocityX(), visual.velocityY(), visual.velocityZ(), visual.onGround(), visual.playerName(),
            visual.textureValue(), visual.textureSignature(), opaque(secret, visual.passengerOf()), opaque(secret, visual.leashHolder()),
            visual.metadata(), visual.equipment(), EntitySnapshot.EMPTY);
        return new Projected(source, ItemFrameTransform.NONE);
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double z() {
        return z;
    }

    public double velocityX() {
        return velocityX;
    }

    public double velocityY() {
        return velocityY;
    }

    public double velocityZ() {
        return velocityZ;
    }

    public float yaw() {
        return yaw;
    }

    public float pitch() {
        return pitch;
    }

    public int metadataTransform() {
        return metadataTransform;
    }

    private void finish(EntitySnapshot visual, OpticTransform transform, boolean itemFrame, boolean hanging) {
        transform.vectorInto(visual.lookX(), visual.lookY(), visual.lookZ(), vector);
        lookX = vector[0];
        lookY = vector[1];
        lookZ = vector[2];
        yaw = Angles.yaw(lookX, lookZ);
        pitch = Angles.pitch(lookX, lookY, lookZ);
        metadataTransform = itemFrame ? ItemFrameTransform.of(Face.closest(visual.lookX(), visual.lookY(), visual.lookZ()), transform)
            : ItemFrameTransform.NONE;
        if (hanging) {
            ItemFrameTransform.anchorInto(visual.x(), visual.y(), visual.z(), transform, vector);
            x = vector[0];
            y = vector[1];
            z = vector[2];
        } else {
            x = point[0];
            y = point[1] - (visual.height() * 0.5D);
            z = point[2];
        }
        transform.vectorInto(visual.velocityX(), visual.velocityY(), visual.velocityZ(), vector);
        velocityX = vector[0];
        velocityY = vector[1];
        velocityZ = vector[2];
    }

    private static double visibleY(EntitySnapshot visual, boolean hanging) {
        return hanging ? visual.y() : visual.y() + (visual.height() * 0.5D);
    }

    private static boolean inside(ViewWindow window, double[] point) {
        Face normal = window.localFrame().getNormal();
        double signed = (point[0] - window.localOrigin().x()) * normal.x() + (point[1] - window.localOrigin().y()) * normal.y()
            + (point[2] - window.localOrigin().z()) * normal.z();
        double depth = window.frontSide() ? -signed : signed;
        return depth >= -PLANE_TOLERANCE && depth <= window.depth() + PLANE_TOLERANCE;
    }

    public record Projected(EntitySnapshot visual, int metadataTransform) {
    }
}
