package art.arcane.wormholes.modded.client.render.sodium;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.SortedRenderLists;
import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.SectionTree;
import net.caffeinemc.mods.sodium.client.util.FogParameters;

record SodiumTerrainState(SortedRenderLists lists, SectionTree tree, FogParameters fog, GpuBufferSlice uniforms, boolean uniformsWritten,
                          ChunkRenderMatrices matrices, boolean[] draws) {
}
