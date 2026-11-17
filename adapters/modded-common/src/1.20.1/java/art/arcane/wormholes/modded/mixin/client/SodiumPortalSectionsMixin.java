package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.sodium.SodiumArrivalPreparation;
import art.arcane.wormholes.modded.client.render.sodium.SodiumPortalSections;
import it.unimi.dsi.fastutil.longs.Long2ReferenceMap;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkUpdateType;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager;
import me.jellysquid.mods.sodium.client.render.chunk.lists.SortedRenderLists;
import me.jellysquid.mods.sodium.client.render.chunk.region.RenderRegionManager;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayDeque;
import java.util.Map;

@Pseudo
@Mixin(value = RenderSectionManager.class, remap = false)
public abstract class SodiumPortalSectionsMixin implements SodiumPortalSections {
    @Shadow private SortedRenderLists renderLists;
    @Shadow private Map<ChunkUpdateType, ArrayDeque<RenderSection>> rebuildLists;
    @Shadow private int lastUpdatedFrame;
    @Shadow @Final private Long2ReferenceMap<RenderSection> sectionByPosition;
    @Shadow @Final private RenderRegionManager regions;
    @Shadow @Final private ChunkRenderer chunkRenderer;
    @Shadow @Final private ClientLevel world;
    @Shadow @Final private int renderDistance;
    @Unique private final SodiumArrivalPreparation wormholes$arrivalPreparation = new SodiumArrivalPreparation();
    @Unique private long wormholes$terrainRevision;

    @Override
    public SortedRenderLists wormholes$renderLists() {
        return renderLists;
    }

    @Override
    public void wormholes$renderLists(SortedRenderLists lists) {
        renderLists = lists;
    }

    @Override
    public Map<ChunkUpdateType, ArrayDeque<RenderSection>> wormholes$rebuildLists() {
        return rebuildLists;
    }

    @Override
    public void wormholes$rebuildLists(Map<ChunkUpdateType, ArrayDeque<RenderSection>> lists) {
        rebuildLists = lists;
    }

    @Override
    public int wormholes$updatedFrame() {
        return lastUpdatedFrame;
    }

    @Override
    public void wormholes$updatedFrame(int frame) {
        lastUpdatedFrame = frame;
    }

    @Override
    public Long2ReferenceMap<RenderSection> wormholes$storage() {
        return sectionByPosition;
    }

    @Override
    public RenderRegionManager wormholes$regions() {
        return regions;
    }

    @Override
    public ChunkVertexType wormholes$vertexType() {
        return ((SodiumPortalVertexTypeAccess) chunkRenderer).wormholes$vertexType();
    }

    @Override
    public ClientLevel wormholes$level() {
        return world;
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
    public float wormholes$buildDistance() {
        return renderDistance * 16.0F;
    }

    @Inject(method = {"onSectionAdded", "onSectionRemoved", "scheduleRebuild"}, at = @At("HEAD"))
    private void wormholes$dirtyArrivalTerrain(CallbackInfo callback) {
        wormholes$terrainRevision++;
    }
}
