package art.arcane.wormholes.modded.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.util.OptionalDouble;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class PortalIrisTerrainTest {
    @Test
    public void terrainLayersUseTheirOwnIrisRenderingPhase() {
        assertEquals(WorldRenderingPhase.TERRAIN_SOLID, PortalIrisTerrain.phase(ChunkSectionLayer.SOLID));
        assertEquals(WorldRenderingPhase.TERRAIN_CUTOUT, PortalIrisTerrain.phase(ChunkSectionLayer.CUTOUT));
        assertEquals(WorldRenderingPhase.TERRAIN_TRANSLUCENT, PortalIrisTerrain.phase(ChunkSectionLayer.TRANSLUCENT));
    }

    @Test
    public void atlasSamplingUsesIrisNearestPolicyAndPackAnisotropyRestriction() {
        GpuDevice device = mock(GpuDevice.class);
        GpuSampler unrestricted = mock(GpuSampler.class);
        GpuSampler restricted = mock(GpuSampler.class);
        WorldRenderingSettings settings = WorldRenderingSettings.INSTANCE;
        boolean previous = settings.breaksAnisotropy();
        try (MockedStatic<RenderSystem> render = mockStatic(RenderSystem.class)) {
            render.when(RenderSystem::getDevice).thenReturn(device);
            when(device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                FilterMode.NEAREST, FilterMode.NEAREST, 13, OptionalDouble.empty())).thenReturn(unrestricted);
            when(device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                FilterMode.NEAREST, FilterMode.NEAREST, 1, OptionalDouble.empty())).thenReturn(restricted);
            settings.setBreaksAnisotropy(false);
            assertSame(unrestricted, PortalIrisTerrain.sampler(13));
            assertSame(unrestricted, PortalIrisTerrain.sampler(13));
            settings.setBreaksAnisotropy(true);
            assertSame(restricted, PortalIrisTerrain.sampler(14));
            verify(device).createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                FilterMode.NEAREST, FilterMode.NEAREST, 13, OptionalDouble.empty());
            verify(device).createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                FilterMode.NEAREST, FilterMode.NEAREST, 1, OptionalDouble.empty());
        } finally {
            settings.setBreaksAnisotropy(previous);
        }
    }
}
