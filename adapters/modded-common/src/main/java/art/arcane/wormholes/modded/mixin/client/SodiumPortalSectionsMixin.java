package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.sodium.SodiumPortalSections;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.async.CullTask;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.DeferredTaskList;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.SortedRenderLists;
import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.SectionTree;
import net.caffeinemc.mods.sodium.client.render.chunk.storage.SectionStorage;
import net.caffeinemc.mods.sodium.client.render.chunk.tree.RemovableMultiForest;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;

@Pseudo
@Mixin(value = RenderSectionManager.class, remap = false)
public abstract class SodiumPortalSectionsMixin implements SodiumPortalSections {
    @Shadow private SortedRenderLists renderLists;
    @Shadow private SectionTree renderTree;
    @Shadow private DeferredTaskList taskLists;
    @Shadow @Final private SectionStorage renderSections;
    @Shadow @Final private RemovableMultiForest renderableSectionTree;
    @Shadow @Final private ClientLevel level;
    @Shadow private CullTask pendingTask;
    @Shadow private boolean needsGraphUpdate;

    @Shadow
    protected abstract void consumeCullTaskResults(boolean waitForCompletion);

    @Shadow
    protected abstract float getSearchDistance(FogParameters fogParameters);

    @Shadow
    protected abstract float getRenderDistance();

    @Override
    public SortedRenderLists wormholes$renderLists() {
        return renderLists;
    }

    @Override
    public void wormholes$renderLists(SortedRenderLists lists) {
        renderLists = lists;
    }

    @Override
    public SectionTree wormholes$renderTree() {
        return renderTree;
    }

    @Override
    public void wormholes$renderTree(SectionTree tree) {
        renderTree = tree;
    }

    @Override
    public void wormholes$taskLists(DeferredTaskList tasks) {
        taskLists = tasks;
    }

    @Override
    public SectionStorage wormholes$storage() {
        return renderSections;
    }

    @Override
    public RemovableMultiForest wormholes$renderable() {
        return renderableSectionTree;
    }

    @Override
    public ClientLevel wormholes$level() {
        return level;
    }

    @Override
    public boolean wormholes$culling() {
        return pendingTask != null;
    }

    @Override
    public void wormholes$settleCulling() {
        consumeCullTaskResults(true);
    }

    @Override
    public void wormholes$graphUpdated() {
        needsGraphUpdate = false;
    }

    @Override
    public float wormholes$searchDistance(FogParameters fog) {
        return getSearchDistance(fog);
    }

    @Override
    public float wormholes$buildDistance() {
        return getRenderDistance();
    }
}
