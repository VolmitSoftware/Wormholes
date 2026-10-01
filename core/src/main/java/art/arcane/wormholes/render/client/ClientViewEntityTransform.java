package art.arcane.wormholes.render.client;

import java.util.Objects;
import java.util.UUID;

import art.arcane.wormholes.network.view.EntityVisual;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.render.EntityVisualProjection;
import art.arcane.wormholes.render.PortalCoordMap;
import art.arcane.wormholes.render.ProjectedItemFrameTransform;
import art.arcane.wormholes.render.ProjectedPlayerNames;
import art.arcane.wormholes.render.ProjectorPassRevision;
import art.arcane.wormholes.util.Direction;

public final class ClientViewEntityTransform {
    private static final double PLANE_TOLERANCE = 0.25D;

    private final double[] point;
    private final double[] vector;
    private final double[] anchor;

    public ClientViewEntityTransform() {
        this.point = new double[3];
        this.vector = new double[3];
        this.anchor = new double[3];
    }

    public static UUID opaque(long secret, UUID id) {
        if (id == null) {
            return null;
        }
        long most = ProjectorPassRevision.mix(ProjectorPassRevision.mix(secret, id.getMostSignificantBits()), id.getLeastSignificantBits());
        long least = ProjectorPassRevision.mix(ProjectorPassRevision.mix(most, secret), id.getMostSignificantBits() ^ 0x5DEECE66DL);
        return new UUID((most & ~0xF000L) | 0x4000L, (least & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L);
    }

    public boolean upsideDown(Frame frame) {
        return frame.mirror()
            ? PortalCoordMap.mirrorTransformFlipsWorldUp(frame.localFrame(), frame.quarterTurns())
            : PortalCoordMap.transformFlipsWorldUp(frame.remoteViewFrame(), frame.localViewFrame());
    }

    public Projected project(EntityVisual visual, Frame frame, boolean hanging, boolean itemFrame, long secret) {
        Objects.requireNonNull(visual, "visual");
        Objects.requireNonNull(frame, "frame");
        double visibleY = hanging ? visual.y() : visual.y() + (visual.height() * 0.5D);
        mapPoint(visual.x(), visibleY, visual.z(), frame, point);
        if (!inVolume(point[0], point[1], point[2], frame)) {
            return null;
        }
        double centerX = point[0];
        double centerY = point[1];
        double centerZ = point[2];
        mapVector(visual.lookX(), visual.lookY(), visual.lookZ(), frame, vector);
        double lookX = vector[0];
        double lookY = vector[1];
        double lookZ = vector[2];
        float yaw = EntityVisualProjection.yaw(lookX, lookZ);
        float pitch = EntityVisualProjection.pitch(lookX, lookY, lookZ);
        int metadataTransform = ProjectedItemFrameTransform.NONE;
        if (itemFrame) {
            Direction sourceFacing = Direction.closest(visual.lookX(), visual.lookY(), visual.lookZ());
            metadataTransform = frame.mirror()
                ? ProjectedItemFrameTransform.mirror(sourceFacing, frame.localFrame(), frame.quarterTurns(), anchor)
                : ProjectedItemFrameTransform.between(sourceFacing, frame.remoteViewFrame(), frame.localViewFrame(), anchor);
        }
        double x;
        double y;
        double z;
        if (hanging) {
            double[] position = frame.mirror()
                ? ProjectedItemFrameTransform.mirrorAnchor(visual.x(), visual.y(), visual.z(), frame.remoteOriginX(), frame.remoteOriginY(),
                    frame.remoteOriginZ(), frame.localFrame(), frame.quarterTurns(), anchor, ClientViewEntityTransform::triple)
                : ProjectedItemFrameTransform.betweenAnchor(visual.x(), visual.y(), visual.z(), frame.remoteOriginX(), frame.remoteOriginY(),
                    frame.remoteOriginZ(), frame.localOriginX(), frame.localOriginY(), frame.localOriginZ(), frame.remoteViewFrame(),
                    frame.localViewFrame(), anchor, ClientViewEntityTransform::triple);
            x = position[0];
            y = position[1];
            z = position[2];
        } else {
            x = centerX;
            y = centerY - (visual.height() * 0.5D);
            z = centerZ;
        }
        mapVector(visual.velocityX(), visual.velocityY(), visual.velocityZ(), frame, vector);
        UUID id = opaque(secret, visual.id());
        String playerName = visual.isPlayer() && upsideDown(frame)
            ? ProjectedPlayerNames.projectedProfileName(visual.playerName(), id, true)
            : visual.playerName();
        EntityVisual local = new EntityVisual(EntityVisual.MODE_FULL, 0, EntityVisual.FIELD_ALL_FULL, id, visual.typeKey(), x, y, z,
            visual.height(), lookX, lookY, lookZ, yaw, pitch, vector[0], vector[1], vector[2], visual.onGround(), playerName,
            visual.textureValue(), visual.textureSignature(), opaque(secret, visual.passengerOf()), opaque(secret, visual.leashHolder()),
            visual.metadata(), visual.equipment(), EntityVisual.EMPTY);
        return new Projected(local, metadataTransform);
    }

    private boolean inVolume(double x, double y, double z, Frame frame) {
        PortalFrame local = frame.localFrame();
        Direction normal = local.getNormal();
        double signed = (x - frame.localOriginX()) * normal.x() + (y - frame.localOriginY()) * normal.y() + (z - frame.localOriginZ()) * normal.z();
        double depth = frame.frontSide() ? -signed : signed;
        return depth >= -PLANE_TOLERANCE && depth <= frame.depth() + PLANE_TOLERANCE;
    }

    private void mapPoint(double x, double y, double z, Frame frame, double[] out) {
        if (frame.mirror()) {
            PortalCoordMap.mirrorSourceToDisplayPointInto(x, y, z, frame.remoteOriginX(), frame.remoteOriginY(), frame.remoteOriginZ(),
                frame.localFrame(), frame.quarterTurns(), out);
            return;
        }
        PortalCoordMap.transformPointInto(x, y, z, frame.remoteOriginX(), frame.remoteOriginY(), frame.remoteOriginZ(),
            frame.localOriginX(), frame.localOriginY(), frame.localOriginZ(), frame.remoteViewFrame(), frame.localViewFrame(), out);
    }

    private void mapVector(double x, double y, double z, Frame frame, double[] out) {
        if (frame.mirror()) {
            PortalCoordMap.mirrorSourceToDisplayVectorInto(x, y, z, frame.localFrame(), frame.quarterTurns(), out);
            return;
        }
        frame.remoteViewFrame().transformVectorInto(x, y, z, frame.localViewFrame(), out);
    }

    private static double[] triple(double x, double y, double z) {
        return new double[] {x, y, z};
    }

    public record Frame(double localOriginX,
                        double localOriginY,
                        double localOriginZ,
                        PortalFrame localFrame,
                        double remoteOriginX,
                        double remoteOriginY,
                        double remoteOriginZ,
                        PortalFrame remoteFrame,
                        boolean mirror,
                        int quarterTurns,
                        boolean frontSide,
                        double depth) {
        public Frame {
            Objects.requireNonNull(localFrame, "localFrame");
            Objects.requireNonNull(remoteFrame, "remoteFrame");
        }

        public PortalFrame localViewFrame() {
            return localFrame.view(frontSide);
        }

        public PortalFrame remoteViewFrame() {
            return remoteFrame.view(frontSide);
        }
    }

    public record Projected(EntityVisual visual, int metadataTransform) {
    }
}
