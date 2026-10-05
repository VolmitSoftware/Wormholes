package art.arcane.wormholes.modded.client.render;

import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.mixin.client.SodiumPreparedSectionStateMixin;
import art.arcane.wormholes.modded.mixin.client.SodiumPreparedSectionsMixin;
import net.caffeinemc.mods.sodium.client.render.chunk.async.CullTask;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionInfo;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.SectionTree;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.EnumMap;
import art.arcane.wormholes.modded.mixin.client.SodiumPreparedColumnsMixin;
import net.caffeinemc.mods.sodium.client.render.chunk.storage.QueuedSectionStorage;
import net.caffeinemc.mods.sodium.client.render.chunk.storage.SectionStorage;
import net.caffeinemc.mods.sodium.client.render.chunk.tree.RemovableMultiForest;
import net.caffeinemc.mods.sodium.client.gpu.arena.ArenaAggregator;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import art.arcane.wormholes.modded.mixin.client.SodiumPreparedSectionFadeMixin;
import art.arcane.wormholes.modded.mixin.client.SodiumPreparedExtractorMixin;
import art.arcane.wormholes.modded.mixin.client.SodiumPreparedRendererMixin;
import org.spongepowered.asm.mixin.injection.Inject;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.DeferredTaskList;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.VisibleChunkCollector;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.TaskCollectingTree;
import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.CullType;
import net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkTracker;
import net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkTrackerHolder;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkUpdateTypes;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkSortOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkJob;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.DynamicGpuDataStorage;
import net.minecraft.client.renderer.DynamicGpuDataStorage.DynamicGpuData;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.TextureFilteringMethod;
import net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.mixin.core.render.texture.TextureAtlasAccessor;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import art.arcane.wormholes.modded.mixin.client.SodiumPreparedTerrainReloadMixin;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.GpuFence;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.DynamicGpuDataStorageMapped;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.joml.Matrix4f;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.SectionPos;
import net.minecraft.core.Direction;
import net.minecraft.client.renderer.chunk.VisibilitySet;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.InOrder;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import art.arcane.wormholes.network.client.ClientViewEnvironment;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.spy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;

public class ClientSodiumTerrainTest extends MinecraftTestBase {
    @Test
    public void preparedNativeDrawsAdvanceOncePerFrameAndPipelineReplacementRevokesReadiness() throws Exception {
        Minecraft minecraft = mock(Minecraft.class);
        set(minecraft, "options", mock(Options.class));
        when(minecraft.options.getEffectiveRenderDistance()).thenReturn(10);
        ClientLevel level = mock(ClientLevel.class);
        SodiumWorldRenderer renderer = mock(SodiumWorldRenderer.class, withSettings().extraInterfaces(PortalSodiumTerrainAccess.class));
        when(renderer.isTerrainRenderComplete()).thenReturn(true);
        PortalSodiumSectionAccess visibility = warmVisibility(renderer);
        PortalIrisSettings settings = mock(PortalIrisSettings.class);
        when(settings.terrainCompatible(settings)).thenReturn(true);
        ClientSodiumTerrain.State state = new ClientSodiumTerrain.State(new ClientSodiumTerrain.Ownership(level, renderer, settings, 10));
        set(state, "warmEnvironment", mock(ClientViewEnvironment.class));
        set(state, "warmCamera", new CameraRenderState());
        Object pipeline = new Object();
        AtomicInteger frame = new AtomicInteger(100);
        List<ClientSodiumTerrain.WarmStage> stages = new ArrayList<>();
        Map<ClientLevel, ClientSodiumTerrain.State> states = states();
        states.put(level, state);
        try (MockedStatic<Minecraft> client = mockStatic(Minecraft.class);
             MockedStatic<PortalShaderScope> shaders = mockStatic(PortalShaderScope.class);
             MockedStatic<PortalIrisSettings> material = mockStatic(PortalIrisSettings.class);
             MockedStatic<PortalIrisMainPipelines> iris = mockStatic(PortalIrisMainPipelines.class)) {
            client.when(Minecraft::getInstance).thenReturn(minecraft);
            shaders.when(PortalShaderScope::shaders).thenReturn(true);
            material.when(PortalIrisSettings::capture).thenReturn(settings);
            iris.when(PortalIrisMainPipelines::nativeFrame).thenAnswer(call -> frame.get());
            iris.when(() -> PortalIrisMainPipelines.nativePipeline(level)).thenReturn(pipeline);
            iris.when(() -> PortalIrisMainPipelines.warmNative(any())).thenAnswer(call -> {
                stages.add(call.<PortalIrisMainPipelines.NativeDraw>getArgument(0).stage());
                return true;
            });
            when(visibility.wormholes$visibilityReady()).thenReturn(false);
            ClientSodiumTerrain.warmPrepared();
            assertTrue(stages.isEmpty());
            verify((PortalSodiumTerrainAccess) renderer, never()).wormholes$prepareFrame();
            when(visibility.wormholes$visibilityReady()).thenReturn(true);
            ClientSodiumTerrain.warmPrepared();
            assertTrue(stages.isEmpty());
            Viewport viewport = warmViewport(level, state);
            when(level.getSectionsCount()).thenReturn(1);
            LevelChunk chunk = mock(LevelChunk.class);
            LevelChunkSection section = mock(LevelChunkSection.class);
            when(chunk.getSection(0)).thenReturn(section);
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    when(level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false)).thenReturn(chunk);
                }
            }
            when(viewport.getTransform()).thenReturn(new CameraTransform(0, 8, 0));
            when(viewport.isBoxVisible(8, 8, 8)).thenReturn(true);
            ClientSodiumTerrain.warmPrepared();
            assertTrue(stages.isEmpty());
            when(renderer.isSectionReady(0, 0, 0)).thenReturn(true);
            ClientSodiumTerrain.warmPrepared();
            assertTrue(stages.isEmpty());
            when(((PortalSodiumTerrainAccess) renderer).wormholes$sectionSettled(0, 0, 0)).thenReturn(true);
            for (int index = 0; index < 7; index++) {
                assertFalse(state.warmed());
                if (index == 2) {
                    when(visibility.wormholes$visibilityReady()).thenReturn(false);
                    ClientSodiumTerrain.warmPrepared();
                    assertEquals(2, stages.size());
                    when(visibility.wormholes$visibilityReady()).thenReturn(true);
                }
                ClientSodiumTerrain.warmPrepared();
                ClientSodiumTerrain.warmPrepared();
                verify(renderer, times(index)).endFrame();
                ClientSodiumTerrain.endFrame();
                ClientSodiumTerrain.endFrame();
                frame.incrementAndGet();
            }
            assertEquals(List.of(ClientSodiumTerrain.WarmStage.SKY, ClientSodiumTerrain.WarmStage.SOLID,
                ClientSodiumTerrain.WarmStage.CUTOUT, ClientSodiumTerrain.WarmStage.TRANSLUCENT,
                ClientSodiumTerrain.WarmStage.HAND_SOLID, ClientSodiumTerrain.WarmStage.HAND_TRANSLUCENT,
                ClientSodiumTerrain.WarmStage.POST), stages);
            assertTrue(state.warmed());
            verify(renderer, times(7)).endFrame();
            verify((PortalSodiumTerrainAccess) renderer, times(7)).wormholes$prepareFrame();
            set(state, "ready", true);
            state.selectWarmPipeline(new Object());
            assertFalse(state.warmed());
            Field readiness = ClientSodiumTerrain.State.class.getDeclaredField("ready");
            readiness.setAccessible(true);
            assertFalse(readiness.getBoolean(state));
            shaders.when(PortalShaderScope::shaders).thenReturn(false);
            assertTrue(state.warmed());
            ClientSodiumTerrain.warmPrepared();
            assertEquals(7, stages.size());
        } finally {
            states.clear();
        }
    }

    @Test
    public void failedPreparedDrawRetainsTheOwnedRendererAndStopsWarmRetries() throws Exception {
        Minecraft minecraft = mock(Minecraft.class);
        set(minecraft, "options", mock(Options.class));
        when(minecraft.options.getEffectiveRenderDistance()).thenReturn(10);
        ClientLevel level = mock(ClientLevel.class);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        SodiumWorldRenderer renderer = mock(SodiumWorldRenderer.class, withSettings().extraInterfaces(PortalSodiumTerrainAccess.class));
        when(renderer.isTerrainRenderComplete()).thenReturn(true);
        warmVisibility(renderer);
        PortalIrisSettings settings = mock(PortalIrisSettings.class);
        when(settings.terrainCompatible(settings)).thenReturn(true);
        ClientSodiumTerrain.State state = new ClientSodiumTerrain.State(new ClientSodiumTerrain.Ownership(level, renderer, settings, 10));
        warmViewport(level, state);
        set(state, "warmEnvironment", mock(ClientViewEnvironment.class));
        set(state, "warmCamera", new CameraRenderState());
        Map<ClientLevel, ClientSodiumTerrain.State> states = states();
        states.put(level, state);
        try (MockedStatic<Minecraft> client = mockStatic(Minecraft.class);
             MockedStatic<PortalShaderScope> shaders = mockStatic(PortalShaderScope.class);
             MockedStatic<PortalIrisSettings> material = mockStatic(PortalIrisSettings.class);
             MockedStatic<PortalIrisMainPipelines> iris = mockStatic(PortalIrisMainPipelines.class)) {
            client.when(Minecraft::getInstance).thenReturn(minecraft);
            shaders.when(PortalShaderScope::shaders).thenReturn(true);
            material.when(PortalIrisSettings::capture).thenReturn(settings);
            iris.when(PortalIrisMainPipelines::nativeFrame).thenReturn(200, 201);
            iris.when(() -> PortalIrisMainPipelines.nativePipeline(level)).thenReturn(new Object());
            iris.when(() -> PortalIrisMainPipelines.warmNative(any())).thenThrow(new IllegalStateException("driver draw failed"));
            ClientSodiumTerrain.warmPrepared();
            ClientSodiumTerrain.warmPrepared();
            assertFalse(state.warmed());
            assertSame(state, states.get(level));
            assertFalse(ClientSodiumTerrain.usesPreparedTerrain(level));
            verify(renderer, never()).endFrame();
            ClientSodiumTerrain.endFrame();
            ClientSodiumTerrain.endFrame();
            verify(renderer).endFrame();
            verify(renderer, never()).setLevel(null);
        } finally {
            states.clear();
        }
    }

    private static PortalSodiumSectionAccess warmVisibility(SodiumWorldRenderer renderer) {
        RenderSectionManager manager = mock(RenderSectionManager.class, withSettings().extraInterfaces(PortalSodiumSectionAccess.class));
        when(((PortalSodiumTerrainAccess) renderer).wormholes$terrainManager()).thenReturn(manager);
        PortalSodiumSectionAccess visibility = (PortalSodiumSectionAccess) manager;
        when(visibility.wormholes$visibilityReady()).thenReturn(true);
        return visibility;
    }

    private static Viewport warmViewport(ClientLevel level, ClientSodiumTerrain.State state) throws Exception {
        Viewport viewport = mock(Viewport.class);
        ClientChunkCache chunks = mock(ClientChunkCache.class);
        when(viewport.getChunkCoord()).thenReturn(SectionPos.of(0, 0, 0));
        when(level.getChunkSource()).thenReturn(chunks);
        set(state, "viewport", viewport);
        return viewport;
    }

    @Test
    @SuppressWarnings("unchecked")
    public void consecutiveIndependentUniformFramesRefreshRealManagerAfterStorageRotation() throws Exception {
        UniformBufferManager uniforms = mock(UniformBufferManager.class, CALLS_REAL_METHODS);
        DynamicGpuDataStorage<DynamicGpuData> storage = mock(DynamicGpuDataStorage.class);
        set(uniforms, "uniformStorage", storage);
        GpuBufferSlice first = mock(GpuBufferSlice.class);
        GpuBufferSlice second = mock(GpuBufferSlice.class);
        when(storage.writeData(any(DynamicGpuData.class))).thenReturn(first, second);
        SodiumPreparedTerrainReloadMixin access = mock(SodiumPreparedTerrainReloadMixin.class, CALLS_REAL_METHODS);
        Field managerField = SodiumPreparedTerrainReloadMixin.class.getDeclaredField("uniformBufferManager");
        managerField.setAccessible(true);
        managerField.set(access, uniforms);
        Minecraft minecraft = mock(Minecraft.class);
        set(minecraft, "options", mock(Options.class));
        TextureManager textures = mock(TextureManager.class);
        TextureAtlas atlas = mock(TextureAtlas.class, withSettings().extraInterfaces(TextureAtlasAccessor.class));
        when(minecraft.getTextureManager()).thenReturn(textures);
        when(textures.getTexture(TextureAtlas.LOCATION_BLOCKS)).thenReturn(atlas);
        when(((TextureAtlasAccessor) atlas).sodium$getWidth()).thenReturn(1024);
        when(((TextureAtlasAccessor) atlas).sodium$getHeight()).thenReturn(1024);
        OptionInstance<Double> fade = mock(OptionInstance.class);
        when(fade.get()).thenReturn(0.25);
        when(minecraft.options.chunkSectionFadeInTime()).thenReturn(fade);
        OptionInstance<TextureFilteringMethod> filtering = mock(OptionInstance.class);
        when(filtering.get()).thenReturn(TextureFilteringMethod.NONE);
        when(minecraft.options.textureFiltering()).thenReturn(filtering);
        ChunkRenderMatrices matrices = new ChunkRenderMatrices(new Matrix4f(), new Matrix4f());
        try (MockedStatic<Minecraft> client = mockStatic(Minecraft.class)) {
            client.when(Minecraft::getInstance).thenReturn(minecraft);
            access.wormholes$prepareFrame();
            uniforms.update(matrices, FogParameters.NONE);
            assertSame(first, uniforms.getUniformBuffer());
            uniforms.endFrame();
            uniforms.update(matrices, FogParameters.NONE);
            assertThrows(IllegalStateException.class, uniforms::getUniformBuffer);
            access.wormholes$prepareFrame();
            uniforms.update(matrices, FogParameters.NONE);
            assertSame(second, uniforms.getUniformBuffer());
            uniforms.endFrame();
        }
        verify(storage, times(2)).writeData(any(DynamicGpuData.class));
        verify(storage, times(2)).endFrame();
    }

    @Test
    @SuppressWarnings("unchecked")
    public void setupOnlyCatchupDoesNotRotateRealUniformRingBeforeWarmDrawSubmission() throws Exception {
        Minecraft minecraft = mock(Minecraft.class);
        set(minecraft, "options", mock(Options.class));
        when(minecraft.options.getEffectiveRenderDistance()).thenReturn(10);
        TextureManager textures = mock(TextureManager.class);
        TextureAtlas atlas = mock(TextureAtlas.class, withSettings().extraInterfaces(TextureAtlasAccessor.class));
        when(minecraft.getTextureManager()).thenReturn(textures);
        when(textures.getTexture(TextureAtlas.LOCATION_BLOCKS)).thenReturn(atlas);
        when(((TextureAtlasAccessor) atlas).sodium$getWidth()).thenReturn(1024);
        when(((TextureAtlasAccessor) atlas).sodium$getHeight()).thenReturn(1024);
        OptionInstance<Double> fade = mock(OptionInstance.class);
        when(fade.get()).thenReturn(0.25);
        when(minecraft.options.chunkSectionFadeInTime()).thenReturn(fade);
        OptionInstance<TextureFilteringMethod> filtering = mock(OptionInstance.class);
        when(filtering.get()).thenReturn(TextureFilteringMethod.NONE);
        when(minecraft.options.textureFiltering()).thenReturn(filtering);
        ClientLevel level = mock(ClientLevel.class);
        SodiumWorldRenderer renderer = mock(SodiumWorldRenderer.class, withSettings().extraInterfaces(PortalSodiumTerrainAccess.class));
        when(renderer.isTerrainRenderComplete()).thenReturn(true);
        warmVisibility(renderer);
        Viewport viewport = mock(Viewport.class);
        when(viewport.getChunkCoord()).thenReturn(SectionPos.of(0, 0, 0));
        when(level.getChunkSource()).thenReturn(mock(ClientChunkCache.class));
        PortalIrisSettings settings = mock(PortalIrisSettings.class);
        when(settings.terrainCompatible(settings)).thenReturn(true);
        ClientSodiumTerrain.State state = new ClientSodiumTerrain.State(new ClientSodiumTerrain.Ownership(level, renderer, settings, 10));
        Map<ClientLevel, ClientSodiumTerrain.State> states = states();
        states.put(level, state);
        set(state, "viewport", viewport);
        set(state, "warmEnvironment", PortalEnvironmentTest.environment(ClientViewEnvironment.Transform.IDENTITY));
        set(state, "warmCamera", new CameraRenderState());
        Method prepare = ClientSodiumTerrain.class.getDeclaredMethod("prepareTerrain", ClientSodiumTerrain.State.class,
            Camera.class, FogParameters.class, Matrix4f.class);
        prepare.setAccessible(true);
        Camera camera = mock(Camera.class);
        AtomicInteger submit = new AtomicInteger();
        AtomicInteger frame = new AtomicInteger(300);
        AtomicInteger fences = new AtomicInteger();
        GpuDevice device = mock(GpuDevice.class);
        CommandEncoder encoder = mock(CommandEncoder.class);
        when(device.createCommandEncoder()).thenReturn(encoder);
        when(device.createBuffer(any(), anyInt(), anyLong())).thenAnswer(call -> {
            GpuBuffer buffer = mock(GpuBuffer.class, CALLS_REAL_METHODS);
            when(buffer.size()).thenReturn(call.getArgument(2));
            when(buffer.map(anyLong(), anyLong(), anyBoolean(), anyBoolean())).thenAnswer(mapping ->
                new GpuBufferSlice.MappedView(new GpuBufferSlice(buffer, mapping.getArgument(0), mapping.getArgument(1)),
                    ByteBuffer.allocateDirect(Math.toIntExact(mapping.<Long>getArgument(1))).order(ByteOrder.nativeOrder()), () -> {}));
            return buffer;
        });
        when(encoder.createFence()).thenAnswer(call -> {
            fences.incrementAndGet();
            int capturedSubmit = submit.get();
            GpuFence fence = mock(GpuFence.class);
            when(fence.awaitCompletion(anyLong())).thenAnswer(wait -> {
                if (capturedSubmit == submit.get()) {
                    throw new IllegalStateException("Cannot wait on a fence for the current submit");
                }
                return true;
            });
            return fence;
        });
        try (MockedStatic<RenderSystem> render = mockStatic(RenderSystem.class);
             MockedStatic<Minecraft> client = mockStatic(Minecraft.class);
             MockedStatic<PortalShaderScope> shaders = mockStatic(PortalShaderScope.class);
             MockedStatic<PortalIrisSettings> material = mockStatic(PortalIrisSettings.class);
             MockedStatic<PortalIrisMainPipelines> iris = mockStatic(PortalIrisMainPipelines.class)) {
            render.when(RenderSystem::getDevice).thenReturn(device);
            client.when(Minecraft::getInstance).thenReturn(minecraft);
            shaders.when(PortalShaderScope::shaders).thenReturn(true);
            material.when(PortalIrisSettings::capture).thenReturn(settings);
            iris.when(PortalIrisMainPipelines::nativeFrame).thenAnswer(call -> frame.get());
            Object pipeline = new Object();
            iris.when(() -> PortalIrisMainPipelines.nativePipeline(level)).thenReturn(pipeline);
            try (DynamicGpuDataStorageMapped<DynamicGpuData> poisoned = new DynamicGpuDataStorageMapped<>("poisoned", 1024,
                     GpuBuffer.USAGE_COPY_DST, 4);
                 DynamicGpuDataStorageMapped<DynamicGpuData> storage = new DynamicGpuDataStorageMapped<>("native", 1024,
                     GpuBuffer.USAGE_COPY_DST, 4)) {
                poisoned.endFrame();
                poisoned.endFrame();
                poisoned.endFrame();
                assertThrows(IllegalStateException.class, () -> poisoned.writeData(mock(DynamicGpuData.class)));
                fences.set(0);
                UniformBufferManager uniforms = mock(UniformBufferManager.class, CALLS_REAL_METHODS);
                set(uniforms, "uniformStorage", storage);
                doAnswer(call -> { uniforms.prepareFrame(); return null; }).when(renderer)
                    .setupTerrain(any(), any(), any(), anyBoolean(), anyBoolean(), any());
                doAnswer(call -> { uniforms.prepareFrame(); return null; }).when((PortalSodiumTerrainAccess) renderer).wormholes$prepareFrame();
                doAnswer(call -> { uniforms.endFrame(); return null; }).when(renderer).endFrame();
                iris.when(() -> PortalIrisMainPipelines.warmNative(any())).thenAnswer(call -> {
                    uniforms.update(new ChunkRenderMatrices(new Matrix4f(), new Matrix4f()), FogParameters.NONE);
                    assertTrue(uniforms.getUniformBuffer() != null);
                    return true;
                });
                for (int drawnFrame = 0; drawnFrame < 5; drawnFrame++) {
                    for (int tick = 0; tick < 6; tick++) {
                        prepare.invoke(null, state, camera, FogParameters.NONE, new Matrix4f());
                    }
                    assertFalse(ClientSodiumTerrain.prewarming());
                    assertEquals(drawnFrame, fences.get());
                    ClientSodiumTerrain.warmPrepared();
                    ClientSodiumTerrain.warmPrepared();
                    assertEquals(drawnFrame, fences.get());
                    submit.incrementAndGet();
                    ClientSodiumTerrain.endFrame();
                    ClientSodiumTerrain.endFrame();
                    assertEquals(drawnFrame + 1, fences.get());
                    frame.incrementAndGet();
                }
                verify(renderer, times(30)).setupTerrain(any(), any(), any(), anyBoolean(), anyBoolean(), any());
                verify(renderer, times(5)).endFrame();
                assertFalse(state.warmed());
            }
        } finally {
            states.clear();
        }
    }

    @Test
    public void nativeRendererFrameHookTargetsTheCanonicalLevelRendererEndFrameReturn() throws Exception {
        assertSame(void.class, LevelRenderer.class.getDeclaredMethod("endFrame").getReturnType());
        Method method = SodiumPreparedRendererMixin.class.getDeclaredMethod("wormholes$endPreparedFrame", CallbackInfo.class);
        Inject injection = method.getAnnotation(Inject.class);
        assertEquals(List.of("endFrame"), List.of(injection.method()));
        assertEquals("RETURN", injection.at()[0].value());
        SodiumPreparedRendererMixin callback = mock(SodiumPreparedRendererMixin.class, CALLS_REAL_METHODS);
        method.setAccessible(true);
        try (MockedStatic<ClientSodiumTerrain> terrain = mockStatic(ClientSodiumTerrain.class)) {
            method.invoke(callback, new CallbackInfo("endFrame", false));
            terrain.verify(ClientSodiumTerrain::endFrame);
        }
    }

    @Test
    public void submittedUniformCleanupUsesRendererIdentityAndConsumesPendingOwnershipOnFailure() throws Exception {
        ClientLevel source = mock(ClientLevel.class);
        ClientLevel destination = mock(ClientLevel.class);
        SodiumWorldRenderer oldRenderer = mock(SodiumWorldRenderer.class);
        SodiumWorldRenderer adoptedRenderer = mock(SodiumWorldRenderer.class);
        SodiumWorldRenderer idleRenderer = mock(SodiumWorldRenderer.class);
        ClientSodiumTerrain.State old = new ClientSodiumTerrain.State(new ClientSodiumTerrain.Ownership(source, oldRenderer, null, 10));
        ClientSodiumTerrain.State adopted = new ClientSodiumTerrain.State(new ClientSodiumTerrain.Ownership(destination, adoptedRenderer, null, 10));
        ClientSodiumTerrain.State idle = new ClientSodiumTerrain.State(new ClientSodiumTerrain.Ownership(mock(ClientLevel.class), idleRenderer, null, 10));
        set(old, "uniformFramePending", true);
        set(adopted, "uniformFramePending", true);
        Map<ClientLevel, ClientSodiumTerrain.State> states = states();
        states.put(source, old);
        states.put(destination, adopted);
        states.put(mock(ClientLevel.class), idle);
        try (MockedStatic<SodiumWorldRenderer> sodium = mockStatic(SodiumWorldRenderer.class);
             MockedStatic<PortalShaderScope> shaders = mockStatic(PortalShaderScope.class)) {
            sodium.when(SodiumWorldRenderer::instanceNullable).thenReturn(adoptedRenderer);
            shaders.when(PortalShaderScope::shaders).thenReturn(false);
            IllegalStateException failure = new IllegalStateException("uniform cleanup failed");
            doThrow(failure).when(oldRenderer).endFrame();
            assertSame(failure, assertThrows(IllegalStateException.class, ClientSodiumTerrain::endFrame));
            ClientSodiumTerrain.endFrame();
            verify(oldRenderer).endFrame();
            verify(adoptedRenderer, never()).endFrame();
            verify(idleRenderer, never()).endFrame();
            assertFalse(uniformFramePending(old));
            assertFalse(uniformFramePending(adopted));
        } finally {
            states.clear();
        }
    }

    @Test
    public void queuedPhysicalRemovalAndLaterTrackerRemovalDeleteEachRealRegionSectionOnce() throws Exception {
        long position = SectionPos.asLong(0, 0, 0);
        RenderRegion region = new RenderRegion(0, 0, 0, mock(ArenaAggregator.class));
        RenderSection section = new RenderSection(region, 0, 0, 0);
        region.addSection(section);
        QueuedSectionStorage raw = new QueuedSectionStorage();
        raw.queuePut(position, section);
        raw.startSafeReadPhase();
        assertSame(section, raw.queueRemove(position));
        region.removeSection(section);
        assertSame(section, raw.queueRemove(position));
        assertThrows(IllegalStateException.class, () -> region.removeSection(section));
        raw.endSafeReadPhase();

        QueuedSectionStorage storage = spy(new QueuedSectionStorage());
        SodiumPreparedColumnsMixin mixin = mock(SodiumPreparedColumnsMixin.class, CALLS_REAL_METHODS);
        Method removal = SodiumPreparedColumnsMixin.class.getDeclaredMethod("wormholes$removeSectionOnce", SectionStorage.class,
            long.class, Operation.class);
        removal.setAccessible(true);
        doAnswer(call -> {
            Operation<RenderSection> original = operation();
            when(original.call(storage, call.<Long>getArgument(0))).thenAnswer(arguments -> call.callRealMethod());
            return removal.invoke(mixin, storage, call.<Long>getArgument(0), original);
        }).when(storage).queueRemove(anyLong());
        ClientLevel level = mock(ClientLevel.class);
        when(level.getMinSectionY()).thenReturn(0);
        when(level.getMaxSectionY()).thenReturn(0);
        when(level.getSectionsCount()).thenReturn(1);
        RenderSectionManager manager = mock(RenderSectionManager.class, CALLS_REAL_METHODS);
        setManagerField(manager, "level", level);
        setManagerField(manager, "renderSections", storage);
        setManagerField(manager, "renderableSectionTree", mock(RemovableMultiForest.class));
        setManagerField(manager, "sectionsWithGlobalEntities", new ReferenceOpenHashSet<RenderSection>());
        RenderSection retained = new RenderSection(region, 0, 0, 0);
        region.addSection(retained);
        storage.queuePut(position, retained);
        storage.startSafeReadPhase();
        manager.onSectionAdded(0, 0, 0);
        assertSame(retained, storage.getConsistent(position));
        manager.onSectionRemoved(0, 0, 0);
        assertTrue(retained.isDisposed());
        manager.onChunkRemoved(0, 0);
        manager.onSectionRemoved(0, 0, 0);
        assertTrue(region.isEmpty());
        assertNull(storage.getConsistent(position));
        RenderSection replacement = new RenderSection(region, 0, 0, 0);
        region.addSection(replacement);
        storage.queuePut(position, replacement);
        assertSame(replacement, storage.getConsistent(position));
        manager.onChunkRemoved(0, 0);
        assertTrue(replacement.isDisposed());
        assertTrue(region.isEmpty());
        storage.endSafeReadPhase();
        assertNull(storage.getCurrent(position));
    }

    @Test
    public void neighborLossRetainsOwnedColumnsAndDefersDirtyBuildUntilNeighborLightReturns() throws Exception {
        ClientLevel level = mock(ClientLevel.class, withSettings().extraInterfaces(ChunkTrackerHolder.class));
        ClientChunkCache chunks = mock(ClientChunkCache.class);
        when(level.getChunkSource()).thenReturn(chunks);
        when(level.getMinSectionY()).thenReturn(-4);
        when(level.getSectionsCount()).thenReturn(24);
        LevelChunk column = mock(LevelChunk.class);
        when(chunks.getChunk(4, -21, ChunkStatus.FULL, false)).thenReturn(column);
        ChunkTracker tracker = new ChunkTracker();
        when(((ChunkTrackerHolder) level).sodium$getTracker()).thenReturn(tracker);
        for (int x = 3; x <= 5; x++) {
            for (int z = -22; z <= -20; z++) {
                tracker.onChunkStatusAdded(x, z, 3);
            }
        }
        RenderSectionManager manager = mock(RenderSectionManager.class);
        doCallRealMethod().when(manager).markGraphDirty();
        doCallRealMethod().when(manager).needsUpdate();
        SodiumWorldRenderer renderer = mock(SodiumWorldRenderer.class, withSettings().extraInterfaces(PortalSodiumTerrainAccess.class));
        when(((PortalSodiumTerrainAccess) renderer).wormholes$terrainManager()).thenReturn(manager);
        Map<ClientLevel, ClientSodiumTerrain.State> states = states();
        states.put(level, state(level, renderer));
        try {
            tracker.onChunkStatusRemoved(4, -22, 3);
            assertTrue(ClientSodiumTerrain.retainColumn(level, manager, 4, -21));
            ClientSodiumTerrain.prepareColumns(level, manager);
            verify(manager, never()).onSectionRemoved(4, -4, -21);
            ClientSodiumTerrain.dirty(level, SectionPos.asLong(4, 0, -21));
            verify(renderer).scheduleRebuildForChunk(4, 0, -21, true);
            RenderSection dirty = new RenderSection(mock(RenderRegion.class), 4, 0, -21);
            dirty.setPendingUpdate(ChunkUpdateTypes.REBUILD, System.nanoTime());
            DeferredTaskList queued = pendingTasks(level, dirty);
            assertEquals(dirty.getPosition().asLong(), queued.dequeueNextSectionPos());
            assertTrue(queued.isEmpty());
            assertTrue(ClientSodiumTerrain.deferColumnBuild(level, manager, 4, -21));
            assertEquals(ChunkUpdateTypes.REBUILD, dirty.getPendingUpdate());
            tracker.onChunkStatusAdded(4, -22, 1);
            assertTrue(ClientSodiumTerrain.deferColumnBuild(level, manager, 4, -21));
            tracker.onChunkStatusAdded(4, -22, 2);
            assertFalse(ClientSodiumTerrain.deferColumnBuild(level, manager, 4, -21));
            ClientSodiumTerrain.prepareColumns(level, manager);
            verify(manager).markGraphDirty();
            assertTrue(manager.needsUpdate());
            assertEquals(dirty.getPosition().asLong(), pendingTasks(level, dirty).dequeueNextSectionPos());
            assertEquals(ChunkUpdateTypes.REBUILD, dirty.getPendingUpdate());
            verify(manager, never()).onSectionRemoved(4, -4, -21);
            tracker.onChunkStatusRemoved(4, -22, 3);
            assertTrue(ClientSodiumTerrain.retainColumn(level, manager, 4, -21));
            when(chunks.getChunk(4, -21, ChunkStatus.FULL, false)).thenReturn(null);
            tracker.onChunkStatusRemoved(4, -21, 3);
            ClientSodiumTerrain.columnUnloaded(level, 4, -21);
            for (int y = -4; y < 20; y++) {
                verify(manager).onSectionRemoved(4, y, -21);
            }
            clearInvocations(manager);
            ClientSodiumTerrain.prepareColumns(level, manager);
            verify(manager, never()).onSectionRemoved(4, -4, -21);
            assertFalse(ClientSodiumTerrain.deferColumnBuild(level, manager, 4, -21));
        } finally {
            states.clear();
        }
    }

    @Test
    public void replacingParkedPhysicalIdentityDiscardsOldGpuAndUnownedManagersKeepNormalRemoval() throws Exception {
        ClientLevel level = mock(ClientLevel.class, withSettings().extraInterfaces(ChunkTrackerHolder.class));
        ClientChunkCache chunks = mock(ClientChunkCache.class);
        when(level.getChunkSource()).thenReturn(chunks);
        when(level.getMinSectionY()).thenReturn(-4);
        when(level.getSectionsCount()).thenReturn(24);
        LevelChunk previous = mock(LevelChunk.class);
        LevelChunk replacement = mock(LevelChunk.class);
        when(chunks.getChunk(-7, 9, ChunkStatus.FULL, false)).thenReturn(previous);
        ChunkTracker tracker = new ChunkTracker();
        when(((ChunkTrackerHolder) level).sodium$getTracker()).thenReturn(tracker);
        RenderSectionManager manager = mock(RenderSectionManager.class);
        RenderSectionManager foreign = mock(RenderSectionManager.class);
        SodiumWorldRenderer renderer = mock(SodiumWorldRenderer.class, withSettings().extraInterfaces(PortalSodiumTerrainAccess.class));
        when(((PortalSodiumTerrainAccess) renderer).wormholes$terrainManager()).thenReturn(manager);
        Map<ClientLevel, ClientSodiumTerrain.State> states = states();
        states.put(level, state(level, renderer));
        try {
            assertFalse(ClientSodiumTerrain.retainColumn(level, foreign, -7, 9));
            assertTrue(ClientSodiumTerrain.retainColumn(level, manager, -7, 9));
            when(chunks.getChunk(-7, 9, ChunkStatus.FULL, false)).thenReturn(replacement);
            ClientSodiumTerrain.prepareColumns(level, manager);
            for (int y = -4; y < 20; y++) {
                verify(manager).onSectionRemoved(-7, y, 9);
            }
            verify(manager, never()).onChunkAdded(-7, 9);
            assertFalse(ClientSodiumTerrain.deferColumnBuild(level, manager, -7, 9));
            states.clear();
            assertFalse(ClientSodiumTerrain.retainColumn(level, manager, -7, 9));
        } finally {
            states.clear();
        }
    }

    @Test
    public void retiringOwnedTerrainDropsParkedIdentityAndRestoresOrdinaryRemoval() throws Exception {
        ClientLevel level = mock(ClientLevel.class, withSettings().extraInterfaces(ChunkTrackerHolder.class));
        when(((ChunkTrackerHolder) level).sodium$getTracker()).thenReturn(new ChunkTracker());
        ClientChunkCache chunks = mock(ClientChunkCache.class);
        when(level.getChunkSource()).thenReturn(chunks);
        when(chunks.getChunk(0, 0, ChunkStatus.FULL, false)).thenReturn(mock(LevelChunk.class));
        RenderSectionManager manager = mock(RenderSectionManager.class);
        SodiumWorldRenderer renderer = mock(SodiumWorldRenderer.class, withSettings().extraInterfaces(PortalSodiumTerrainAccess.class));
        when(((PortalSodiumTerrainAccess) renderer).wormholes$terrainManager()).thenReturn(manager);
        ClientSodiumTerrain.State state = state(level, renderer);
        Map<ClientLevel, ClientSodiumTerrain.State> states = states();
        states.put(level, state);
        try {
            assertTrue(ClientSodiumTerrain.retainColumn(level, manager, 0, 0));
            state.close();
            state.close();
            assertFalse(ClientSodiumTerrain.retainColumn(level, manager, 0, 0));
            verify(renderer, times(1)).setLevel(null);
        } finally {
            states.clear();
        }
    }

    @Test
    public void mainUpdateOwnershipRequiresExactActiveLevelAndRendererWhileDirtyTerrainStillReceivesUpdates() throws Exception {
        Minecraft minecraft = mock(Minecraft.class);
        ClientLevel level = mock(ClientLevel.class);
        ClientLevel inactive = mock(ClientLevel.class);
        minecraft.level = level;
        SodiumWorldRenderer renderer = mock(SodiumWorldRenderer.class);
        ClientSodiumTerrain.State state = state(level, renderer);
        Map<ClientLevel, ClientSodiumTerrain.State> states = states();
        states.put(level, state);
        states.put(inactive, state(inactive, renderer));
        try (MockedStatic<Minecraft> clients = mockStatic(Minecraft.class);
             MockedStatic<SodiumWorldRenderer> renderers = mockStatic(SodiumWorldRenderer.class)) {
            clients.when(Minecraft::getInstance).thenReturn(minecraft);
            renderers.when(SodiumWorldRenderer::instanceNullable).thenReturn(renderer);
            assertTrue(ClientSodiumTerrain.handlesMainUpdates(level));
            assertFalse(ClientSodiumTerrain.handlesMainUpdates(inactive));
            assertFalse(ClientSodiumTerrain.handlesMainUpdates(mock(ClientLevel.class)));
            SodiumWorldRenderer foreign = mock(SodiumWorldRenderer.class);
            renderers.when(SodiumWorldRenderer::instanceNullable).thenReturn(foreign);
            assertFalse(ClientSodiumTerrain.handlesMainUpdates(level));
            renderers.when(SodiumWorldRenderer::instanceNullable).thenReturn(renderer);
            set(state, "closed", true);
            assertFalse(ClientSodiumTerrain.handlesMainUpdates(level));
            states.clear();
            assertFalse(ClientSodiumTerrain.handlesMainUpdates(level));
        } finally {
            states.clear();
        }
    }

    @Test
    public void inactiveTerrainClosesOnceAndActiveTerrainStaysOwnedByMainRenderer() throws Exception {
        ClientLevel activeLevel = mock(ClientLevel.class);
        ClientLevel retainedLevel = mock(ClientLevel.class);
        SodiumWorldRenderer active = mock(SodiumWorldRenderer.class);
        SodiumWorldRenderer retained = mock(SodiumWorldRenderer.class);
        Map<ClientLevel, ClientSodiumTerrain.State> states = states();
        states.put(activeLevel, state(activeLevel, active));
        states.put(retainedLevel, state(retainedLevel, retained));
        try (MockedStatic<SodiumWorldRenderer> renderers = mockStatic(SodiumWorldRenderer.class)) {
            renderers.when(SodiumWorldRenderer::instanceNullable).thenReturn(active);
            ClientSodiumTerrain.forget(retainedLevel);
            ClientSodiumTerrain.forget(retainedLevel);
            ClientSodiumTerrain.clear();
            ClientSodiumTerrain.clear();
        } finally {
            states.clear();
        }
        verify(retained, times(1)).setLevel(null);
        verify(active, never()).setLevel(null);
    }

    @Test
    public void failedCleanupStillReleasesEveryOtherInactiveRenderer() throws Exception {
        Map<ClientLevel, ClientSodiumTerrain.State> states = states();
        SodiumWorldRenderer failed = mock(SodiumWorldRenderer.class);
        SodiumWorldRenderer retained = mock(SodiumWorldRenderer.class);
        ClientLevel first = mock(ClientLevel.class);
        ClientLevel second = mock(ClientLevel.class);
        states.put(first, state(first, failed));
        states.put(second, state(second, retained));
        IllegalStateException expected = new IllegalStateException("delete failed");
        doThrow(expected).when(failed).setLevel(null);
        try (MockedStatic<SodiumWorldRenderer> renderers = mockStatic(SodiumWorldRenderer.class)) {
            assertSame(expected, assertThrows(IllegalStateException.class, ClientSodiumTerrain::clear));
            ClientSodiumTerrain.clear();
            assertTrue(states.isEmpty());
        } finally {
            states.clear();
        }
        verify(failed, times(1)).setLevel(null);
        verify(retained, times(1)).setLevel(null);
    }

    @Test
    public void nativeLightNotificationUsesOneExactInactiveSectionAndLeavesActiveOwnedGeometryToVanilla() throws Exception {
        Minecraft minecraft = mock(Minecraft.class);
        when(minecraft.isSameThread()).thenReturn(true);
        ClientLevel active = mock(ClientLevel.class);
        ClientLevel inactive = mock(ClientLevel.class);
        minecraft.level = active;
        SodiumWorldRenderer activeRenderer = mock(SodiumWorldRenderer.class);
        SodiumWorldRenderer retainedRenderer = mock(SodiumWorldRenderer.class);
        ClientSodiumTerrain.State activeState = state(active, activeRenderer);
        ClientSodiumTerrain.State inactiveState = state(inactive, retainedRenderer);
        set(activeState, "ready", true);
        set(inactiveState, "ready", true);
        Map<ClientLevel, ClientSodiumTerrain.State> states = states();
        states.put(active, activeState);
        states.put(inactive, inactiveState);
        long section = SectionPos.asLong(-5, -4, 7);
        try (MockedStatic<Minecraft> clients = mockStatic(Minecraft.class);
             MockedStatic<SodiumWorldRenderer> renderers = mockStatic(SodiumWorldRenderer.class)) {
            clients.when(Minecraft::getInstance).thenReturn(minecraft);
            renderers.when(SodiumWorldRenderer::instanceNullable).thenReturn(activeRenderer);
            ClientSodiumTerrain.lightChanged(active, section, true);
            assertFalse(stateReady(activeState));
            verify(activeRenderer, never()).scheduleRebuildForChunk(anyInt(), anyInt(), anyInt(), anyBoolean());
            ClientSodiumTerrain.lightChanged(inactive, section, false);
            assertFalse(stateReady(inactiveState));
            verify(retainedRenderer).scheduleRebuildForChunk(-5, -4, 7, false);
            verify(retainedRenderer, times(1)).scheduleRebuildForChunk(anyInt(), anyInt(), anyInt(), anyBoolean());
            ClientSodiumTerrain.lightChanged(mock(ClientLevel.class), section, false);
            set(inactiveState, "closed", true);
            ClientSodiumTerrain.lightChanged(inactive, section, false);
            verify(retainedRenderer, times(1)).scheduleRebuildForChunk(anyInt(), anyInt(), anyInt(), anyBoolean());
            SodiumWorldRenderer foreign = mock(SodiumWorldRenderer.class);
            renderers.when(SodiumWorldRenderer::instanceNullable).thenReturn(foreign);
            ClientSodiumTerrain.lightChanged(active, section, true);
            verify(activeRenderer).scheduleRebuildForChunk(-5, -4, 7, false);
        } finally {
            states.clear();
        }
    }

    @Test
    public void offThreadInactiveLightNotificationStillSchedulesAfterTheLevelBecomesActive() throws Exception {
        Minecraft minecraft = mock(Minecraft.class);
        ClientLevel level = mock(ClientLevel.class);
        minecraft.level = mock(ClientLevel.class);
        SodiumWorldRenderer renderer = mock(SodiumWorldRenderer.class);
        ClientSodiumTerrain.State retained = state(level, renderer);
        set(retained, "ready", true);
        Map<ClientLevel, ClientSodiumTerrain.State> states = states();
        states.put(level, retained);
        List<Runnable> callbacks = new ArrayList<>();
        doAnswer(call -> {
            callbacks.add(call.getArgument(0));
            return null;
        }).when(minecraft).execute(any(Runnable.class));
        try (MockedStatic<Minecraft> clients = mockStatic(Minecraft.class)) {
            clients.when(Minecraft::getInstance).thenReturn(minecraft);
            ClientSodiumTerrain.lightChanged(level, SectionPos.asLong(-5, -4, 7), false);
            assertTrue(stateReady(retained));
            verify(renderer, never()).scheduleRebuildForChunk(anyInt(), anyInt(), anyInt(), anyBoolean());
            assertEquals(1, callbacks.size());
            when(minecraft.isSameThread()).thenReturn(true);
            minecraft.level = level;
            try (MockedStatic<SodiumWorldRenderer> renderers = mockStatic(SodiumWorldRenderer.class)) {
                renderers.when(SodiumWorldRenderer::instanceNullable).thenReturn(renderer);
                callbacks.getFirst().run();
            }
            assertFalse(stateReady(retained));
            verify(renderer).scheduleRebuildForChunk(-5, -4, 7, false);
        } finally {
            states.clear();
        }
    }

    @Test
    public void dirtyTerrainInvalidatesNeighborLightingFacesAndClampsWorldHeight() throws Exception {
        ClientLevel level = mock(ClientLevel.class);
        when(level.getMinSectionY()).thenReturn(-4);
        when(level.getSectionsCount()).thenReturn(24);
        SodiumWorldRenderer renderer = mock(SodiumWorldRenderer.class);
        Map<ClientLevel, ClientSodiumTerrain.State> states = states();
        states.put(level, state(level, renderer));
        try {
            ClientSodiumTerrain.dirty(level, SectionPos.asLong(-5, -4, 7));
            for (int x = -6; x <= -4; x++) {
                for (int y = -4; y <= -3; y++) {
                    for (int z = 6; z <= 8; z++) {
                        verify(renderer).scheduleRebuildForChunk(x, y, z, true);
                    }
                }
            }
            verify(renderer, never()).scheduleRebuildForChunk(-5, -5, 7, true);
        } finally {
            states.clear();
        }
    }

    @Test
    public void fatalCleanupStillRetiresAllInactiveOwnershipExactlyOnce() throws Exception {
        Map<ClientLevel, ClientSodiumTerrain.State> states = states();
        ClientLevel first = mock(ClientLevel.class);
        ClientLevel second = mock(ClientLevel.class);
        SodiumWorldRenderer failed = mock(SodiumWorldRenderer.class);
        SodiumWorldRenderer retained = mock(SodiumWorldRenderer.class);
        states.put(first, state(first, failed));
        states.put(second, state(second, retained));
        AssertionError expected = new AssertionError("delete failed");
        doThrow(expected).when(failed).setLevel(null);
        try (MockedStatic<SodiumWorldRenderer> renderers = mockStatic(SodiumWorldRenderer.class)) {
            assertSame(expected, assertThrows(AssertionError.class, ClientSodiumTerrain::clear));
            ClientSodiumTerrain.clear();
            assertTrue(states.isEmpty());
        } finally {
            states.clear();
        }
        verify(failed, times(1)).setLevel(null);
        verify(retained, times(1)).setLevel(null);
    }

    @Test
    public void boundedCacheEvictsOldestInactiveTerrainAndKeepsActiveIdentity() throws Exception {
        Map<ClientLevel, ClientSodiumTerrain.State> states = states();
        ClientLevel active = mock(ClientLevel.class);
        ClientLevel oldest = mock(ClientLevel.class);
        ClientLevel newest = mock(ClientLevel.class);
        SodiumWorldRenderer activeRenderer = mock(SodiumWorldRenderer.class);
        SodiumWorldRenderer oldestRenderer = mock(SodiumWorldRenderer.class);
        SodiumWorldRenderer newestRenderer = mock(SodiumWorldRenderer.class);
        ClientSodiumTerrain.State activeState = state(active, activeRenderer);
        ClientSodiumTerrain.State oldestState = state(oldest, oldestRenderer);
        ClientSodiumTerrain.State newestState = state(newest, newestRenderer);
        set(oldestState, "used", 1L);
        set(newestState, "used", 2L);
        states.put(active, activeState);
        states.put(oldest, oldestState);
        states.put(newest, newestState);
        try {
            Method trim = ClientSodiumTerrain.class.getDeclaredMethod("trim", ClientLevel.class);
            trim.setAccessible(true);
            trim.invoke(null, active);
            assertSame(activeState, states.get(active));
            assertSame(newestState, states.get(newest));
            assertFalse(states.containsKey(oldest));
            verify(oldestRenderer).setLevel(null);
            verify(activeRenderer, never()).setLevel(null);
            verify(newestRenderer, never()).setLevel(null);
        } finally {
            states.clear();
        }
    }

    @Test
    public void oldBuiltMeshIsNotSettledDuringRebuildWorkerOrPendingGpuUpload() throws Exception {
        SodiumPreparedSectionStateMixin section = mock(SodiumPreparedSectionStateMixin.class, CALLS_REAL_METHODS);
        set(section, "runningJobs", List.of());
        assertTrue(section.wormholes$buildSettled());
        set(section, "pendingUpdateType", ChunkUpdateTypes.REBUILD);
        assertFalse(section.wormholes$buildSettled());
        set(section, "pendingUpdateType", 0);
        set(section, "runningJobs", List.of(mock(ChunkJob.class)));
        assertFalse(section.wormholes$buildSettled());
        set(section, "runningJobs", List.of());
        set(section, "pendingBuildOutput", mock(ChunkBuildOutput.class));
        assertFalse(section.wormholes$buildSettled());
        set(section, "pendingBuildOutput", null);
        assertTrue(section.wormholes$buildSettled());
    }

    @Test
    public void preparedTranslucentsRemainPendingUntilQueuedSortAndCompletedIndicesAreConsumed() throws Exception {
        SodiumPreparedSectionStateMixin section = mock(SodiumPreparedSectionStateMixin.class, CALLS_REAL_METHODS);
        set(section, "runningJobs", List.of());
        assertTrue(section.wormholes$buildSettled());
        set(section, "pendingUpdateType", ChunkUpdateTypes.INITIAL_BUILD);
        assertTrue(section.wormholes$buildSettled());
        set(section, "pendingUpdateType", 0);
        for (int update : new int[]{ChunkUpdateTypes.SORT, ChunkUpdateTypes.join(ChunkUpdateTypes.SORT, ChunkUpdateTypes.IMPORTANT)}) {
            set(section, "pendingUpdateType", update);
            assertFalse(section.wormholes$buildSettled());
        }
        set(section, "pendingUpdateType", 0);
        set(section, "runningJobs", List.of(mock(ChunkJob.class)));
        assertFalse(section.wormholes$buildSettled());
        set(section, "runningJobs", List.of());
        set(section, "pendingDynamicSortOutput", mock(ChunkSortOutput.class));
        assertFalse(section.wormholes$buildSettled());
        set(section, "pendingBuildOutput", mock(ChunkBuildOutput.class));
        set(section, "pendingDynamicSortOutput", null);
        assertFalse(section.wormholes$buildSettled());
        set(section, "pendingBuildOutput", null);
        assertTrue(section.wormholes$buildSettled());
        set(section, "pendingUpdateType", ChunkUpdateTypes.SORT);
        assertFalse(section.wormholes$buildSettled());
        set(section, "pendingUpdateType", 0);
        assertTrue(section.wormholes$buildSettled());
    }

    @Test
    public void nativeReadinessUsesCylindricalDistanceAndPaddedSectionEdges() {
        CameraTransform camera = new CameraTransform(-0.5, 64.5, 0.5);
        assertTrue(ClientSodiumTerrain.withinRenderDistance(camera, 10, 4, 0, 160));
        assertFalse(ClientSodiumTerrain.withinRenderDistance(camera, 10, 4, 10, 160));
        assertFalse(ClientSodiumTerrain.withinRenderDistance(camera, 0, 15, 0, 160));
        assertTrue(ClientSodiumTerrain.withinRenderDistance(camera, 0, 14, 0, 160));
        CameraTransform boundary = new CameraTransform(-29_999_999.5, -64.5, 29_999_999.5);
        assertTrue(ClientSodiumTerrain.withinRenderDistance(boundary, -1_875_000, -5, 1_874_999, 160));
        assertFalse(ClientSodiumTerrain.withinRenderDistance(boundary, -1_874_990, -5, 1_875_009, 160));
    }

    @Test
    public void borrowingCurrentRendererDoesNotClaimPreparedDestinationReadiness() throws Exception {
        ClientLevel level = mock(ClientLevel.class);
        SodiumWorldRenderer renderer = mock(SodiumWorldRenderer.class);
        Map<ClientLevel, ClientSodiumTerrain.State> states = states();
        ClientSodiumTerrain.State state = state(level, renderer);
        set(state, "ready", true);
        states.put(level, state);
        try {
            assertFalse(ClientSodiumTerrain.ready(level));
        } finally {
            states.clear();
        }
    }

    @Test
    public void offscreenNativeUploadsConsumeTheirFadeWithoutStartingVisibleArrivalAnimation() throws Exception {
        SodiumPreparedSectionFadeMixin callback = mock(SodiumPreparedSectionFadeMixin.class, CALLS_REAL_METHODS);
        RenderSection section = mock(RenderSection.class);
        Operation<Boolean> consume = mock(Operation.class);
        when(consume.call(section)).thenReturn(true);
        Method method = SodiumPreparedSectionFadeMixin.class.getDeclaredMethod("wormholesPreparedSectionFade", RenderSection.class, Operation.class);
        method.setAccessible(true);
        Field prewarming = ClientSodiumTerrain.class.getDeclaredField("prewarming");
        prewarming.setAccessible(true);
        try (MockedStatic<ClientPortalRenderer> renderers = mockStatic(ClientPortalRenderer.class)) {
            prewarming.setInt(null, 1);
            assertFalse((boolean) method.invoke(callback, section, consume));
            verify(consume, times(1)).call(section);
            renderers.verifyNoInteractions();
        } finally {
            prewarming.setInt(null, 0);
        }
        assertFalse(ClientSodiumTerrain.prewarming());
    }

    @Test
    public void compatiblePreparedAttachPreservesPriorReloadButDoesNotRequestDeferredTerrainDestruction() throws Exception {
        SodiumPreparedExtractorMixin callback = mock(SodiumPreparedExtractorMixin.class, CALLS_REAL_METHODS);
        ClientLevel level = mock(ClientLevel.class);
        Minecraft minecraft = mock(Minecraft.class);
        Field gameRenderer = Minecraft.class.getDeclaredField("gameRenderer");
        gameRenderer.setAccessible(true);
        gameRenderer.set(minecraft, mock(GameRenderer.class));
        LevelRenderer renderer = mock(LevelRenderer.class);
        set(callback, "minecraft", minecraft);
        set(callback, "levelRenderer", renderer);
        Field invalidate = SodiumPreparedExtractorMixin.class.getDeclaredField("shouldInvalidateCompiledGeometry");
        invalidate.setAccessible(true);
        Field reset = SodiumPreparedExtractorMixin.class.getDeclaredField("shouldResetLevelRenderData");
        reset.setAccessible(true);
        Method method = SodiumPreparedExtractorMixin.class.getDeclaredMethod("wormholes$restoreTerrain",
            ClientLevel.class, Operation.class);
        method.setAccessible(true);
        Operation<Void> original = mock(Operation.class);
        doAnswer(invocation -> {
            invalidate.setBoolean(callback, true);
            reset.setBoolean(callback, true);
            return null;
        }).when(original).call(level);
        InOrder order = inOrder(original, renderer);
        try (MockedStatic<ClientSodiumTerrain> terrain = mockStatic(ClientSodiumTerrain.class)) {
            terrain.when(() -> ClientSodiumTerrain.retainLevelInvalidation(level)).thenReturn(true);
            method.invoke(callback, level, original);
            order.verify(original).call(level);
            order.verify(renderer).resetLevelRenderData();
            order.verify(renderer).invalidateCompiledGeometry(level, null, null, null);
            assertFalse(reset.getBoolean(callback));
            assertFalse(invalidate.getBoolean(callback));
            invalidate.setBoolean(callback, true);
            method.invoke(callback, level, original);
            assertTrue(invalidate.getBoolean(callback));
            assertFalse(reset.getBoolean(callback));
            terrain.when(() -> ClientSodiumTerrain.retainLevelInvalidation(level)).thenReturn(false);
            invalidate.setBoolean(callback, false);
            method.invoke(callback, level, original);
            assertTrue(invalidate.getBoolean(callback));
            assertTrue(reset.getBoolean(callback));
            verify(renderer, times(2)).resetLevelRenderData();
            verify(renderer, times(2)).invalidateCompiledGeometry(level, null, null, null);
        }
    }

    @Test
    public void deferredInvalidationRetentionRequiresInstalledExactWorldRendererAndUnchangedSettings() throws Exception {
        Minecraft minecraft = mock(Minecraft.class);
        Options options = mock(Options.class);
        Field optionsField = Minecraft.class.getDeclaredField("options");
        optionsField.setAccessible(true);
        optionsField.set(minecraft, options);
        when(options.getEffectiveRenderDistance()).thenReturn(10);
        ClientLevel level = mock(ClientLevel.class);
        SodiumWorldRenderer renderer = mock(SodiumWorldRenderer.class);
        ClientSodiumTerrain.State state = state(level, renderer);
        ClientSodiumTerrain.Handoff scope = mock(ClientSodiumTerrain.Handoff.class);
        set(scope, "destination", level);
        set(scope, "state", state);
        Field handoff = ClientSodiumTerrain.class.getDeclaredField("handoff");
        handoff.setAccessible(true);
        Map<ClientLevel, ClientSodiumTerrain.State> states = states();
        states.put(level, state);
        handoff.set(null, scope);
        try (MockedStatic<Minecraft> clients = mockStatic(Minecraft.class);
             MockedStatic<SodiumWorldRenderer> renderers = mockStatic(SodiumWorldRenderer.class);
             MockedStatic<PortalShaderScope> shaders = mockStatic(PortalShaderScope.class)) {
            clients.when(Minecraft::getInstance).thenReturn(minecraft);
            renderers.when(SodiumWorldRenderer::instanceNullable).thenReturn(renderer);
            assertTrue(ClientSodiumTerrain.usesPreparedTerrain(level));
            assertFalse(ClientSodiumTerrain.usesPreparedTerrain(mock(ClientLevel.class)));
            assertFalse(ClientSodiumTerrain.retainLevelInvalidation(level));
            assertFalse(ClientSodiumTerrain.retainUnshadedHandoff());
            minecraft.level = level;
            assertFalse(ClientSodiumTerrain.usesPreparedTerrain(level));
            set(state, "viewport", mock(Viewport.class));
            assertTrue(ClientSodiumTerrain.usesPreparedTerrain(level));
            assertFalse(ClientSodiumTerrain.ready(level));
            assertTrue(ClientSodiumTerrain.retainUnshadedHandoff());
            shaders.when(PortalShaderScope::shaders).thenReturn(true);
            assertFalse(ClientSodiumTerrain.retainUnshadedHandoff());
            shaders.when(PortalShaderScope::shaders).thenReturn(false);
            set(scope, "installed", true);
            assertTrue(ClientSodiumTerrain.retainLevelInvalidation(level));
            assertFalse(ClientSodiumTerrain.retainLevelInvalidation(mock(ClientLevel.class)));
            renderers.when(SodiumWorldRenderer::instanceNullable).thenReturn(mock(SodiumWorldRenderer.class));
            assertFalse(ClientSodiumTerrain.retainLevelInvalidation(level));
            renderers.when(SodiumWorldRenderer::instanceNullable).thenReturn(renderer);
            when(options.getEffectiveRenderDistance()).thenReturn(12);
            assertFalse(ClientSodiumTerrain.usesPreparedTerrain(level));
            assertFalse(ClientSodiumTerrain.retainLevelInvalidation(level));
            assertFalse(ClientSodiumTerrain.retainUnshadedHandoff());
            when(options.getEffectiveRenderDistance()).thenReturn(10);
            states.clear();
            assertFalse(ClientSodiumTerrain.usesPreparedTerrain(level));
            assertFalse(ClientSodiumTerrain.retainLevelInvalidation(level));
            assertFalse(ClientSodiumTerrain.retainUnshadedHandoff());
            handoff.set(null, null);
            assertFalse(ClientSodiumTerrain.retainLevelInvalidation(level));
            assertFalse(ClientSodiumTerrain.retainUnshadedHandoff());
        } finally {
            handoff.set(null, null);
            states.clear();
        }
    }

    @Test
    public void authoritativeAttachReusesBorrowedDirtyTerrainWithoutAdvertisingPreparedReadiness() throws Exception {
        Minecraft minecraft = mock(Minecraft.class);
        Options options = mock(Options.class);
        Field optionsField = Minecraft.class.getDeclaredField("options");
        optionsField.setAccessible(true);
        optionsField.set(minecraft, options);
        when(options.getEffectiveRenderDistance()).thenReturn(10);
        ClientLevel active = mock(ClientLevel.class);
        ClientLevel destination = mock(ClientLevel.class);
        minecraft.level = active;
        SodiumWorldRenderer activeRenderer = mock(SodiumWorldRenderer.class);
        ClientSodiumTerrain.State retained = state(destination, mock(SodiumWorldRenderer.class));
        Map<ClientLevel, ClientSodiumTerrain.State> states = states();
        states.put(active, state(active, activeRenderer));
        states.put(destination, retained);
        Field selected = ClientSodiumTerrain.Handoff.class.getDeclaredField("state");
        selected.setAccessible(true);
        try (MockedStatic<Minecraft> clients = mockStatic(Minecraft.class);
             MockedStatic<SodiumWorldRenderer> renderers = mockStatic(SodiumWorldRenderer.class);
             MockedStatic<PortalShaderScope> shaders = mockStatic(PortalShaderScope.class)) {
            clients.when(Minecraft::getInstance).thenReturn(minecraft);
            renderers.when(SodiumWorldRenderer::instance).thenReturn(activeRenderer);
            try (ClientSodiumTerrain.Handoff prepared = ClientSodiumTerrain.handoff(destination)) {
                assertNull(selected.get(prepared));
            }
            try (ClientSodiumTerrain.Handoff authoritative = ClientSodiumTerrain.authoritativeHandoff(destination)) {
                assertSame(retained, selected.get(authoritative));
                assertFalse(ClientSodiumTerrain.ready(destination));
            }
            when(options.getEffectiveRenderDistance()).thenReturn(12);
            try (ClientSodiumTerrain.Handoff authoritative = ClientSodiumTerrain.authoritativeHandoff(destination)) {
                assertNull(selected.get(authoritative));
            }
            when(options.getEffectiveRenderDistance()).thenReturn(10);
            set(retained, "closed", true);
            try (ClientSodiumTerrain.Handoff authoritative = ClientSodiumTerrain.authoritativeHandoff(destination)) {
                assertNull(selected.get(authoritative));
            }
            states.remove(destination);
            try (ClientSodiumTerrain.Handoff authoritative = ClientSodiumTerrain.authoritativeHandoff(destination)) {
                assertNull(selected.get(authoritative));
            }
        } finally {
            states.clear();
        }
    }

    @Test
    public void farCompiledSectionWaitsForFreshNativeVisibilityWithoutBlocking() throws Exception {
        ClientLevel level = mock(ClientLevel.class);
        Viewport viewport = mock(Viewport.class);
        when(viewport.getChunkCoord()).thenReturn(SectionPos.of(0, 4, 0));
        when(viewport.getBlockCoord()).thenReturn(new BlockPos(0, 64, 0));
        SectionTree oldTree = new SectionTree(viewport, 160, 1, CullType.WIDE, level);
        SectionTree freshTree = new SectionTree(viewport, 160, 2, CullType.WIDE, level);
        RenderSection farSection = new RenderSection(new RenderRegion(8, 4, 0, mock(ArenaAggregator.class)), 8, 4, 0);
        BuiltSectionInfo.Builder geometry = new BuiltSectionInfo.Builder();
        geometry.addRenderPass(DefaultTerrainRenderPasses.SOLID);
        VisibilitySet occlusion = new VisibilitySet();
        occlusion.add(EnumSet.allOf(Direction.class));
        geometry.setOcclusionData(new VisibilitySet[]{occlusion});
        farSection.setInfo(geometry.build());
        assertTrue(farSection.isBuilt());
        freshTree.tree.add(farSection);
        freshTree.prepareForTraversal();
        assertFalse(oldTree.tree.isSectionPresent(8, 4, 0));
        assertTrue(freshTree.tree.isSectionPresent(8, 4, 0));
        SodiumPreparedSectionsMixin access = visibilityAccess();
        visibilityEvent(access, "wormholes$graphChanged", new Class<?>[]{CallbackInfo.class}, (Object) null);
        set(access, "needsGraphUpdate", true);
        visibilityEvent(access, "wormholes$scheduledVisibility",
            new Class<?>[]{Viewport.class, FogParameters.class, boolean.class, CallbackInfo.class},
            viewport, FogParameters.NONE, false, null);
        set(access, "needsGraphUpdate", false);
        assertFalse(access.wormholes$visibilityReady());
        cacheVisibility(access, CullType.WIDE, freshTree);
        set(access, "needsRenderListUpdate", true);
        assertFalse(access.wormholes$visibilityReady());
        set(access, "renderTree", freshTree);
        visibilityEvent(access, "wormholes$selectedVisibility",
            new Class<?>[]{Viewport.class, FogParameters.class, CallbackInfo.class}, viewport, FogParameters.NONE, null);
        set(access, "needsRenderListUpdate", false);
        assertTrue(access.wormholes$visibilityReady());
        assertTrue(freshTree.tree.isSectionPresent(8, 4, 0));
    }

    @Test
    public void cameraOnlyPendingCullPreservesReadinessButOldCompletionCannotBlessNewGeometry() throws Exception {
        SodiumPreparedSectionsMixin access = visibilityAccess();
        SectionTree oldTree = mock(SectionTree.class);
        SectionTree freshTree = mock(SectionTree.class);
        Viewport viewport = mock(Viewport.class);
        cacheVisibility(access, CullType.LOCAL, oldTree);
        set(access, "renderTree", oldTree);
        visibilityEvent(access, "wormholes$selectedVisibility",
            new Class<?>[]{Viewport.class, FogParameters.class, CallbackInfo.class}, viewport, FogParameters.NONE, null);
        CullTask cameraTask = mock(CullTask.class);
        RenderSectionManager nativeManager = mock(RenderSectionManager.class, CALLS_REAL_METHODS);
        setManagerField(nativeManager, "pendingTask", cameraTask);
        assertTrue(access.wormholes$visibilityReady());
        verify(cameraTask, never()).getResult();
        visibilityEvent(access, "wormholes$graphChanged", new Class<?>[]{CallbackInfo.class}, (Object) null);
        cacheVisibility(access, CullType.WIDE, freshTree);
        set(access, "renderTree", freshTree);
        visibilityEvent(access, "wormholes$selectedVisibility",
            new Class<?>[]{Viewport.class, FogParameters.class, CallbackInfo.class}, viewport, FogParameters.NONE, null);
        assertFalse(access.wormholes$visibilityReady());
        set(access, "wormholes$pendingRevision", 1L);
        cacheVisibility(access, CullType.WIDE, freshTree);
        set(access, "renderTree", oldTree);
        visibilityEvent(access, "wormholes$selectedVisibility",
            new Class<?>[]{Viewport.class, FogParameters.class, CallbackInfo.class}, viewport, FogParameters.NONE, null);
        assertFalse(access.wormholes$visibilityReady());
        set(access, "renderTree", freshTree);
        visibilityEvent(access, "wormholes$selectedVisibility",
            new Class<?>[]{Viewport.class, FogParameters.class, CallbackInfo.class}, viewport, FogParameters.NONE, null);
        assertTrue(access.wormholes$visibilityReady());
        set(access, "needsGraphUpdate", true);
        assertFalse(access.wormholes$visibilityReady());
        set(access, "needsGraphUpdate", false);
        set(access, "needsRenderListUpdate", true);
        assertFalse(access.wormholes$visibilityReady());
    }

    @Test
    public void visibilityObservationLeavesNativeRegionListsForOneRefreshPerFrame() throws Exception {
        RenderRegion region = new RenderRegion(0, 0, 0, mock(ArenaAggregator.class));
        BuiltSectionInfo.Builder builder = new BuiltSectionInfo.Builder();
        builder.addRenderPass(DefaultTerrainRenderPasses.SOLID);
        VisibilitySet occlusion = new VisibilitySet();
        occlusion.add(EnumSet.allOf(Direction.class));
        builder.setOcclusionData(new VisibilitySet[]{occlusion});
        BuiltSectionInfo geometry = builder.build();
        for (int x = 0; x < RenderRegion.REGION_WIDTH; x++) {
            for (int y = 0; y < RenderRegion.REGION_HEIGHT; y++) {
                for (int z = 0; z < RenderRegion.REGION_WIDTH; z++) {
                    RenderSection section = new RenderSection(region, x, y, z);
                    region.addSection(section);
                    section.setInfo(geometry);
                }
            }
        }
        RenderRegionManager regions = mock(RenderRegionManager.class);
        when(regions.getForChunk(anyInt(), anyInt(), anyInt())).thenReturn(region);
        VisibleChunkCollector first = new VisibleChunkCollector(regions, 1);
        visitRegion(first);
        assertEquals(RenderRegion.REGION_SIZE, region.getRenderList().size());
        assertEquals(RenderRegion.REGION_SIZE, region.getRenderList().getSectionsWithGeometryCount());
        SodiumPreparedSectionsMixin access = visibilityAccess();
        Viewport viewport = mock(Viewport.class);
        SectionTree tree = mock(SectionTree.class);
        cacheVisibility(access, CullType.WIDE, tree);
        set(access, "renderTree", tree);
        visibilityEvent(access, "wormholes$selectedVisibility",
            new Class<?>[]{Viewport.class, FogParameters.class, CallbackInfo.class}, viewport, FogParameters.NONE, null);
        for (int query = 0; query < 20; query++) {
            assertTrue(access.wormholes$visibilityReady());
        }
        assertEquals(RenderRegion.REGION_SIZE, region.getRenderList().size());
        VisibleChunkCollector duplicate = new VisibleChunkCollector(regions, 1);
        assertThrows(ArrayIndexOutOfBoundsException.class, () -> duplicate.visit(0, 0, 0));
        VisibleChunkCollector nextFrame = new VisibleChunkCollector(regions, 2);
        visitRegion(nextFrame);
        assertEquals(RenderRegion.REGION_SIZE, region.getRenderList().size());
        assertEquals(RenderRegion.REGION_SIZE, region.getRenderList().getSectionsWithGeometryCount());
        assertEquals(1, nextFrame.getUnsortedRenderLists().size());
        assertTrue(access.wormholes$visibilityReady());
    }

    private static void visitRegion(VisibleChunkCollector collector) {
        for (int x = 0; x < RenderRegion.REGION_WIDTH; x++) {
            for (int y = 0; y < RenderRegion.REGION_HEIGHT; y++) {
                for (int z = 0; z < RenderRegion.REGION_WIDTH; z++) {
                    collector.visit(x, y, z);
                }
            }
        }
    }

    private static SodiumPreparedSectionsMixin visibilityAccess() throws Exception {
        SodiumPreparedSectionsMixin access = mock(SodiumPreparedSectionsMixin.class, CALLS_REAL_METHODS);
        set(access, "wormholes$trees", new SectionTree[CullType.values().length]);
        set(access, "wormholes$treeRevisions", new long[CullType.values().length]);
        set(access, "wormholes$listRevision", -1L);
        return access;
    }

    private static void visibilityEvent(SodiumPreparedSectionsMixin access, String name, Class<?>[] types,
                                        Object... arguments) throws Exception {
        Method method = SodiumPreparedSectionsMixin.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        method.invoke(access, arguments);
    }

    private static void cacheVisibility(SodiumPreparedSectionsMixin access, CullType type, SectionTree tree) throws Exception {
        Map<CullType, SectionTree> cache = new EnumMap<>(CullType.class);
        Operation<Object> original = arguments -> cache.put((CullType) arguments[1], (SectionTree) arguments[2]);
        visibilityEvent(access, "wormholes$cacheVisibility", new Class<?>[]{Map.class, Object.class, Object.class, Operation.class},
            cache, type, tree, original);
    }

    private static ClientSodiumTerrain.State state(ClientLevel level, SodiumWorldRenderer renderer) {
        return new ClientSodiumTerrain.State(new ClientSodiumTerrain.Ownership(level, renderer, null, 10));
    }

    private static DeferredTaskList pendingTasks(ClientLevel level, RenderSection section) {
        Viewport viewport = mock(Viewport.class);
        when(viewport.getChunkCoord()).thenReturn(section.getPosition());
        when(viewport.getBlockCoord()).thenReturn(new BlockPos(section.getCenterX(), section.getCenterY(), section.getCenterZ()));
        TaskCollectingTree tree = new TaskCollectingTree(viewport, 160, 1, CullType.WIDE, level);
        tree.visit(section, true);
        return tree.getPendingTaskLists();
    }

    @SuppressWarnings("unchecked")
    private static Operation<RenderSection> operation() {
        return mock(Operation.class);
    }

    private static void setManagerField(RenderSectionManager manager, String name, Object value) throws ReflectiveOperationException {
        Field field = RenderSectionManager.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(manager, value);
    }

    @SuppressWarnings("unchecked")
    private static Map<ClientLevel, ClientSodiumTerrain.State> states() throws Exception {
        Field field = ClientSodiumTerrain.class.getDeclaredField("STATES");
        field.setAccessible(true);
        return (Map<ClientLevel, ClientSodiumTerrain.State>) field.get(null);
    }

    private static boolean stateReady(ClientSodiumTerrain.State state) throws Exception {
        Field field = ClientSodiumTerrain.State.class.getDeclaredField("ready");
        field.setAccessible(true);
        return field.getBoolean(state);
    }

    private static boolean uniformFramePending(ClientSodiumTerrain.State state) throws Exception {
        Field field = ClientSodiumTerrain.State.class.getDeclaredField("uniformFramePending");
        field.setAccessible(true);
        return field.getBoolean(state);
    }

    private static void set(Object value, String name, Object fieldValue) throws Exception {
        Class<?> owner = value instanceof SodiumPreparedSectionStateMixin ? SodiumPreparedSectionStateMixin.class
            : value instanceof SodiumPreparedExtractorMixin ? SodiumPreparedExtractorMixin.class
            : value instanceof SodiumPreparedSectionsMixin ? SodiumPreparedSectionsMixin.class : value.getClass();
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(value, fieldValue);
    }
}
