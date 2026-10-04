package art.arcane.wormholes.modded.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.backend.opengl.GlProgram;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import net.irisshaders.iris.api.v0.IrisProgram;
import net.irisshaders.iris.pipeline.IrisPipelines;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.irisshaders.iris.pipeline.programs.PartialShader;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.pipeline.programs.ShaderLoadingMap;
import net.irisshaders.iris.pipeline.programs.ShaderMap;
import net.irisshaders.iris.pipeline.programs.ShaderSupplier;
import net.irisshaders.iris.platform.IrisPlatformHelpers;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.irisshaders.iris.shadows.ShadowRenderingState;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import org.junit.Test;
import org.lwjgl.opengl.GL20C;
import org.mockito.MockedStatic;

import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class PortalIrisTerrainTest {
    @Test
    public void terrainOverridesResolveRealIrisProgramsOnColdWarmReloadedAndSwitchedPacks() {
        EnumMap<ChunkSectionLayer, RenderPipeline> normal = new EnumMap<>(ChunkSectionLayer.class);
        EnumMap<ChunkSectionLayer, RenderPipeline> reflected = new EnumMap<>(ChunkSectionLayer.class);
        EnumMap<ShaderKey, GlProgram> programs = new EnumMap<>(ShaderKey.class);
        Map<GlProgram, CompiledRenderPipeline> compiled = new IdentityHashMap<>();
        AtomicReference<ShaderMap> overrides = new AtomicReference<>();
        AtomicReference<RenderPipeline> requested = new AtomicReference<>();
        IrisRenderingPipeline world = mock(IrisRenderingPipeline.class);
        try (MockedStatic<RenderSystem> render = mockStatic(RenderSystem.class);
             MockedStatic<GlStateManager> gl = mockStatic(GlStateManager.class);
             MockedStatic<IrisPlatformHelpers> platform = mockStatic(IrisPlatformHelpers.class);
             MockedStatic<ShadowRenderingState> shadows = mockStatic(ShadowRenderingState.class)) {
            platform.when(IrisPlatformHelpers::getInstance).thenReturn(mock(IrisPlatformHelpers.class));
            gl.when(() -> GlStateManager.glGetProgrami(anyInt(), anyInt())).thenReturn(GL20C.GL_TRUE);
            render.when(() -> RenderSystem.getCompiledPipelineNullable(any(RenderPipeline.class))).thenAnswer(call -> {
                RenderPipeline pipeline = call.getArgument(0);
                requested.set(pipeline);
                ShaderKey key = IrisPipelines.getPipeline(world, pipeline);
                assertNotNull(key);
                GlProgram shader = overrides.get().getShader(key);
                assertNotNull("Missing destination shader " + key + " for " + pipeline.getLocation(), shader);
                return compiled.get(shader);
            });
            for (int generation = 0; generation < 4; generation++) {
                EnumMap<ShaderKey, GlProgram> previous = new EnumMap<>(programs);
                programs.clear();
                compiled.clear();
                for (ShaderKey key : new ShaderKey[] {ShaderKey.TERRAIN_SOLID, ShaderKey.TERRAIN_CUTOUT,
                    ShaderKey.TERRAIN_TRANSLUCENT, ShaderKey.SHADOW_TERRAIN_CUTOUT, ShaderKey.SHADOW_TRANSLUCENT}) {
                    GlProgram shader = mock(GlProgram.class);
                    programs.put(key, shader);
                    compiled.put(shader, mock(CompiledRenderPipeline.class));
                }
                ShaderLoadingMap loading = new ShaderLoadingMap((key, patch) -> programs.containsKey(key)
                    ? new ShaderSupplier(key, new PartialShader(key.ordinal() + 1, 0, 0, 0, 0, 0), () -> programs.get(key))
                    : null);
                overrides.set(new ShaderMap(loading, shader -> false, shader -> { }));
                assertNull(overrides.get().getShader(ShaderKey.SODIUM_TERRAIN_TRANSLUCENT));
                for (boolean mirror : new boolean[] {false, true}) {
                    EnumMap<ChunkSectionLayer, RenderPipeline> cached = mirror ? reflected : normal;
                    for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
                        ShaderKey terrainKey = switch (layer) {
                            case SOLID -> ShaderKey.TERRAIN_SOLID;
                            case CUTOUT -> ShaderKey.TERRAIN_CUTOUT;
                            case TRANSLUCENT -> ShaderKey.TERRAIN_TRANSLUCENT;
                        };
                        assertSame(compiled.get(programs.get(terrainKey)), PortalIrisTerrain.get(layer, mirror));
                        assertSame(terrainKey, IrisPipelines.getPipeline(world, requested.get()));
                        if (generation == 0) {
                            cached.put(layer, requested.get());
                        } else {
                            assertSame(cached.get(layer), requested.get());
                            assertNotSame(previous.get(terrainKey), programs.get(terrainKey));
                        }
                        assertSame(compiled.get(programs.get(terrainKey)), PortalIrisTerrain.get(layer, mirror));
                        assertSame(cached.get(layer), requested.get());
                        shadows.when(ShadowRenderingState::areShadowsCurrentlyBeingRendered).thenReturn(true);
                        ShaderKey shadowKey = layer == ChunkSectionLayer.TRANSLUCENT
                            ? ShaderKey.SHADOW_TRANSLUCENT : ShaderKey.SHADOW_TERRAIN_CUTOUT;
                        assertSame(compiled.get(programs.get(shadowKey)), PortalIrisTerrain.get(layer, mirror));
                        assertSame(shadowKey, IrisPipelines.getPipeline(world, requested.get()));
                        shadows.when(ShadowRenderingState::areShadowsCurrentlyBeingRendered).thenReturn(false);
                    }
                }
            }
            assertSame(ShaderKey.SODIUM_TERRAIN_TRANSLUCENT,
                ShaderKey.findBestMatch(normal.get(ChunkSectionLayer.TRANSLUCENT), ProgramId.fromAPI(IrisProgram.TRANSLUCENT)));
            assertSame(ShaderKey.SODIUM_TERRAIN_TRANSLUCENT,
                ShaderKey.findBestMatch(reflected.get(ChunkSectionLayer.TRANSLUCENT), ProgramId.fromAPI(IrisProgram.TRANSLUCENT)));
        }
    }

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
