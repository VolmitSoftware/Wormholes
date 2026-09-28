package art.arcane.wormholes.render;

import art.arcane.wormholes.portal.ILocalPortal;
import org.bukkit.World;
import art.arcane.wormholes.network.view.EntityVisual;
import art.arcane.wormholes.render.view.OccludedMarker;
import art.arcane.wormholes.render.view.ProjectionWorldView;
import org.bukkit.block.data.BlockData;
import org.bukkit.util.BoundingBox;

import static art.arcane.wormholes.render.ProjectedEntityOcclusion.LABEL_HORIZONTAL_MARGIN;
import static art.arcane.wormholes.render.ProjectedEntityOcclusion.LABEL_VERTICAL_MARGIN;
import static art.arcane.wormholes.render.ProjectedEntityOcclusion.MIN_VISUAL_HEIGHT;
import static art.arcane.wormholes.render.ProjectedEntityOcclusion.VISUAL_HALF_WIDTH;

final class BukkitEntityOcclusion {
    private BukkitEntityOcclusion() {
    }

    static ProjectedEntityOcclusion<BlockData, ProjectionWorldView> create() {
        return new ProjectedEntityOcclusion<>(new ProjectorViewOcclusion<>(OccludedMarker::isOccluding,
            ProjectedEntityOcclusion.MAX_VOXEL_STEPS_PER_BATCH));
    }

    static boolean visible(EntityProjectionPath<World, ILocalPortal> path,
                           double x, double y, double z, BoundingBox bounds, double[] out) {
        return bounds == null ? path.visible(x, y, z, out)
            : path.visibleBounds(x, y, z, bounds.getMinX(), bounds.getMinY(), bounds.getMinZ(),
                bounds.getMaxX(), bounds.getMaxY(), bounds.getMaxZ(), out);
    }

    static boolean fullyHidden(ProjectedEntityOcclusion<BlockData, ProjectionWorldView> occlusion, BoundingBox box) {
        if (box == null) {
            return false;
        }
        return occlusion.fullyHidden(box.getMinX() - LABEL_HORIZONTAL_MARGIN, box.getMinY(),
            box.getMinZ() - LABEL_HORIZONTAL_MARGIN, box.getMaxX() + LABEL_HORIZONTAL_MARGIN,
            box.getMaxY() + LABEL_VERTICAL_MARGIN, box.getMaxZ() + LABEL_HORIZONTAL_MARGIN);
    }

    static boolean fullyHidden(ProjectedEntityOcclusion<BlockData, ProjectionWorldView> occlusion,
                               BoundingBox box, EntityProjectionPath<World, ILocalPortal> path) {
        if (path == null || !path.nested() || box == null) {
            return fullyHidden(occlusion, box);
        }
        return path.fullyHidden(occlusion, box.getMinX() - LABEL_HORIZONTAL_MARGIN, box.getMinY(),
            box.getMinZ() - LABEL_HORIZONTAL_MARGIN, box.getMaxX() + LABEL_HORIZONTAL_MARGIN,
            box.getMaxY() + LABEL_VERTICAL_MARGIN, box.getMaxZ() + LABEL_HORIZONTAL_MARGIN);
    }

    static boolean fullyHidden(ProjectedEntityOcclusion<BlockData, ProjectionWorldView> occlusion,
                               EntityVisual visual, EntityProjectionPath<World, ILocalPortal> path) {
        return EntityRenderVisualProjector.fullyHidden(occlusion, visual, path);
    }
}
