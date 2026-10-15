package art.arcane.wormholes.modded.client.render.iris;

import art.arcane.wormholes.modded.mixin.client.IrisPortalSodiumProgramsAccess;
import net.caffeinemc.mods.sodium.client.gl.shader.GlProgram;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ChunkShaderInterface;
import net.irisshaders.iris.pipeline.programs.SodiumPrograms;
import org.junit.Test;

import java.util.EnumMap;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

public class IrisSodiumProgramsTest {
    @Test
    public void destroyDeletesEveryTerrainProgramOnce() {
        EnumMap<SodiumPrograms.Pass, GlProgram<ChunkShaderInterface>> shaders = new EnumMap<>(SodiumPrograms.Pass.class);
        GlProgram<ChunkShaderInterface> terrain = program();
        GlProgram<ChunkShaderInterface> shadow = program();
        shaders.put(SodiumPrograms.Pass.TERRAIN, terrain);
        shaders.put(SodiumPrograms.Pass.SHADOW, shadow);
        SodiumPrograms programs = mock(SodiumPrograms.class, withSettings().extraInterfaces(IrisPortalSodiumProgramsAccess.class));
        when(((IrisPortalSodiumProgramsAccess) programs).wormholes$shaders()).thenReturn(shaders);
        IrisSodiumPrograms.destroy(programs);
        IrisSodiumPrograms.destroy(programs);
        assertTrue("a destroyed pipeline must not keep its Sodium terrain programs", shaders.isEmpty());
        verify(terrain, times(1)).delete();
        verify(shadow, times(1)).delete();
    }

    @SuppressWarnings("unchecked")
    private static GlProgram<ChunkShaderInterface> program() {
        return mock(GlProgram.class);
    }
}
