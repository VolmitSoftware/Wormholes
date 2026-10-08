package art.arcane.wormholes.modded.client.render.sodium;

import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.CoordinateSectionVisitor;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.DeferredTaskList;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.TaskCollectingTree;
import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.CullType;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;

final class SodiumTaskSweep implements CoordinateSectionVisitor {
    private final SodiumPortalSections sections;
    private final TaskCollectingTree tasks;
    private final boolean inFrustum;

    private SodiumTaskSweep(SodiumPortalSections sections, Viewport viewport, float distance, boolean inFrustum) {
        this.sections = sections;
        this.inFrustum = inFrustum;
        tasks = new TaskCollectingTree(viewport, distance, SodiumLayers.nextFrame(), CullType.WIDE, sections.wormholes$level());
    }

    static DeferredTaskList pending(SodiumPortalSections sections, Viewport viewport, float distance, boolean inFrustum) {
        SodiumTaskSweep sweep = new SodiumTaskSweep(sections, viewport, distance, inFrustum);
        sections.wormholes$renderable().prepareForTraversal();
        sections.wormholes$renderable().traverse(sweep, viewport, distance);
        return sweep.tasks.getPendingTaskLists();
    }

    @Override
    public void visit(int x, int y, int z) {
        RenderSection section = sections.wormholes$storage().getCurrent(x, y, z);
        if (section != null) {
            tasks.visit(section, inFrustum);
        }
    }
}
