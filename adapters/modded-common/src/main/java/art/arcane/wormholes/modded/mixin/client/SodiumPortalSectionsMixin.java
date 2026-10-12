package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.sodium.SodiumArrivalPreparation;
import art.arcane.wormholes.modded.client.render.sodium.SodiumPortalSections;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.async.CullTask;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.DeferredTaskList;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.SortedRenderLists;
import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.SectionTree;
import net.caffeinemc.mods.sodium.client.render.chunk.storage.SectionStorage;
import net.caffeinemc.mods.sodium.client.render.chunk.tree.RemovableMultiForest;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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
    @Unique private RemovableMultiForest wormholes$arrivalSections = new RemovableMultiForest(0.0F);
    @Unique private final SodiumArrivalPreparation wormholes$arrivalPreparation = new SodiumArrivalPreparation();
    @Unique private long wormholes$terrainRevision;

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
    public RemovableMultiForest wormholes$arrivalSections() {
        wormholes$arrivalSections.ensureCapacity(getRenderDistance());
        return wormholes$arrivalSections;
    }

    @Override
    public SodiumArrivalPreparation wormholes$arrivalPreparation() {
        return wormholes$arrivalPreparation;
    }

    @Override
    public long wormholes$terrainRevision() {
        return wormholes$terrainRevision;
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

    @Inject(method = "onSectionAdded", at = @At("RETURN"))
    private void wormholes$trackArrivalSection(int x, int y, int z, CallbackInfo callback) {
        RenderSection section = renderSections.getConsistent(SectionPos.asLong(x, y, z));
        if (section != null) {
            wormholes$arrivalSections.add(section);
            wormholes$terrainRevision++;
        }
    }

    @Inject(method = "onSectionRemoved", at = @At("HEAD"))
    private void wormholes$forgetArrivalSection(int x, int y, int z, CallbackInfo callback) {
        wormholes$arrivalSections.remove(x, y, z);
        wormholes$terrainRevision++;
    }

    @Inject(method = {"scheduleRebuild", "scheduleSort"}, at = @At("HEAD"))
    private void wormholes$dirtyArrivalTerrain(CallbackInfo callback) {
        wormholes$terrainRevision++;
    }

    @Inject(method = "destroy", at = @At("RETURN"))
    private void wormholes$releaseArrivalTerrain(CallbackInfo callback) {
        wormholes$arrivalSections = new RemovableMultiForest(0.0F);
    }
}
