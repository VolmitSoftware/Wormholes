package art.arcane.optics.entity;

import art.arcane.optics.math.Vec3;
import art.arcane.optics.aperture.Endpoint;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.math.Face;
import art.arcane.optics.frame.PortalCoordMap;
import art.arcane.optics.recursion.EntityPath;
import art.arcane.optics.volume.ViewVolume;

public final class EntityVisualProjection<W, P extends Endpoint, R> {
    private final ItemFrameTransform.PositionFactory<R> positions;
    private final double[] scratchVisiblePoint = new double[3];
    private final double[] scratchDirection = new double[3];
    private R position;
    private R velocity;
    private float yaw;
    private float pitch;
    private int metadataTransform;

    public EntityVisualProjection(ItemFrameTransform.PositionFactory<R> positions) {
        this.positions = positions;
    }

    public boolean project(Endpoint localPortal,
                           double remoteOriginX, double remoteOriginY, double remoteOriginZ,
                           Frame localViewFrame, Frame remoteViewFrame, ViewVolume frustum,
                           EntitySnapshot visual, boolean mirror, int mirrorRotationQuarterTurns,
                           EntityPath<W, P> projectionPath, boolean itemFrame, boolean hanging) {
        Vec3 localOrigin = localPortal.origin();
        double visibleY = hanging ? visual.y() : visual.y() + (visual.height() * 0.5D);
        if (projectionPath != null) {
            if (!projectionPath.visible(visual, visibleY, scratchVisiblePoint)) {
                return false;
            }
        } else if (mirror) {
            PortalCoordMap.mirrorSourceToDisplayPointInto(visual.x(), visibleY, visual.z(),
                remoteOriginX, remoteOriginY, remoteOriginZ, localPortal.frame(), mirrorRotationQuarterTurns,
                scratchVisiblePoint);
        } else {
            PortalCoordMap.transformPointInto(visual.x(), visibleY, visual.z(),
                remoteOriginX, remoteOriginY, remoteOriginZ,
                localOrigin.getX(), localOrigin.getY(), localOrigin.getZ(),
                remoteViewFrame, localViewFrame, scratchVisiblePoint);
        }
        if (projectionPath == null && !frustum.containsPrimitive(scratchVisiblePoint[0], scratchVisiblePoint[1], scratchVisiblePoint[2])) {
            return false;
        }

        if (projectionPath != null) {
            projectionPath.vector(visual.lookX(), visual.lookY(), visual.lookZ(), scratchDirection);
        } else if (mirror) {
            PortalCoordMap.mirrorSourceToDisplayVectorInto(visual.lookX(), visual.lookY(), visual.lookZ(),
                localPortal.frame(), mirrorRotationQuarterTurns, scratchDirection);
        } else {
            remoteViewFrame.transformVectorInto(visual.lookX(), visual.lookY(), visual.lookZ(), localViewFrame, scratchDirection);
        }
        yaw = yaw(scratchDirection[0], scratchDirection[2]);
        pitch = pitch(scratchDirection[0], scratchDirection[1], scratchDirection[2]);
        Face sourceFacing = Face.closest(visual.lookX(), visual.lookY(), visual.lookZ());
        metadataTransform = ItemFrameTransform.NONE;
        if (itemFrame) {
            metadataTransform = projectionPath != null ? projectionPath.itemFrameTransform(sourceFacing) : mirror
                ? ItemFrameTransform.mirror(sourceFacing, localPortal.frame(),
                    mirrorRotationQuarterTurns, scratchDirection)
                : ItemFrameTransform.between(sourceFacing, remoteViewFrame, localViewFrame,
                    scratchDirection);
        }
        if (hanging && projectionPath != null) {
            position = projectionPath.anchor(visual.x(), visual.y(), visual.z(), positions);
        } else if (hanging && mirror) {
            position = ItemFrameTransform.mirrorAnchor(
                visual.x(), visual.y(), visual.z(),
                remoteOriginX, remoteOriginY, remoteOriginZ,
                localPortal.frame(), mirrorRotationQuarterTurns, scratchVisiblePoint, positions);
        } else if (hanging) {
            position = ItemFrameTransform.betweenAnchor(
                visual.x(), visual.y(), visual.z(),
                remoteOriginX, remoteOriginY, remoteOriginZ,
                localOrigin.getX(), localOrigin.getY(), localOrigin.getZ(),
                remoteViewFrame, localViewFrame, scratchVisiblePoint, positions);
        } else {
            double visualBaseY = scratchVisiblePoint[1] - (visual.height() * 0.5D);
            position = positions.create(scratchVisiblePoint[0], visualBaseY, scratchVisiblePoint[2]);
        }
        if (projectionPath != null) {
            projectionPath.vector(visual.velocityX(), visual.velocityY(), visual.velocityZ(), scratchDirection);
        } else if (mirror) {
            PortalCoordMap.mirrorSourceToDisplayVectorInto(visual.velocityX(), visual.velocityY(), visual.velocityZ(),
                localPortal.frame(), mirrorRotationQuarterTurns, scratchDirection);
        } else {
            remoteViewFrame.transformVectorInto(visual.velocityX(), visual.velocityY(), visual.velocityZ(), localViewFrame, scratchDirection);
        }
        velocity = positions.create(scratchDirection[0], scratchDirection[1], scratchDirection[2]);

        return true;
    }

    public R position() {
        return position;
    }

    public R velocity() {
        return velocity;
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

    public static void lookDirectionInto(float yaw, float pitch, double[] out) {
        double pitchRadians = Math.toRadians(pitch);
        double yawRadians = Math.toRadians(yaw);
        double horizontal = Math.cos(pitchRadians);
        out[0] = -horizontal * Math.sin(yawRadians);
        out[1] = -Math.sin(pitchRadians);
        out[2] = horizontal * Math.cos(yawRadians);
    }

    public static float yaw(double x, double z) {
        return (float) Math.toDegrees(Math.atan2(-x, z));
    }

    public static float pitch(double x, double y, double z) {
        double horizontal = Math.sqrt(x * x + z * z);
        return (float) Math.toDegrees(-Math.atan2(y, horizontal));
    }

}
