package art.arcane.wormholes.render;

import it.unimi.dsi.fastutil.longs.LongSet;

import org.bukkit.util.BoundingBox;

import art.arcane.wormholes.network.view.EntityVisual;
import art.arcane.wormholes.render.view.ProjectionWorldView;
import art.arcane.wormholes.util.Direction;

final class ProjectedEntityOcclusion {
    private static final int MAX_ENTITY_CELLS = 64;
    private static final int MAX_VOXEL_STEPS_PER_BATCH = 16_384;
    private static final double VISUAL_HALF_WIDTH = 1.0D;
    private static final double LABEL_HORIZONTAL_MARGIN = 0.5D;
    private static final double LABEL_VERTICAL_MARGIN = 0.75D;
    private static final double MIN_VISUAL_HEIGHT = 0.25D;

    private final ProjectorViewOcclusion occlusion;
    private ProjectionWorldView view;
    private long revision;
    private double eyeX;
    private double eyeY;
    private double eyeZ;
    private boolean enabled;
    private boolean batchReady;

    ProjectedEntityOcclusion() {
        this(new ProjectorViewOcclusion(MAX_VOXEL_STEPS_PER_BATCH));
    }

    ProjectedEntityOcclusion(ProjectorViewOcclusion occlusion) {
        this.occlusion = occlusion;
    }

    void beginPass(ProjectionWorldView view,
                   double portalOriginX,
                   double portalOriginY,
                   double portalOriginZ,
                   Direction portalNormal,
                   LongSet eligibleBlockers,
                   double eyeX,
                   double eyeY,
                   double eyeZ,
                   double revealMarginDegrees) {
        this.view = view;
        this.revision = view == null ? 0L : view.getRevision();
        this.eyeX = eyeX;
        this.eyeY = eyeY;
        this.eyeZ = eyeZ;
        this.enabled = view != null && eligibleBlockers != null && !eligibleBlockers.isEmpty();
        this.batchReady = false;
        if (enabled) {
            occlusion.setRevealMarginDegrees(revealMarginDegrees);
            occlusion.beginPass(portalOriginX, portalOriginY, portalOriginZ, portalNormal, eligibleBlockers);
        }
    }

    void updateEye(double eyeX, double eyeY, double eyeZ) {
        this.eyeX = eyeX;
        this.eyeY = eyeY;
        this.eyeZ = eyeZ;
    }

    void startBatch() {
        batchReady = enabled && view.getRevision() == revision;
        if (batchReady) {
            occlusion.restartTraceBudget();
        }
    }

    boolean fullyHidden(BoundingBox box) {
        if (box == null) {
            return false;
        }
        return fullyHidden(
            box.getMinX() - LABEL_HORIZONTAL_MARGIN,
            box.getMinY(),
            box.getMinZ() - LABEL_HORIZONTAL_MARGIN,
            box.getMaxX() + LABEL_HORIZONTAL_MARGIN,
            box.getMaxY() + LABEL_VERTICAL_MARGIN,
            box.getMaxZ() + LABEL_HORIZONTAL_MARGIN);
    }

    boolean fullyHidden(EntityVisual visual) {
        if (visual == null) {
            return false;
        }
        double height = Math.max(MIN_VISUAL_HEIGHT, visual.height());
        return fullyHidden(
            visual.x() - VISUAL_HALF_WIDTH,
            visual.y(),
            visual.z() - VISUAL_HALF_WIDTH,
            visual.x() + VISUAL_HALF_WIDTH,
            visual.y() + height + LABEL_VERTICAL_MARGIN,
            visual.z() + VISUAL_HALF_WIDTH);
    }

    void disable() {
        view = null;
        revision = 0L;
        enabled = false;
        batchReady = false;
    }

    private boolean fullyHidden(double minX,
                                double minY,
                                double minZ,
                                double maxX,
                                double maxY,
                                double maxZ) {
        if (!batchReady
            || !finiteBounds(minX, minY, minZ, maxX, maxY, maxZ)
            || maxX <= minX
            || maxY <= minY
            || maxZ <= minZ) {
            return false;
        }
        int firstX = floor(minX);
        int firstY = floor(minY);
        int firstZ = floor(minZ);
        int lastX = floor(Math.nextDown(maxX));
        int lastY = floor(Math.nextDown(maxY));
        int lastZ = floor(Math.nextDown(maxZ));
        long cells = ((long) lastX - firstX + 1L)
            * ((long) lastY - firstY + 1L)
            * ((long) lastZ - firstZ + 1L);
        if (cells <= 0L || cells > MAX_ENTITY_CELLS) {
            return false;
        }
        for (int x = firstX; x <= lastX; x++) {
            for (int y = firstY; y <= lastY; y++) {
                for (int z = firstZ; z <= lastZ; z++) {
                    ProjectorViewOcclusion.Visibility visibility = occlusion.visibility(
                        view, x, y, z, eyeX, eyeY, eyeZ);
                    if (visibility != ProjectorViewOcclusion.Visibility.HIDDEN) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static boolean finiteBounds(double minX,
                                        double minY,
                                        double minZ,
                                        double maxX,
                                        double maxY,
                                        double maxZ) {
        return Double.isFinite(minX)
            && Double.isFinite(minY)
            && Double.isFinite(minZ)
            && Double.isFinite(maxX)
            && Double.isFinite(maxY)
            && Double.isFinite(maxZ);
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }
}
