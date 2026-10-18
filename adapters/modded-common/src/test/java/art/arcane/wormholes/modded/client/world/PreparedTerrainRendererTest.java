package art.arcane.wormholes.modded.client.world;

import art.arcane.wormholes.modded.MinecraftTestBase;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class PreparedTerrainRendererTest extends MinecraftTestBase {
    @Test
    public void cpuMeshesDoNotCountUntilEveryNonemptyLayerIsUploaded() {
        assertFalse(PreparedTerrainRenderer.uploaded(CompiledSectionMesh.UNCOMPILED));
        assertTrue(PreparedTerrainRenderer.uploaded(CompiledSectionMesh.EMPTY));
        CompiledSectionMesh mesh = mock(CompiledSectionMesh.class);
        assertFalse(PreparedTerrainRenderer.uploaded(mesh));
        for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
            when(mesh.isVertexBufferUploaded(layer)).thenReturn(true);
        }
        assertFalse(PreparedTerrainRenderer.uploaded(mesh));
        for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
            when(mesh.isIndexBufferUploaded(layer)).thenReturn(true);
        }
        assertTrue(PreparedTerrainRenderer.uploaded(mesh));
    }
}
