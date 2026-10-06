package art.arcane.wormholes.render;

import art.arcane.wormholes.portal.ILocalPortal;
import org.bukkit.World;
import art.arcane.optics.entity.EntitySnapshot;
import art.arcane.wormholes.render.view.OccludedMarker;
import art.arcane.wormholes.render.view.ProjectionWorldView;
import org.bukkit.block.data.BlockData;
import org.bukkit.util.BoundingBox;

import static art.arcane.optics.occlusion.ProjectedEntityOcclusion.LABEL_HORIZONTAL_MARGIN;
import static art.arcane.optics.occlusion.ProjectedEntityOcclusion.LABEL_VERTICAL_MARGIN;
import static art.arcane.optics.occlusion.ProjectedEntityOcclusion.MIN_VISUAL_HEIGHT;
import static art.arcane.optics.occlusion.ProjectedEntityOcclusion.VISUAL_HALF_WIDTH;
import art.arcane.optics.entity.SnapshotProjector;
import art.arcane.optics.occlusion.ProjectedEntityOcclusion;
import art.arcane.optics.occlusion.ProjectorViewOcclusion;
import art.arcane.optics.recursion.EntityPath;

final class BukkitEntityOcclusion {
    private BukkitEntityOcclusion() {
    }

    static ProjectedEntityOcclusion<BlockData, ProjectionWorldView> create() {
        return new ProjectedEntityOcclusion<>(new ProjectorViewOcclusion<>(OccludedMarker::isOccluding,
            ProjectedEntityOcclusion.MAX_VOXEL_STEPS_PER_BATCH));
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
                               BoundingBox box, EntityPath<World, ILocalPortal> path) {
        if (path == null || !path.nested() || box == null) {
            return fullyHidden(occlusion, box);
        }
        return path.fullyHidden(occlusion, box.getMinX() - LABEL_HORIZONTAL_MARGIN, box.getMinY(),
            box.getMinZ() - LABEL_HORIZONTAL_MARGIN, box.getMaxX() + LABEL_HORIZONTAL_MARGIN,
            box.getMaxY() + LABEL_VERTICAL_MARGIN, box.getMaxZ() + LABEL_HORIZONTAL_MARGIN);
    }

    static boolean fullyHidden(ProjectedEntityOcclusion<BlockData, ProjectionWorldView> occlusion,
                               EntitySnapshot visual, EntityPath<World, ILocalPortal> path) {
        return SnapshotProjector.fullyHidden(occlusion, visual, path);
    }
}
