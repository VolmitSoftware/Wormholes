package art.arcane.wormholes.modded.client.render.sodium;

import it.unimi.dsi.fastutil.longs.Long2ReferenceMap;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkUpdateType;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.render.chunk.lists.SortedRenderLists;
import me.jellysquid.mods.sodium.client.render.chunk.region.RenderRegionManager;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.minecraft.client.multiplayer.ClientLevel;

import java.util.ArrayDeque;
import java.util.Map;

public interface SodiumPortalSections {
    SortedRenderLists wormholes$renderLists();

    void wormholes$renderLists(SortedRenderLists lists);

    Map<ChunkUpdateType, ArrayDeque<RenderSection>> wormholes$rebuildLists();

    void wormholes$rebuildLists(Map<ChunkUpdateType, ArrayDeque<RenderSection>> lists);

    int wormholes$updatedFrame();

    void wormholes$updatedFrame(int frame);

    Long2ReferenceMap<RenderSection> wormholes$storage();

    RenderRegionManager wormholes$regions();

    ChunkVertexType wormholes$vertexType();

    ClientLevel wormholes$level();

    SodiumArrivalPreparation wormholes$arrivalPreparation();

    long wormholes$terrainRevision();

    float wormholes$buildDistance();
}
