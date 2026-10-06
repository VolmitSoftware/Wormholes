package art.arcane.optics.occlusion;



import art.arcane.optics.view.BlockView;

import it.unimi.dsi.fastutil.longs.LongSet;


import art.arcane.optics.entity.EntitySnapshot;
import art.arcane.optics.math.Face;

public final class ProjectedEntityOcclusion<B, V extends BlockView<B>> {
    private static final int MAX_ENTITY_CELLS = 64;
    public static final int MAX_VOXEL_STEPS_PER_BATCH = 16_384;
    public static final double VISUAL_HALF_WIDTH = 1.0D;
    public static final double LABEL_HORIZONTAL_MARGIN = 0.5D;
    public static final double LABEL_VERTICAL_MARGIN = 0.75D;
    public static final double MIN_VISUAL_HEIGHT = 0.25D;

    private final ProjectorViewOcclusion<B> occlusion;
    private V view;
    private long revision;
    private double eyeX;
    private double eyeY;
    private double eyeZ;
    private boolean enabled;
    private boolean batchReady;


    public ProjectedEntityOcclusion(ProjectorViewOcclusion<B> occlusion) {
        this.occlusion = occlusion;
    }

    public void beginPass(V view,
                   double portalOriginX,
                   double portalOriginY,
                   double portalOriginZ,
                   Face portalNormal,
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

    public void retainRevision(long revision) {
        this.revision = revision;
    }

    public void updateEye(double eyeX, double eyeY, double eyeZ) {
        this.eyeX = eyeX;
        this.eyeY = eyeY;
        this.eyeZ = eyeZ;
    }

    public void startBatch() {
        batchReady = enabled && view.getRevision() == revision;
        if (batchReady) {
            occlusion.restartTraceBudget();
        }
    }



    public boolean fullyHidden(EntitySnapshot visual) {
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





    public void disable() {
        view = null;
        revision = 0L;
        enabled = false;
        batchReady = false;
    }

    public boolean fullyHidden(double minX,
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
