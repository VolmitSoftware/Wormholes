package art.arcane.wormholes.modded.client.render.sodium;

import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkBuilder;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.CoordinateSectionVisitor;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.DeferredTaskList;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.TaskCollectingTree;
import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.CullType;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.minecraft.core.SectionPos;

import java.util.ArrayList;
import java.util.List;

final class SodiumTaskSweep implements CoordinateSectionVisitor {
    private final SodiumPortalSections sections;
    private final TaskCollectingTree tasks;
    private final boolean inFrustum;
    private final CameraTransform camera;
    private final float distance;
    private List<SodiumArrivalSection> unfinished;

    private SodiumTaskSweep(SodiumPortalSections sections, Viewport viewport, float distance, boolean inFrustum) {
        this.sections = sections;
        this.inFrustum = inFrustum;
        camera = viewport.getTransform();
        this.distance = distance;
        tasks = new TaskCollectingTree(viewport, distance, SodiumLayers.nextFrame(), CullType.WIDE, sections.wormholes$level());
    }

    static DeferredTaskList pending(SodiumPortalSections sections, Viewport viewport, float distance, boolean inFrustum) {
        SodiumTaskSweep sweep = new SodiumTaskSweep(sections, viewport, distance, inFrustum);
        sweep.traverse(viewport, distance);
        return sweep.tasks.getPendingTaskLists();
    }

    static Work arrival(SodiumPortalSections sections, Viewport viewport, float distance) {
        SodiumTaskSweep sweep = new SodiumTaskSweep(sections, viewport, distance, true);
        sweep.unfinished = new ArrayList<>();
        sweep.traverse(viewport, distance);
        return new Work(sweep.tasks.getPendingTaskLists(), sweep.unfinished);
    }

    @Override
    public void visit(int x, int y, int z) {
        RenderSection section = sections.wormholes$storage().getConsistent(SectionPos.asLong(x, y, z));
        if (section == null || section.isDisposed() || !withinDistance(section)) {
            return;
        }
        if (section.getPendingUpdate() != 0) {
            tasks.visit(section, inFrustum);
        }
        if (unfinished != null) {
            SodiumArrivalSection arrival = (SodiumArrivalSection) section;
            if (!arrival.wormholes$terrainComplete()) {
                unfinished.add(arrival);
            }
        }
    }

    private void traverse(Viewport viewport, float distance) {
        sections.wormholes$arrivalSections().prepareForTraversal();
        sections.wormholes$arrivalSections().traverse(this, viewport, distance);
    }

    private boolean withinDistance(RenderSection section) {
        int x = (section.getChunkX() << 4) - camera.intX;
        int y = (section.getChunkY() << 4) - camera.intY;
        int z = (section.getChunkZ() << 4) - camera.intZ;
        float nearestX = nearestToZero(x - 1, x + 17) - camera.fracX;
        float nearestY = nearestToZero(y - 1, y + 17) - camera.fracY;
        float nearestZ = nearestToZero(z - 1, z + 17) - camera.fracZ;
        return nearestX * nearestX + nearestZ * nearestZ < distance * distance && Math.abs(nearestY) < distance;
    }

    private static int nearestToZero(int min, int max) {
        return min > 0 ? min : Math.min(max, 0);
    }

    record Work(DeferredTaskList pending, List<SodiumArrivalSection> unfinished) {
        boolean complete() {
            return pending.isEmpty() && unfinished.isEmpty();
        }

        void finishRunning(ChunkBuilder builder) {
            for (SodiumArrivalSection section : unfinished) {
                section.wormholes$stealBuilds(builder);
            }
        }

        boolean hasRunningBuilds() {
            for (SodiumArrivalSection section : unfinished) {
                if (section.wormholes$buildsPending()) {
                    return true;
                }
            }
            return false;
        }
    }
}
