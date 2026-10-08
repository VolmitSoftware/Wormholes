package art.arcane.wormholes.modded.client.render;

import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.frame.Similarity;
import art.arcane.optics.frame.AxisPermutation;
import art.arcane.optics.stream.EnvironmentState;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import art.arcane.optics.aperture.ApertureCells;
import art.arcane.optics.claim.BlockClaim;
import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;
import art.arcane.optics.shape.ShapeDescriptor;
import art.arcane.wormholes.portal.ApertureKind;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuSampler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.DynamicGpuData;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.SectionPos;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4d;
import org.joml.Quaternionf;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.MockedConstruction;

import java.nio.ByteBuffer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import java.util.EnumMap;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;

public class ClientPortalRendererTest extends MinecraftTestBase {
    @Test
    @SuppressWarnings("unchecked")
    public void sectionOrderingPreservesDistanceTiesAndUpdatesAfterMovement() throws ReflectiveOperationException {
        ClientPortalRenderer renderer = ClientPortalRenderer.instance();
        renderer.clear();
        renderer.replaceScene(1, scene());
        Object portal = ((Map<?, ?>) get(renderer, "portals")).get(1);
        Long2ObjectOpenHashMap<Object> sections = (Long2ObjectOpenHashMap<Object>) get(portal, "sections");
        Object negativeFar = orderingSection(SectionPos.asLong(-2, 0, 0));
        Object negativeNear = orderingSection(SectionPos.asLong(-1, 0, 0));
        Object positiveNear = orderingSection(SectionPos.asLong(0, 0, 0));
        Object positiveFar = orderingSection(SectionPos.asLong(1, 0, 0));
        for (Object section : List.of(negativeFar, negativeNear, positiveNear, positiveFar)) {
            sections.put((long) get(section, "key"), section);
        }
        List<Object> expected = new ArrayList<>();
        for (Object section : sections.values()) {
            if (section == negativeFar || section == positiveFar) {
                expected.add(section);
            }
        }
        for (Object section : sections.values()) {
            if (section == negativeNear || section == positiveNear) {
                expected.add(section);
            }
        }
        CameraRenderState camera = new CameraRenderState();
        camera.pos = new Vec3(0, 8, 8);
        set(renderer, "camera", camera);
        Method ordered = ClientPortalRenderer.class.getDeclaredMethod("orderedSections", portal.getClass());
        ordered.setAccessible(true);
        try {
            List<Object> sorted = (List<Object>) ordered.invoke(renderer, portal);
            assertEquals(expected, sorted);
            assertEquals(576.0, (double) get(negativeFar, "sortDistance"), 0.0);
            assertEquals(64.0, (double) get(negativeNear, "sortDistance"), 0.0);
            camera.pos = new Vec3(40, 8, 8);
            assertSame(sorted, ordered.invoke(renderer, portal));
            assertEquals(List.of(negativeFar, negativeNear, positiveNear, positiveFar), sorted);
            assertEquals(4096.0, (double) get(negativeFar, "sortDistance"), 0.0);
            assertEquals(256.0, (double) get(positiveFar, "sortDistance"), 0.0);
        } finally {
            renderer.clear();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void sectionOrderingReusesUnchangedListAndRefreshesInvalidatedMembership() throws ReflectiveOperationException {
        ClientPortalRenderer renderer = ClientPortalRenderer.instance();
        renderer.clear();
        renderer.replaceScene(1, scene());
        Object portal = ((Map<?, ?>) get(renderer, "portals")).get(1);
        Long2ObjectOpenHashMap<Object> sections = (Long2ObjectOpenHashMap<Object>) get(portal, "sections");
        long nearKey = SectionPos.asLong(-1874999, -128, 1874999);
        long farKey = SectionPos.asLong(-1874998, -128, 1874999);
        Object near = orderingSection(nearKey);
        Object far = orderingSection(farKey);
        sections.put(nearKey, near);
        sections.put(farKey, far);
        CameraRenderState camera = new CameraRenderState();
        camera.pos = new Vec3(-29999976, -2040, 29999992);
        set(renderer, "camera", camera);
        Method ordered = ClientPortalRenderer.class.getDeclaredMethod("orderedSections", portal.getClass());
        ordered.setAccessible(true);
        try {
            List<Object> sorted = (List<Object>) ordered.invoke(renderer, portal);
            assertEquals(List.of(far, near), sorted);
            assertEquals(256.0, (double) get(far, "sortDistance"), 0.0);
            assertEquals(0.0, (double) get(near, "sortDistance"), 0.0);
            sections.remove(farKey);
            assertSame(sorted, ordered.invoke(renderer, portal));
            assertEquals(List.of(far, near), sorted);
            set(portal, "orderDirty", true);
            assertSame(sorted, ordered.invoke(renderer, portal));
            assertEquals(List.of(near), sorted);
            sections.put(farKey, far);
            ((EnumMap<ChunkSectionLayer, PortalGpuMesh>) get(near, "layers")).clear();
            set(portal, "orderDirty", true);
            ordered.invoke(renderer, portal);
            assertEquals(List.of(far), sorted);
        } finally {
            renderer.clear();
        }
    }

    private static Object orderingSection(long key) throws ReflectiveOperationException {
        Class<?> type = Class.forName(ClientPortalRenderer.class.getName() + "$Section");
        Constructor<?> constructor = type.getDeclaredConstructor(long.class, long.class);
        constructor.setAccessible(true);
        Object section = constructor.newInstance(key, 1L);
        @SuppressWarnings("unchecked")
        EnumMap<ChunkSectionLayer, PortalGpuMesh> layers = (EnumMap<ChunkSectionLayer, PortalGpuMesh>) get(section, "layers");
        layers.put(ChunkSectionLayer.SOLID, mock(PortalGpuMesh.class));
        return section;
    }

    @Test
    @SuppressWarnings("unchecked")
    public void shadowlessPortalKeepsPreparedNormalListWithoutRecheckingItsFrustum() throws ReflectiveOperationException {
        ClientPortalRenderer renderer = ClientPortalRenderer.instance();
        renderer.clear();
        renderer.replaceScene(1, scene());
        Object portal = ((Map<?, ?>) get(renderer, "portals")).get(1);
        PortalShaderRenderer.Session shaders = mock(PortalShaderRenderer.Session.class);
        set(portal, "shader", shaders);
        Frustum frustum = mock(Frustum.class);
        set(portal, "cullFrustum", frustum);
        CameraRenderState camera = new CameraRenderState();
        set(portal, "camera", camera);
        set(portal, "contentCamera", camera);
        set(renderer, "camera", camera);
        Object selected = new Object();
        List<Object> normal = (List<Object>) get(portal, "drawSections");
        normal.add(selected);
        PortalFeatureRenderer features = mock(PortalFeatureRenderer.class);
        Method shadows = ClientPortalRenderer.class.getDeclaredMethod("renderDestinationShadows", portal.getClass(), PortalFeatureRenderer.class);
        shadows.setAccessible(true);
        try {
            shadows.invoke(renderer, portal, features);
            assertSame(normal, get(portal, "drawSections"));
            assertEquals(List.of(selected), normal);
            assertSame(camera, get(renderer, "camera"));
            verifyNoInteractions(frustum);
            verify(features).closeFrame();
        } finally {
            normal.clear();
            renderer.clear();
        }
    }

    @Test
    public void actualShadowPassRestoresIndependentlySelectedNormalTerrain() throws ReflectiveOperationException {
        verifyShadowRestoration(false, false);
    }

    @Test
    public void failedShadowPassRestoresNormalTerrainAndClosesItsFrame() throws ReflectiveOperationException {
        verifyShadowRestoration(true, false);
    }

    @Test
    public void failedShadowFeatureCleanupClearsBorrowedTransformsAndRestoresNormalTerrain() throws ReflectiveOperationException {
        verifyShadowRestoration(false, true);
    }

    @SuppressWarnings("unchecked")
    private static void verifyShadowRestoration(boolean fail, boolean failCleanup) throws ReflectiveOperationException {
        ClientPortalRenderer renderer = ClientPortalRenderer.instance();
        renderer.clear();
        renderer.replaceScene(1, scene());
        Object portal = ((Map<?, ?>) get(renderer, "portals")).get(1);
        Class<?> sectionType = Class.forName(ClientPortalRenderer.class.getName() + "$Section");
        Constructor<?> constructor = sectionType.getDeclaredConstructor(long.class, long.class);
        constructor.setAccessible(true);
        long near = SectionPos.asLong(0, 0, 0);
        long far = SectionPos.asLong(2, 0, 0);
        Object mainSection = constructor.newInstance(near, 1L);
        Object shadowSection = constructor.newInstance(far, 1L);
        for (Object section : List.of(mainSection, shadowSection)) {
            for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
                ((EnumMap<ChunkSectionLayer, PortalGpuMesh>) get(section, "layers")).put(layer, mock(PortalGpuMesh.class));
            }
        }
        Long2ObjectOpenHashMap<Object> sections = (Long2ObjectOpenHashMap<Object>) get(portal, "sections");
        sections.put(near, mainSection);
        sections.put(far, shadowSection);
        List<Object> drawing = (List<Object>) get(portal, "drawSections");
        drawing.add(mainSection);
        CameraRenderState main = new CameraRenderState();
        main.pos = Vec3.ZERO;
        CameraRenderState shadow = new CameraRenderState();
        shadow.pos = new Vec3(10, 20, 30);
        Frustum mainFrustum = mock(Frustum.class);
        when(mainFrustum.isVisible(any(AABB.class))).thenAnswer(call -> ((AABB) call.getArgument(0)).minX < 16);
        shadow.cullFrustum = mock(Frustum.class);
        when(shadow.cullFrustum.isVisible(any(AABB.class))).thenAnswer(call -> ((AABB) call.getArgument(0)).minX > 16);
        set(portal, "cullFrustum", mainFrustum);
        set(portal, "camera", main);
        set(portal, "contentCamera", main);
        set(renderer, "camera", main);
        set(portal, "viewport", new PortalViewport(0, 0, 1, 1));
        set(portal, "target", mock(TextureTarget.class));
        PortalShaderRenderer.Session shader = mock(PortalShaderRenderer.Session.class);
        PortalShaderRenderer.ShadowFrame shadowFrame = mock(PortalShaderRenderer.ShadowFrame.class);
        PortalShaderRenderer.Frame phase = mock(PortalShaderRenderer.Frame.class);
        when(shader.shadows(main)).thenReturn(shadowFrame);
        when(shadowFrame.camera()).thenReturn(shadow);
        when(shadowFrame.features()).thenReturn(phase);
        when(shadowFrame.terrain()).thenReturn(true);
        when(shadowFrame.translucent()).thenReturn(true);
        set(portal, "shader", shader);
        set(portal, "environment", mock(PortalEnvironmentRenderer.class));
        PortalFeatureRenderer features = mock(PortalFeatureRenderer.class);
        doAnswer(call -> {
            assertEquals(List.of(shadowSection), drawing);
            if (fail) {
                throw new IllegalStateException("shadow extraction failed");
            }
            return null;
        }).when(features).prepare(any(PortalScene.class), eq(shadow), anyBoolean(), anyBoolean());
        doAnswer(call -> {
            assertSame(null, get(shadowSection, "transform"));
            if (failCleanup) {
                throw new IllegalStateException("Shadow cleanup failed");
            }
            return null;
        }).when(features).closeFrame();
        Method draw = ClientPortalRenderer.class.getDeclaredMethod("renderDestinationShadows", portal.getClass(), PortalFeatureRenderer.class);
        draw.setAccessible(true);
        GpuDevice device = mock(GpuDevice.class, RETURNS_DEEP_STUBS);
        DynamicGpuData uniforms = mock(DynamicGpuData.class);
        GpuBufferSlice shadowSlice = mock(GpuBufferSlice.class);
        GpuBufferSlice mainSlice = mock(GpuBufferSlice.class);
        when(uniforms.writeTransform(any(DynamicGpuData.Transform.class))).thenReturn(shadowSlice, mainSlice);
        Minecraft minecraft = mock(Minecraft.class, RETURNS_DEEP_STUBS);
        try (MockedStatic<RenderSystem> systems = mockStatic(RenderSystem.class, RETURNS_DEEP_STUBS);
             MockedStatic<Minecraft> game = mockStatic(Minecraft.class);
             MockedStatic<PortalIrisTerrain> terrain = mockStatic(PortalIrisTerrain.class)) {
            systems.when(RenderSystem::getDevice).thenReturn(device);
            systems.when(RenderSystem::getDynamicUniforms).thenReturn(uniforms);
            game.when(Minecraft::getInstance).thenReturn(minecraft);
            if (fail || failCleanup) {
                InvocationTargetException failure = assertThrows(InvocationTargetException.class, () -> draw.invoke(renderer, portal, features));
                assertTrue(failure.getCause() instanceof IllegalStateException);
            } else {
                draw.invoke(renderer, portal, features);
            }
            assertSame(main, get(renderer, "camera"));
            assertSame(drawing, get(portal, "drawSections"));
            assertEquals(List.of(mainSection), drawing);
            assertSame(null, get(shadowSection, "transform"));
            assertSame(null, get(mainSection, "transform"));
            verify(shadowFrame).close();
            verify(features).closeFrame();
            if (fail) {
                verifyNoInteractions(uniforms);
            } else {
                verify(uniforms).writeTransform(any(DynamicGpuData.Transform.class));
                Method normal = ClientPortalRenderer.class.getDeclaredMethod("drawTerrain", portal.getClass(), ChunkSectionLayer.class, RenderPass.class);
                normal.setAccessible(true);
                RenderPass pass = mock(RenderPass.class);
                for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
                    normal.invoke(renderer, portal, layer, pass);
                }
                verify(uniforms, times(2)).writeTransform(any(DynamicGpuData.Transform.class));
                verify(pass, times(3)).setUniform("DynamicTransforms", mainSlice);
                assertSame(mainSlice, get(mainSection, "transform"));
                verifyNoInteractions(shadowSlice, mainSlice);
            }
        } finally {
            renderer.clear();
        }
    }

    @Test
    public void returnShadersWarmWithoutSourceSnapshotsAndReleaseForDimensionReuse() throws ReflectiveOperationException {
        ClientPortalRenderer renderer = ClientPortalRenderer.instance();
        renderer.clear();
        EnvironmentState environment = PortalEnvironmentTest.environment(OpticTransform.IDENTITY);
        PortalShaderRenderer shaders = mock(PortalShaderRenderer.class);
        PortalShaderRenderer.Session session = mock(PortalShaderRenderer.Session.class);
        TextureTarget target = mock(TextureTarget.class);
        when(session.target()).thenReturn(target);
        when(shaders.acquire(-3, environment, 1920, 1080)).thenReturn(session);
        set(renderer, "shaderRenderer", shaders);
        CameraRenderState camera = new CameraRenderState();
        camera.pos = Vec3.ZERO;
        set(renderer, "rootCamera", camera);
        CameraRenderState previous = new CameraRenderState();
        set(renderer, "camera", previous);
        Class<?> dimensions = Class.forName(ClientPortalRenderer.class.getName() + "$RenderDimensions");
        Constructor<?> constructor = dimensions.getDeclaredConstructor(int.class, int.class, int.class);
        constructor.setAccessible(true);
        Method warm = ClientPortalRenderer.class.getDeclaredMethod("warmTravelSource", dimensions);
        warm.setAccessible(true);
        Minecraft minecraft = mock(Minecraft.class);
        try (MockedStatic<Minecraft> clients = mockStatic(Minecraft.class);
             MockedStatic<PortalShaderScope> bindings = mockStatic(PortalShaderScope.class)) {
            clients.when(Minecraft::getInstance).thenReturn(minecraft);
            bindings.when(PortalShaderScope::shaders).thenReturn(true);
            renderer.prepareTravelSourceEnvironment(environment);
            assertFalse(renderer.travelSourceShaderReady());
            assertEquals(null, get(renderer, "travelSource"));
            warm.invoke(renderer, constructor.newInstance(1920, 1080, 0));
            verify(session).warm(argThat(view -> view.environment() == environment));
            assertSame(previous, get(renderer, "camera"));
            when(session.ready()).thenReturn(true);
            assertTrue(renderer.travelSourceShaderReady());
            warm.invoke(renderer, constructor.newInstance(1920, 1080, 0));
            verify(session, times(1)).warm(any());
            clearInvocations(shaders);
            renderer.retireTravelSource();
            verify(shaders).remove(-3);
            assertFalse(renderer.travelSourceShaderReady());
            assertEquals(null, get(renderer, "travelSourceEnvironment"));
            bindings.when(PortalShaderScope::shaders).thenReturn(false);
            assertTrue(renderer.travelSourceShaderReady());
        } finally {
            renderer.clear();
        }
    }

    @Test
    public void offscreenRootDoesNotAllocateOrClearTheCompositeLayer() throws ReflectiveOperationException {
        ClientPortalRenderer renderer = ClientPortalRenderer.instance();
        renderer.clear();
        PortalScene scene = scene();
        when(scene.environment()).thenReturn(PortalEnvironmentTest.environment(OpticTransform.IDENTITY));
        renderer.replaceScene(1, scene);
        CameraRenderState camera = new CameraRenderState();
        camera.pos = new Vec3(1, 1, 3);
        camera.cullFrustum = mock(Frustum.class);
        set(renderer, "rootCamera", camera);
        set(renderer, "camera", camera);
        Minecraft minecraft = mock(Minecraft.class, RETURNS_DEEP_STUBS);
        Options options = mock(Options.class, RETURNS_DEEP_STUBS);
        when(options.ambientOcclusion().get()).thenReturn(false);
        set(minecraft, "options", options);
        set(minecraft, "gameRenderer", mock(GameRenderer.class));
        TextureTarget main = mock(TextureTarget.class);
        main.width = 1920;
        main.height = 1080;
        when(minecraft.gameRenderer.mainRenderTarget()).thenReturn(main);
        set(renderer, "models", minecraft.getModelManager().getBlockStateModelSet());
        set(renderer, "ambientOcclusion", false);
        set(renderer, "pipelines", mock(PortalPipelines.class));
        set(renderer, "terrainSampler", mock(GpuSampler.class));
        set(renderer, "anisotropy", 1);
        set(renderer, "layerMesh", mock(PortalGpuMesh.class));
        set(renderer, "portalLayer", mock(TextureTarget.class));
        GpuDevice device = mock(GpuDevice.class);
        Method prepare = ClientPortalRenderer.class.getDeclaredMethod("prepareFrame", GpuBufferSlice.class);
        prepare.setAccessible(true);
        try (MockedStatic<RenderSystem> system = mockStatic(RenderSystem.class);
             MockedStatic<Minecraft> game = mockStatic(Minecraft.class);
             MockedStatic<PortalShaderScope> shaders = mockStatic(PortalShaderScope.class);
             MockedConstruction<TextureTarget> attachments = mockConstruction(TextureTarget.class)) {
            system.when(RenderSystem::getDevice).thenReturn(device);
            game.when(Minecraft::getInstance).thenReturn(minecraft);
            prepare.invoke(renderer, new Object[]{null});
            assertSame(null, get(renderer, "portalLayer"));
            assertTrue(attachments.constructed().isEmpty());
            verifyNoInteractions(device);
            verify(camera.cullFrustum).isVisible(any(AABB.class));
            assertTrue(((List<?>) get(renderer, "visible")).isEmpty());
        } finally {
            renderer.clear();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void shaderCompositingSkipsEmptyFramesButPreservesTravelAndArrivalCovers() throws ReflectiveOperationException {
        ClientPortalRenderer renderer = ClientPortalRenderer.instance();
        renderer.clear();
        PortalGpuMesh mesh = mock(PortalGpuMesh.class);
        set(renderer, "layerMesh", mesh);
        set(renderer, "pipelines", mock(PortalPipelines.class));
        set(renderer, "travelMainReady", true);
        GpuDevice device = mock(GpuDevice.class, RETURNS_DEEP_STUBS);
        Minecraft minecraft = mock(Minecraft.class, RETURNS_DEEP_STUBS);
        Options options = mock(Options.class, RETURNS_DEEP_STUBS);
        when(options.chunkSectionFadeInTime().get()).thenReturn(0.0);
        set(minecraft, "options", options);
        set(minecraft, "gameRenderer", mock(GameRenderer.class));
        TextureTarget main = mock(TextureTarget.class, RETURNS_DEEP_STUBS);
        when(minecraft.gameRenderer.mainRenderTarget()).thenReturn(main);
        try (MockedStatic<RenderSystem> system = mockStatic(RenderSystem.class, RETURNS_DEEP_STUBS);
             MockedStatic<Minecraft> game = mockStatic(Minecraft.class);
             MockedStatic<PortalShaderScope> shaders = mockStatic(PortalShaderScope.class);
             MockedStatic<PortalFramebufferScope> framebuffer = mockStatic(PortalFramebufferScope.class)) {
            system.when(RenderSystem::getDevice).thenReturn(device);
            game.when(Minecraft::getInstance).thenReturn(minecraft);
            shaders.when(PortalShaderScope::shaders).thenReturn(true);
            renderer.compositeAfterShaders();
            assertEquals(false, get(renderer, "travelMainReady"));
            verifyNoInteractions(device);
            game.verifyNoInteractions();
            framebuffer.verifyNoInteractions();
            renderer.prepareTravel(scene(), new CameraRenderState());
            Object travel = get(renderer, "travel");
            set(travel, "rendered", true);
            set(travel, "target", mock(TextureTarget.class, RETURNS_DEEP_STUBS));
            set(travel, "cullFrustum", mock(Frustum.class));
            renderer.compositeAfterShaders();
            verifyNoInteractions(device);
            renderer.transitionTravel(true);
            renderer.compositeAfterShaders();
            verify(device).createCommandEncoder();
            verify(mesh).draw(any(RenderPass.class));
            assertEquals(true, get(renderer, "travelMainReady"));
            renderer.retainArrival();
            renderer.compositeAfterShaders();
            verify(device, times(2)).createCommandEncoder();
            verify(mesh, times(2)).draw(any(RenderPass.class));
            renderer.retireArrival();
            renderer.compositeAfterShaders();
            assertEquals(false, get(renderer, "travelMainReady"));
            verify(device, times(2)).createCommandEncoder();
            renderer.replaceScene(1, scene());
            ((List<Object>) get(renderer, "visible")).add(((Map<?, ?>) get(renderer, "portals")).get(1));
            set(renderer, "portalLayer", mock(TextureTarget.class, RETURNS_DEEP_STUBS));
            renderer.compositeAfterShaders();
            verify(device, times(3)).createCommandEncoder();
            verify(mesh, times(3)).draw(any(RenderPass.class));
        } finally {
            renderer.clear();
        }
    }

    @Test
    public void nextPreparationHasIndependentLifetimeWhileArrivalCoverIsStillNeeded() throws ReflectiveOperationException {
        ClientPortalRenderer renderer = ClientPortalRenderer.instance();
        renderer.clear();
        renderer.prepareTravel(scene(), new CameraRenderState());
        Object first = get(renderer, "travel");
        renderer.transitionTravel(true);
        renderer.retainArrival();
        renderer.prepareTravel(scene(), new CameraRenderState());
        Object second = get(renderer, "travel");
        assertSame(first, get(renderer, "arrival"));
        assertFalse(first == second);
        assertEquals(-1, get(first, "key"));
        assertEquals(-2, get(second, "key"));
        renderer.cancelTravel();
        assertSame(first, get(renderer, "arrival"));
        renderer.prepareTravel(scene(), new CameraRenderState());
        renderer.transitionTravel(true);
        assertEquals(null, get(renderer, "arrival"));
        renderer.clear();
    }

    @Test
    @SuppressWarnings("unchecked")
    public void changedArrivalSectionKeepsDrawingItsMeshUntilTheRebuildLands() throws ReflectiveOperationException {
        ClientPortalRenderer renderer = ClientPortalRenderer.instance();
        renderer.clear();
        long key = SectionPos.asLong(0, 5, 0);
        PortalScene scene = scene();
        when(scene.sectionKeys()).thenReturn(LongArrayList.of(key));
        when(scene.revision(key)).thenReturn(1L);
        renderer.prepareTravel(scene, new CameraRenderState());
        Object portal = get(renderer, "travel");
        PortalShaderRenderer.Session session = mock(PortalShaderRenderer.Session.class);
        TextureTarget target = mock(TextureTarget.class, RETURNS_DEEP_STUBS);
        when(session.target()).thenReturn(target);
        set(portal, "shader", session);
        Object section = sectionConstructor().newInstance(key, 1L);
        PortalGpuMesh mesh = mock(PortalGpuMesh.class);
        when(mesh.bytes()).thenReturn(64L);
        ((EnumMap<ChunkSectionLayer, PortalGpuMesh>) get(section, "layers")).put(ChunkSectionLayer.SOLID, mesh);
        Long2ObjectOpenHashMap<Object> sections = (Long2ObjectOpenHashMap<Object>) get(portal, "sections");
        sections.put(key, section);
        set(renderer, "gpuBytes", 64L);
        renderer.retainArrival();
        try {
            assertTrue(renderer.arrivalDrawable());
            renderer.invalidateArrival(key);
            assertSame(section, sections.get(key));
            assertTrue(((LongSet) get(portal, "dirty")).contains(key));
            assertEquals(64L, get(renderer, "gpuBytes"));
            verify(mesh, never()).close();
            assertSame(portal, get(renderer, "arrival"));
            assertTrue(renderer.arrivalDrawable());
            when(scene.revision(key)).thenReturn(2L);
            set(renderer, "pendingBuilds", 1);
            finish(renderer, portal, key, 2L);
            verify(mesh).close();
            assertEquals(2L, get(sections.get(key), "revision"));
        } finally {
            renderer.clear();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void invalidatedTravelSectionKeepsDrawingItsMeshUntilTheRebuildLands() throws ReflectiveOperationException {
        ClientPortalRenderer renderer = ClientPortalRenderer.instance();
        renderer.clear();
        long key = SectionPos.asLong(0, 5, 0);
        PortalScene scene = scene();
        when(scene.sectionKeys()).thenReturn(LongArrayList.of(key));
        when(scene.revision(key)).thenReturn(1L);
        renderer.prepareTravel(scene, new CameraRenderState());
        Object portal = get(renderer, "travel");
        Object section = sectionConstructor().newInstance(key, 1L);
        PortalGpuMesh mesh = mock(PortalGpuMesh.class);
        ((EnumMap<ChunkSectionLayer, PortalGpuMesh>) get(section, "layers")).put(ChunkSectionLayer.SOLID, mesh);
        Long2ObjectOpenHashMap<Object> sections = (Long2ObjectOpenHashMap<Object>) get(portal, "sections");
        sections.put(key, section);
        try {
            renderer.invalidateTravel(key);
            assertSame(section, sections.get(key));
            assertTrue(((LongSet) get(portal, "dirty")).contains(key));
            verify(mesh, never()).close();
            when(scene.revision(key)).thenReturn(2L);
            set(renderer, "pendingBuilds", 1);
            finish(renderer, portal, key, 2L);
            verify(mesh).close();
            assertEquals(2L, get(sections.get(key), "revision"));
        } finally {
            renderer.clear();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void refreshingSnapshotKeepsItsMeshQueuedAndRebuildsItOnceTheSnapshotLands() throws ReflectiveOperationException {
        ClientPortalRenderer renderer = ClientPortalRenderer.instance();
        renderer.clear();
        long refreshing = SectionPos.asLong(0, 5, 0);
        long retired = SectionPos.asLong(1, 5, 0);
        PortalScene scene = scene();
        when(scene.sectionKeys()).thenReturn(LongArrayList.of(refreshing, retired));
        when(scene.revision(anyLong())).thenReturn(1L);
        renderer.prepareTravel(scene, new CameraRenderState());
        Object portal = get(renderer, "travel");
        Long2ObjectOpenHashMap<Object> sections = (Long2ObjectOpenHashMap<Object>) get(portal, "sections");
        PortalGpuMesh kept = mock(PortalGpuMesh.class);
        PortalGpuMesh dropped = mock(PortalGpuMesh.class);
        Object refreshingSection = sectionConstructor().newInstance(refreshing, 1L);
        Object retiredSection = sectionConstructor().newInstance(retired, 1L);
        ((EnumMap<ChunkSectionLayer, PortalGpuMesh>) get(refreshingSection, "layers")).put(ChunkSectionLayer.SOLID, kept);
        ((EnumMap<ChunkSectionLayer, PortalGpuMesh>) get(retiredSection, "layers")).put(ChunkSectionLayer.SOLID, dropped);
        sections.put(refreshing, refreshingSection);
        sections.put(retired, retiredSection);
        CameraRenderState camera = new CameraRenderState();
        camera.pos = Vec3.ZERO;
        set(renderer, "camera", camera);
        Method maintain = ClientPortalRenderer.class.getDeclaredMethod("maintain", portal.getClass());
        maintain.setAccessible(true);
        LongSet dirty = (LongSet) get(portal, "dirty");
        try {
            when(scene.revision(anyLong())).thenReturn(-1L);
            when(scene.refreshing(refreshing)).thenReturn(true);
            renderer.invalidateTravel(refreshing);
            renderer.invalidateTravel(retired);
            maintain.invoke(renderer, portal);
            assertSame(refreshingSection, sections.get(refreshing));
            verify(kept, never()).close();
            assertTrue(dirty.contains(refreshing));
            assertFalse(sections.containsKey(retired));
            verify(dropped).close();
            assertFalse((boolean) get(portal, "hasResidentBuild"));
            assertFalse((boolean) get(portal, "hasInitialBuild"));
            when(scene.revision(refreshing)).thenReturn(2L);
            when(scene.refreshing(refreshing)).thenReturn(false);
            maintain.invoke(renderer, portal);
            assertTrue((boolean) get(portal, "hasResidentBuild"));
            assertEquals(refreshing, get(portal, "residentSection"));
            assertSame(refreshingSection, sections.get(refreshing));
        } finally {
            renderer.clear();
        }
    }

    @Test
    public void arrivalRetiresOnlyAfterEveryVisibleNonemptySectionIsCompiled() throws ReflectiveOperationException {
        ClientPortalRenderer renderer = ClientPortalRenderer.instance();
        renderer.clear();
        PortalScene scene = scene();
        long near = SectionPos.asLong(0, 0, 0);
        long neighbor = SectionPos.asLong(1, 0, 0);
        long empty = SectionPos.asLong(2, 0, 0);
        long hidden = SectionPos.asLong(3, 0, 0);
        when(scene.sectionKeys()).thenReturn(LongArrayList.of(near, neighbor, empty, hidden));
        when(scene.empty(empty)).thenReturn(true);
        renderer.prepareTravel(scene, new CameraRenderState());
        Object portal = get(renderer, "travel");
        Frustum frustum = mock(Frustum.class);
        when(frustum.isVisible(any(AABB.class))).thenAnswer(call -> ((AABB) call.getArgument(0)).minX < 47);
        set(portal, "cullFrustum", frustum);
        set(portal, "rendered", true);
        renderer.transitionTravel(true);
        Minecraft minecraft = mock(Minecraft.class);
        LevelRenderer main = mock(LevelRenderer.class);
        Options options = mock(Options.class, RETURNS_DEEP_STUBS);
        when(options.chunkSectionFadeInTime().get()).thenReturn(0.25);
        set(minecraft, "options", options);
        set(minecraft, "levelRenderer", main);
        ObjectArrayList<SectionRenderDispatcher.RenderSection> drawn = new ObjectArrayList<>();
        for (long key : new long[] {near, neighbor, empty, hidden}) {
            SectionRenderDispatcher.RenderSection section = mock(SectionRenderDispatcher.RenderSection.class);
            when(section.getSectionNode()).thenReturn(key);
            drawn.add(section);
        }
        when(main.visibleSections()).thenReturn(drawn);
        when(main.isSectionCompiledAndVisible(new BlockPos(8, 8, 8), 0)).thenReturn(true);
        when(main.isSectionCompiledAndVisible(new BlockPos(8, 8, 8), 250)).thenReturn(true);
        Method ready = ClientPortalRenderer.class.getDeclaredMethod("mainTravelCoverageReady");
        ready.setAccessible(true);
        try (MockedStatic<Minecraft> access = mockStatic(Minecraft.class)) {
            access.when(Minecraft::getInstance).thenReturn(minecraft);
            assertFalse((boolean) ready.invoke(renderer));
            when(main.isSectionCompiledAndVisible(new BlockPos(24, 8, 8), 0)).thenReturn(true);
            assertFalse((boolean) ready.invoke(renderer));
            when(main.isSectionCompiledAndVisible(new BlockPos(24, 8, 8), 250)).thenReturn(true);
            assertTrue((boolean) ready.invoke(renderer));
            verify(main, never()).isSectionCompiledAndVisible(new BlockPos(40, 8, 8), 250);
            verify(main, never()).isSectionCompiledAndVisible(new BlockPos(56, 8, 8), 250);
        } finally {
            renderer.clear();
        }
    }

    @Test
    public void arrivalIgnoresSectionsTheMainRendererDoesNotDraw() throws ReflectiveOperationException {
        ClientPortalRenderer renderer = ClientPortalRenderer.instance();
        renderer.clear();
        PortalScene scene = scene();
        long near = SectionPos.asLong(0, 0, 0);
        long occluded = SectionPos.asLong(1, 0, 0);
        when(scene.sectionKeys()).thenReturn(LongArrayList.of(near, occluded));
        renderer.prepareTravel(scene, new CameraRenderState());
        Object portal = get(renderer, "travel");
        Frustum frustum = mock(Frustum.class);
        when(frustum.isVisible(any(AABB.class))).thenReturn(true);
        set(portal, "cullFrustum", frustum);
        set(portal, "rendered", true);
        renderer.transitionTravel(true);
        Minecraft minecraft = mock(Minecraft.class);
        LevelRenderer main = mock(LevelRenderer.class);
        Options options = mock(Options.class, RETURNS_DEEP_STUBS);
        when(options.chunkSectionFadeInTime().get()).thenReturn(0.0);
        set(minecraft, "options", options);
        set(minecraft, "levelRenderer", main);
        ObjectArrayList<SectionRenderDispatcher.RenderSection> drawn = new ObjectArrayList<>();
        when(main.visibleSections()).thenReturn(drawn);
        when(main.isSectionCompiledAndVisible(new BlockPos(8, 8, 8), 0)).thenReturn(true);
        Method ready = ClientPortalRenderer.class.getDeclaredMethod("mainTravelCoverageReady");
        ready.setAccessible(true);
        try (MockedStatic<Minecraft> access = mockStatic(Minecraft.class)) {
            access.when(Minecraft::getInstance).thenReturn(minecraft);
            assertFalse((boolean) ready.invoke(renderer));
            SectionRenderDispatcher.RenderSection section = mock(SectionRenderDispatcher.RenderSection.class);
            when(section.getSectionNode()).thenReturn(near);
            drawn.add(section);
            assertTrue((boolean) ready.invoke(renderer));
            verify(main, never()).isSectionCompiledAndVisible(new BlockPos(24, 8, 8), 0);
        } finally {
            renderer.clear();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void fullWorldDrawUploadsZeroInAllFourClipPlaneComponents() throws ReflectiveOperationException {
        ClientPortalRenderer renderer = ClientPortalRenderer.instance();
        renderer.clear();
        PortalScene scene = scene();
        when(scene.fullWorld()).thenReturn(true);
        renderer.prepareTravel(scene, new CameraRenderState());
        Object portal = get(renderer, "travel");
        Method plane = ClientPortalRenderer.class.getDeclaredMethod("cameraPlane", portal.getClass());
        plane.setAccessible(true);
        Vector4f cameraPlane = (Vector4f) plane.invoke(renderer, portal);
        for (int component = 0; component < 4; component++) {
            assertEquals(0.0f, cameraPlane.get(component), 0.0f);
        }
        set(portal, "environment", mock(PortalEnvironmentRenderer.class));
        set(portal, "viewport", new PortalViewport(0, 0, 1920, 1080));
        CameraRenderState camera = new CameraRenderState();
        camera.pos = Vec3.ZERO;
        camera.viewRotationMatrix = new Matrix4f();
        set(renderer, "camera", camera);
        set(renderer, "pipelines", mock(PortalPipelines.class));
        Class<?> sectionType = Class.forName(ClientPortalRenderer.class.getName() + "$Section");
        Constructor<?> constructor = sectionType.getDeclaredConstructor(long.class, long.class);
        constructor.setAccessible(true);
        long key = SectionPos.asLong(4, 6, -2);
        Object section = constructor.newInstance(key, 1L);
        PortalGpuMesh mesh = mock(PortalGpuMesh.class);
        ((EnumMap<ChunkSectionLayer, PortalGpuMesh>) get(section, "layers")).put(ChunkSectionLayer.SOLID, mesh);
        ((Long2ObjectOpenHashMap<Object>) get(portal, "sections")).put(key, section);
        ((List<Object>) get(portal, "drawSections")).add(section);
        Method draw = ClientPortalRenderer.class.getDeclaredMethod("drawTerrain", portal.getClass(),
            ChunkSectionLayer.class, RenderPass.class);
        draw.setAccessible(true);
        RenderPass pass = mock(RenderPass.class);
        GpuDevice device = mock(GpuDevice.class, RETURNS_DEEP_STUBS);
        GpuBuffer buffer = mock(GpuBuffer.class);
        when(device.createBuffer(any(), anyInt(), any(ByteBuffer.class))).thenAnswer(invocation -> {
            ByteBuffer data = invocation.getArgument(2);
            for (int component = 0; component < 4; component++) {
                assertEquals(0.0f, data.getFloat(component * Float.BYTES), 0.0f);
            }
            return buffer;
        });
        Minecraft minecraft = mock(Minecraft.class, RETURNS_DEEP_STUBS);
        try (MockedStatic<RenderSystem> system = mockStatic(RenderSystem.class, RETURNS_DEEP_STUBS);
             MockedStatic<Minecraft> game = mockStatic(Minecraft.class)) {
            system.when(RenderSystem::getDevice).thenReturn(device);
            game.when(Minecraft::getInstance).thenReturn(minecraft);
            draw.invoke(renderer, portal, ChunkSectionLayer.SOLID, pass);
            verify(mesh).draw(pass);
            verify(device).createBuffer(any(), anyInt(), any(ByteBuffer.class));
        } finally {
            renderer.clear();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void arrivalReadinessRequiresCurrentVisibleDrawnSectionsWhileHiddenBackgroundBuildsContinue() throws ReflectiveOperationException {
        ClientPortalRenderer renderer = ClientPortalRenderer.instance();
        renderer.clear();
        PortalScene scene = scene();
        long forward = SectionPos.asLong(0, 5, -2);
        long below = SectionPos.asLong(0, -4, 0);
        long behind = SectionPos.asLong(0, 5, 1);
        long side = SectionPos.asLong(3, 5, -2);
        when(scene.sectionKeys()).thenReturn(new LongOpenHashSet(new long[]{forward, below, behind, side}));
        when(scene.revision(anyLong())).thenReturn(-1L);
        renderer.prepareTravel(scene, new CameraRenderState());
        Object portal = get(renderer, "travel");
        Long2ObjectOpenHashMap<Object> sections = (Long2ObjectOpenHashMap<Object>) get(portal, "sections");
        Class<?> sectionType = Class.forName(ClientPortalRenderer.class.getName() + "$Section");
        Constructor<?> section = sectionType.getDeclaredConstructor(long.class, long.class);
        section.setAccessible(true);
        LongOpenHashSet drawn = (LongOpenHashSet) get(renderer, "travelDrawSections");
        drawn.add(forward);
        set(renderer, "travelDrawn", true);
        Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(60), 16.0F / 9.0F, 0.05F, 512);
        Frustum frustum = new Frustum(new Matrix4f(), projection);
        frustum.prepare(8, 88, 0);
        set(portal, "cullFrustum", frustum);
        try {
            assertFalse(renderer.travelReady());
            set(renderer, "camera", new CameraRenderState());
            Method maintain = ClientPortalRenderer.class.getDeclaredMethod("maintain", portal.getClass());
            maintain.setAccessible(true);
            maintain.invoke(renderer, portal);
            assertFalse((boolean) get(portal, "hasInitialBuild"));
            assertFalse((boolean) get(portal, "hasResidentBuild"));
            verify(scene, never()).world(anyLong());
            when(scene.revision(forward)).thenReturn(1L);
            sections.put(forward, drawableSection(section, forward));
            assertTrue(renderer.travelReady());
            ((LongSet) get(portal, "dirty")).add(below);
            ((LongSet) get(portal, "building")).add(below);
            assertTrue(renderer.travelReady());
            Frustum wide = new Frustum(new Matrix4f(), new Matrix4f().perspective((float) Math.toRadians(140), 16.0F / 9.0F, 0.05F, 512));
            wide.prepare(8, 88, 0);
            set(portal, "cullFrustum", wide);
            assertFalse(renderer.travelReady());
            when(scene.revision(side)).thenReturn(1L);
            sections.put(side, drawableSection(section, side));
            assertFalse(renderer.travelReady());
            drawn.add(side);
            assertTrue(renderer.travelReady());
            Frustum turned = new Frustum(new Matrix4f().rotationY((float) Math.PI), projection);
            turned.prepare(8, 88, 0);
            set(portal, "cullFrustum", turned);
            assertFalse(renderer.travelReady());
            when(scene.revision(behind)).thenReturn(1L);
            sections.put(behind, drawableSection(section, behind));
            assertFalse(renderer.travelReady());
            drawn.add(behind);
            assertTrue(renderer.travelReady());
            ((LongSet) get(portal, "dirty")).add(behind);
            assertFalse(renderer.travelReady());
            ((LongSet) get(portal, "dirty")).remove(behind);
            when(scene.empty(behind)).thenReturn(true);
            when(scene.revision(behind)).thenReturn(-1L);
            assertFalse(renderer.travelReady());
            when(scene.revision(behind)).thenReturn(2L);
            assertTrue(renderer.travelReady());
        } finally {
            renderer.clear();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void fullyOccludedCompiledSectionNeedsNoDrawButStillRequiresCurrentCleanSnapshot() throws ReflectiveOperationException {
        ClientPortalRenderer renderer = ClientPortalRenderer.instance();
        renderer.clear();
        PortalScene scene = scene();
        long key = SectionPos.asLong(0, 5, 0);
        when(scene.sectionKeys()).thenReturn(new LongOpenHashSet(new long[]{key}));
        when(scene.revision(key)).thenReturn(1L);
        renderer.prepareTravel(scene, new CameraRenderState());
        Object portal = get(renderer, "travel");
        set(renderer, "travelDrawn", true);
        set(renderer, "pendingBuilds", 1);
        PortalSectionMesh mesh = mock(PortalSectionMesh.class);
        when(mesh.meshes()).thenReturn(Map.of());
        Method finish = ClientPortalRenderer.class.getDeclaredMethod("finish", portal.getClass(), long.class,
            long.class, int.class, PortalScene.MeshIdentity.class, PortalSectionMesh.class, Throwable.class);
        finish.setAccessible(true);
        try {
            finish.invoke(renderer, portal, key, 1L, get(portal, "generation"), null, mesh, null);
            assertFalse(renderer.travelReady());
            set(renderer, "travelDrawEpoch", get(renderer, "travelMeshEpoch"));
            assertTrue(((LongOpenHashSet) get(renderer, "travelDrawSections")).isEmpty());
            assertTrue(renderer.travelReady());
            LongSet dirty = (LongSet) get(portal, "dirty");
            dirty.add(key);
            assertFalse(renderer.travelReady());
            dirty.remove(key);
            LongSet building = (LongSet) get(portal, "building");
            building.add(key);
            assertFalse(renderer.travelReady());
            building.remove(key);
            when(scene.revision(key)).thenReturn(2L);
            assertFalse(renderer.travelReady());
            when(scene.empty(key)).thenReturn(true);
            when(scene.revision(key)).thenReturn(-1L);
            assertFalse(renderer.travelReady());
        } finally {
            renderer.clear();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void crossingCoverageAcceptsStaleRebuildingAndUndrawnMeshesButNotVisibleHoles() throws ReflectiveOperationException {
        ClientPortalRenderer renderer = ClientPortalRenderer.instance();
        renderer.clear();
        PortalScene scene = scene();
        long forward = SectionPos.asLong(0, 5, -2);
        long side = SectionPos.asLong(3, 5, -2);
        long behind = SectionPos.asLong(0, 5, 1);
        when(scene.sectionKeys()).thenReturn(new LongOpenHashSet(new long[]{forward, side, behind}));
        when(scene.revision(anyLong())).thenReturn(-1L);
        renderer.prepareTravel(scene, new CameraRenderState());
        Object portal = get(renderer, "travel");
        PortalShaderRenderer.Session session = mock(PortalShaderRenderer.Session.class);
        when(session.ready()).thenReturn(true);
        when(session.target()).thenReturn(mock(TextureTarget.class, RETURNS_DEEP_STUBS));
        set(renderer, "shaderRenderer", mock(PortalShaderRenderer.class));
        set(portal, "shader", session);
        Long2ObjectOpenHashMap<Object> sections = (Long2ObjectOpenHashMap<Object>) get(portal, "sections");
        Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(60), 16.0F / 9.0F, 0.05F, 512);
        Frustum frustum = new Frustum(new Matrix4f(), projection);
        frustum.prepare(8, 88, 0);
        set(portal, "cullFrustum", frustum);
        try {
            assertFalse(renderer.travelCovered());
            set(renderer, "travelDrawn", true);
            assertFalse(renderer.travelCovered());
            when(scene.revision(forward)).thenReturn(1L);
            assertFalse(renderer.travelCovered());
            when(scene.empty(forward)).thenReturn(true);
            assertTrue(renderer.travelCovered());
            when(scene.empty(forward)).thenReturn(false);
            sections.put(forward, drawableSection(sectionConstructor(), forward));
            assertTrue(renderer.travelCovered());
            when(scene.revision(forward)).thenReturn(2L);
            ((LongSet) get(portal, "dirty")).add(forward);
            ((LongSet) get(portal, "building")).add(forward);
            set(renderer, "travelMeshEpoch", 7L);
            assertFalse(renderer.travelReady());
            assertTrue(renderer.travelCovered());
            when(scene.revision(forward)).thenReturn(-1L);
            assertTrue(renderer.travelCovered());
            Frustum wide = new Frustum(new Matrix4f(), new Matrix4f().perspective((float) Math.toRadians(140), 16.0F / 9.0F, 0.05F, 512));
            wide.prepare(8, 88, 0);
            set(portal, "cullFrustum", wide);
            assertFalse(renderer.travelCovered());
            when(scene.revision(side)).thenReturn(1L);
            sections.put(side, drawableSection(sectionConstructor(), side));
            assertTrue(renderer.travelCovered());
            when(session.ready()).thenReturn(false);
            assertFalse(renderer.travelCovered());
        } finally {
            renderer.clear();
        }
    }

    @Test
    public void currentDisplayCameraAndProjectionMapThroughTheActualPortalRotation() throws ReflectiveOperationException {
        ClientPortalRenderer renderer = ClientPortalRenderer.instance();
        renderer.clear();
        renderer.prepareTravel(scene(), new CameraRenderState());
        Camera source = mock(Camera.class);
        when(source.isInitialized()).thenReturn(true);
        when(source.position()).thenReturn(new Vec3(100, 88, 0));
        when(source.blockPosition()).thenReturn(new BlockPos(100, 88, 0));
        when(source.rotation()).thenReturn(new Quaternionf());
        Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(95), 2, 0.05F, 512);
        when(source.getViewRotationMatrix(any())).thenAnswer(call -> ((Matrix4f) call.getArgument(0)).identity());
        when(source.getViewRotationProjectionMatrix(any())).thenAnswer(call -> ((Matrix4f) call.getArgument(0)).set(projection));
        try {
            renderer.updateTravelCamera(source, Similarity.of(OpticTransform.of(AxisPermutation.of(Face.S, Face.U, Face.W), 100, 0, 0).inverse(), 1.0D));
            CameraRenderState destination = (CameraRenderState) get(renderer, "travelCamera");
            assertEquals(new Vec3(0, 88, 0), destination.pos);
            assertEquals(projection, destination.projectionMatrix);
            Vector3f forward = new Matrix4f(destination.viewRotationMatrix).invert().transformDirection(new Vector3f(0, 0, -1));
            assertEquals(new Vector3f(-1, 0, 0), forward);
            assertEquals(new Vec3(100, 88, 0), get(renderer, "travelDisplayCamera") instanceof CameraRenderState display ? display.pos : null);
        } finally {
            renderer.clear();
        }
    }

    @Test
    public void finalAsyncUploadRequiresAFrameWithCurrentMeshesBeforeReady() throws ReflectiveOperationException {
        ClientPortalRenderer renderer = ClientPortalRenderer.instance();
        renderer.clear();
        PortalScene scene = scene();
        long key = SectionPos.asLong(0, 5, 0);
        when(scene.sectionKeys()).thenReturn(new LongOpenHashSet(new long[]{key}));
        when(scene.revision(key)).thenReturn(1L);
        renderer.prepareTravel(scene, new CameraRenderState());
        Object portal = get(renderer, "travel");
        PortalShaderRenderer.Session session = mock(PortalShaderRenderer.Session.class);
        TextureTarget target = mock(TextureTarget.class, RETURNS_DEEP_STUBS);
        when(session.ready()).thenReturn(true);
        when(session.target()).thenReturn(target);
        set(renderer, "shaderRenderer", mock(PortalShaderRenderer.class));
        set(portal, "shader", session);
        set(renderer, "travelDrawn", true);
        set(renderer, "pendingBuilds", 1);
        PortalSectionMesh mesh = mock(PortalSectionMesh.class);
        when(mesh.meshes()).thenReturn(Map.of());
        Method finish = ClientPortalRenderer.class.getDeclaredMethod("finish", portal.getClass(), long.class,
            long.class, int.class, PortalScene.MeshIdentity.class, PortalSectionMesh.class, Throwable.class);
        finish.setAccessible(true);
        try {
            assertFalse(renderer.travelReady());
            finish.invoke(renderer, portal, key, 1L, get(portal, "generation"), null, mesh, null);
            assertEquals(0, get(renderer, "pendingBuilds"));
            assertFalse(renderer.travelReady());
            assertTrue(renderer.travelDrawable());
            set(renderer, "travelDrawEpoch", get(renderer, "travelMeshEpoch"));
            ((LongOpenHashSet) get(renderer, "travelDrawSections")).add(key);
            assertTrue(renderer.travelReady());
        } finally {
            renderer.clear();
        }
    }

    @Test
    public void completeStationaryTravelRetainsItsLeaseWithoutAnotherDestinationDraw() throws ReflectiveOperationException {
        ClientPortalRenderer renderer = ClientPortalRenderer.instance();
        renderer.clear();
        PortalScene scene = scene();
        when(scene.sectionKeys()).thenReturn(new LongOpenHashSet());
        renderer.prepareTravel(scene, new CameraRenderState());
        Object portal = get(renderer, "travel");
        PortalShaderRenderer.Session session = mock(PortalShaderRenderer.Session.class);
        set(portal, "shader", session);
        set(renderer, "travelDrawn", true);
        Class<?> dimensions = Class.forName(ClientPortalRenderer.class.getName() + "$RenderDimensions");
        Method render = ClientPortalRenderer.class.getDeclaredMethod("renderTravel", dimensions);
        render.setAccessible(true);
        try {
            assertTrue(renderer.travelReady());
            render.invoke(renderer, new Object[]{null});
            assertEquals(true, get(portal, "rendered"));
            assertSame(session, get(portal, "shader"));
            verify(session).target();
            renderer.invalidateTravel(SectionPos.asLong(0, 0, 0));
            when(scene.sectionKeys()).thenReturn(new LongOpenHashSet(new long[]{SectionPos.asLong(0, 0, 0)}));
            assertFalse(renderer.travelReady());
        } finally {
            renderer.clear();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void disconnectRetiresBothMeshJobsAndReconnectAdmitsNewBuilds() throws ReflectiveOperationException {
        ClientPortalRenderer renderer = ClientPortalRenderer.instance();
        renderer.clear();
        ExecutorService originalCompiler = (ExecutorService) get(renderer, "compiler");
        ExecutorService compiler = mock(ExecutorService.class);
        List<Runnable> jobs = new ArrayList<>();
        List<Runnable> clientTasks = new ArrayList<>();
        doAnswer(invocation -> {
            jobs.add(invocation.getArgument(0));
            return null;
        }).when(compiler).execute(any(Runnable.class));
        set(renderer, "compiler", compiler);
        Minecraft minecraft = mock(Minecraft.class, RETURNS_DEEP_STUBS);
        doAnswer(invocation -> {
            clientTasks.add(invocation.getArgument(0));
            return null;
        }).when(minecraft).execute(any(Runnable.class));
        PortalSectionMesh first = mock(PortalSectionMesh.class);
        PortalSectionMesh second = mock(PortalSectionMesh.class);
        PortalSectionMesh rejoinedFirst = mock(PortalSectionMesh.class);
        PortalSectionMesh rejoinedSecond = mock(PortalSectionMesh.class);
        when(rejoinedFirst.meshes()).thenReturn(Map.of());
        when(rejoinedSecond.meshes()).thenReturn(Map.of());
        long firstKey = SectionPos.asLong(0, 0, 0);
        long secondKey = SectionPos.asLong(1, 0, 0);
        PortalScene scene = scene();
        when(scene.revision(anyLong())).thenReturn(1L);
        when(scene.sectionKeys()).thenReturn(new LongArrayList(new long[]{firstKey, secondKey}));
        renderer.replaceScene(1, scene);
        Object portal = ((Map<?, ?>) get(renderer, "portals")).get(1);
        Method schedule = ClientPortalRenderer.class.getDeclaredMethod("schedule", portal.getClass(), long.class);
        schedule.setAccessible(true);
        Method maintain = ClientPortalRenderer.class.getDeclaredMethod("maintain", portal.getClass());
        maintain.setAccessible(true);
        Method dispatch = ClientPortalRenderer.class.getDeclaredMethod("dispatchBuilds");
        dispatch.setAccessible(true);
        try (MockedStatic<Minecraft> game = mockStatic(Minecraft.class);
             MockedStatic<PortalSectionMesh> meshes = mockStatic(PortalSectionMesh.class)) {
            game.when(Minecraft::getInstance).thenReturn(minecraft);
            meshes.when(() -> PortalSectionMesh.compile(anyLong(), any(), any(), any(), any(), any(Boolean.class), any()))
                .thenReturn(first, second, rejoinedFirst, rejoinedSecond);
            schedule.invoke(renderer, portal, firstKey);
            schedule.invoke(renderer, portal, secondKey);
            assertEquals(2, get(renderer, "pendingBuilds"));
            jobs.removeFirst().run();
            clientTasks.clear();
            renderer.clear();
            assertEquals(1, get(renderer, "pendingBuilds"));
            verify(first).close();
            jobs.removeFirst().run();
            clientTasks.clear();
            renderer.finishBuilds();
            renderer.finishBuilds();
            assertEquals(0, get(renderer, "pendingBuilds"));
            verify(second).close();
            assertTrue(((LongSet) get(portal, "building")).isEmpty());
            renderer.replaceScene(1, scene);
            Object rejoined = ((Map<?, ?>) get(renderer, "portals")).get(1);
            CameraRenderState camera = new CameraRenderState();
            camera.pos = Vec3.ZERO;
            camera.blockPos = BlockPos.ZERO;
            Frustum frustum = mock(Frustum.class);
            when(frustum.isVisible(any(AABB.class))).thenReturn(true);
            set(renderer, "camera", camera);
            set(rejoined, "camera", camera);
            set(rejoined, "contentCamera", camera);
            set(rejoined, "cullFrustum", frustum);
            maintain.invoke(renderer, rejoined);
            dispatch.invoke(renderer);
            assertEquals(2, get(renderer, "pendingBuilds"));
            assertEquals(2, jobs.size());
            jobs.removeFirst().run();
            jobs.removeFirst().run();
            renderer.finishBuilds();
            renderer.finishBuilds();
            assertEquals(0, get(renderer, "pendingBuilds"));
            assertEquals(2, ((Map<?, ?>) get(rejoined, "sections")).size());
            assertTrue(((LongSet) get(rejoined, "building")).isEmpty());
            verify(first, times(1)).close();
            verify(second, times(1)).close();
            verify(rejoinedFirst).close();
            verify(rejoinedSecond).close();
            verify(minecraft, never()).execute(any(Runnable.class));
        } finally {
            set(renderer, "compiler", originalCompiler);
            renderer.clear();
        }
    }

    @Test
    public void adoptedDrawableSurvivesDirtySectionsButNotMissingShaderResources() throws ReflectiveOperationException {
        ClientPortalRenderer renderer = ClientPortalRenderer.instance();
        renderer.clear();
        PortalScene scene = scene();
        long key = SectionPos.asLong(0, 5, 0);
        when(scene.sectionKeys()).thenReturn(new LongOpenHashSet(new long[]{key}));
        when(scene.revision(key)).thenReturn(1L);
        renderer.prepareTravel(scene, new CameraRenderState());
        Object portal = get(renderer, "travel");
        PortalShaderRenderer shaders = mock(PortalShaderRenderer.class);
        PortalShaderRenderer.Session session = mock(PortalShaderRenderer.Session.class);
        TextureTarget target = mock(TextureTarget.class, RETURNS_DEEP_STUBS);
        when(session.ready()).thenReturn(true);
        when(session.target()).thenReturn(target);
        set(renderer, "shaderRenderer", shaders);
        set(portal, "shader", session);
        set(renderer, "travelDrawn", true);
        Class<?> sectionType = Class.forName(ClientPortalRenderer.class.getName() + "$Section");
        Constructor<?> constructor = sectionType.getDeclaredConstructor(long.class, long.class);
        constructor.setAccessible(true);
        @SuppressWarnings("unchecked")
        Long2ObjectOpenHashMap<Object> sections = (Long2ObjectOpenHashMap<Object>) get(portal, "sections");
        sections.put(key, constructor.newInstance(key, 1L));
        ((LongOpenHashSet) get(renderer, "travelDrawSections")).add(key);
        try {
            assertTrue(renderer.travelReady());
            assertTrue(renderer.travelDrawable());
            renderer.invalidateTravel(key);
            assertFalse(renderer.travelReady());
            assertTrue(renderer.travelDrawable());
            when(session.ready()).thenReturn(false);
            assertFalse(renderer.travelDrawable());
            when(session.ready()).thenReturn(true);
            when(target.getDepthTexture()).thenReturn(null);
            assertFalse(renderer.travelDrawable());
            set(portal, "active", false);
            assertFalse(renderer.travelDrawable());
        } finally {
            renderer.clear();
            assertFalse(renderer.travelDrawable());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void shaderTerrainSharesTransformsAcrossLayersWithoutSharingNestedPortalCameras() throws ReflectiveOperationException {
        ClientPortalRenderer renderer = ClientPortalRenderer.instance();
        renderer.clear();
        renderer.replaceScene(1, scene());
        renderer.replaceScene(2, scene());
        Map<?, ?> portals = (Map<?, ?>) get(renderer, "portals");
        Object parent = portals.get(1);
        Object child = portals.get(2);
        Object parentSection = orderingSection(SectionPos.asLong(0, 0, 0));
        Object childSection = orderingSection(SectionPos.asLong(0, 0, 0));
        for (Object section : List.of(parentSection, childSection)) {
            EnumMap<ChunkSectionLayer, PortalGpuMesh> layers = (EnumMap<ChunkSectionLayer, PortalGpuMesh>) get(section, "layers");
            layers.put(ChunkSectionLayer.CUTOUT, mock(PortalGpuMesh.class));
            layers.put(ChunkSectionLayer.TRANSLUCENT, mock(PortalGpuMesh.class));
        }
        for (Object portal : List.of(parent, child)) {
            set(portal, "shader", mock(PortalShaderRenderer.Session.class));
            set(portal, "environment", mock(PortalEnvironmentRenderer.class));
        }
        ((List<Object>) get(parent, "drawSections")).add(parentSection);
        ((List<Object>) get(child, "drawSections")).add(childSection);
        CameraRenderState main = new CameraRenderState();
        main.pos = new Vec3(1, 2, 3);
        CameraRenderState nested = new CameraRenderState();
        nested.pos = new Vec3(20, 30, 40);
        DynamicGpuData uniforms = mock(DynamicGpuData.class);
        GpuBufferSlice parentSlice = mock(GpuBufferSlice.class);
        GpuBufferSlice childSlice = mock(GpuBufferSlice.class);
        when(uniforms.writeTransform(any(DynamicGpuData.Transform.class))).thenReturn(parentSlice, childSlice);
        RenderPass parentPass = mock(RenderPass.class);
        RenderPass childPass = mock(RenderPass.class);
        Method draw = ClientPortalRenderer.class.getDeclaredMethod("drawTerrain", parent.getClass(), ChunkSectionLayer.class, RenderPass.class);
        draw.setAccessible(true);
        Method clear = ClientPortalRenderer.class.getDeclaredMethod("clearTerrainTransforms", parent.getClass());
        clear.setAccessible(true);
        Minecraft minecraft = mock(Minecraft.class, RETURNS_DEEP_STUBS);
        try (MockedStatic<RenderSystem> system = mockStatic(RenderSystem.class, RETURNS_DEEP_STUBS);
             MockedStatic<Minecraft> game = mockStatic(Minecraft.class);
             MockedStatic<PortalIrisTerrain> terrain = mockStatic(PortalIrisTerrain.class)) {
            game.when(Minecraft::getInstance).thenReturn(minecraft);
            system.when(RenderSystem::getDynamicUniforms).thenReturn(uniforms);
            set(renderer, "camera", main);
            draw.invoke(renderer, parent, ChunkSectionLayer.SOLID, parentPass);
            set(renderer, "camera", nested);
            for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
                draw.invoke(renderer, child, layer, childPass);
            }
            clear.invoke(null, child);
            set(renderer, "camera", main);
            draw.invoke(renderer, parent, ChunkSectionLayer.CUTOUT, parentPass);
            draw.invoke(renderer, parent, ChunkSectionLayer.TRANSLUCENT, parentPass);
            verify(uniforms, times(2)).writeTransform(any(DynamicGpuData.Transform.class));
            verify(uniforms).writeTransform(argThat((DynamicGpuData.Transform transform) -> transform.modelView().m30() == -1.0f && transform.modelView().m31() == -2.0f));
            verify(uniforms).writeTransform(argThat((DynamicGpuData.Transform transform) -> transform.modelView().m30() == -20.0f && transform.modelView().m31() == -30.0f));
            verify(parentPass, times(3)).setUniform("DynamicTransforms", parentSlice);
            verify(childPass, times(3)).setUniform("DynamicTransforms", childSlice);
            assertSame(parentSlice, get(parentSection, "transform"));
            assertSame(null, get(childSection, "transform"));
            clear.invoke(null, parent);
            assertSame(null, get(parentSection, "transform"));
            verifyNoInteractions(parentSlice, childSlice);
        } finally {
            renderer.clear();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void shaderDestinationClearsBorrowedTransformsAfterFrameFailureAndBeforeFeatureCleanup() throws ReflectiveOperationException {
        ClientPortalRenderer renderer = ClientPortalRenderer.instance();
        renderer.clear();
        PortalScene scene = scene();
        when(scene.environment()).thenReturn(PortalEnvironmentTest.environment(OpticTransform.IDENTITY));
        renderer.replaceScene(1, scene);
        Object portal = ((Map<?, ?>) get(renderer, "portals")).get(1);
        long key = SectionPos.asLong(0, 0, 0);
        Object section = orderingSection(key);
        EnumMap<ChunkSectionLayer, PortalGpuMesh> layers = (EnumMap<ChunkSectionLayer, PortalGpuMesh>) get(section, "layers");
        layers.put(ChunkSectionLayer.CUTOUT, mock(PortalGpuMesh.class));
        layers.put(ChunkSectionLayer.TRANSLUCENT, mock(PortalGpuMesh.class));
        ((Long2ObjectOpenHashMap<Object>) get(portal, "sections")).put(key, section);
        ((List<Object>) get(portal, "drawSections")).add(section);
        CameraRenderState camera = new CameraRenderState();
        camera.pos = new Vec3(1, 2, 3);
        camera.blockPos = BlockPos.containing(camera.pos);
        set(renderer, "camera", camera);
        set(portal, "camera", camera);
        set(portal, "contentCamera", camera);
        set(portal, "viewport", new PortalViewport(0, 0, 1920, 1080));
        set(portal, "target", mock(TextureTarget.class));
        PortalShaderRenderer.Session shader = mock(PortalShaderRenderer.Session.class);
        PortalShaderRenderer.Frame successful = mock(PortalShaderRenderer.Frame.class);
        PortalShaderRenderer.Frame failing = mock(PortalShaderRenderer.Frame.class);
        IllegalStateException failure = new IllegalStateException("Shader frame cleanup failed");
        doAnswer(call -> { throw failure; }).when(failing).close();
        when(shader.begin(any())).thenReturn(successful, failing, successful);
        set(portal, "shader", shader);
        PortalEnvironmentRenderer environment = mock(PortalEnvironmentRenderer.class);
        set(portal, "environment", environment);
        PortalFeatureRenderer features = mock(PortalFeatureRenderer.class);
        doAnswer(call -> {
            assertSame(null, get(section, "transform"));
            return null;
        }).when(features).closeFrame();
        ((PortalFeatureRenderer[]) get(get(renderer, "targets"), "features"))[0] = features;
        ((ProjectionMatrixBuffer[]) get(get(renderer, "targets"), "projections"))[0] = mock(ProjectionMatrixBuffer.class);
        DynamicGpuData uniforms = mock(DynamicGpuData.class);
        GpuBufferSlice first = mock(GpuBufferSlice.class);
        GpuBufferSlice second = mock(GpuBufferSlice.class);
        GpuBufferSlice third = mock(GpuBufferSlice.class);
        when(uniforms.writeTransform(any(DynamicGpuData.Transform.class))).thenReturn(first, second, third);
        Class<?> dimensions = Class.forName(ClientPortalRenderer.class.getName() + "$RenderDimensions");
        Constructor<?> constructor = dimensions.getDeclaredConstructor(int.class, int.class, int.class);
        constructor.setAccessible(true);
        Object size = constructor.newInstance(1920, 1080, 0);
        Method render = ClientPortalRenderer.class.getDeclaredMethod("renderShaderPortal", portal.getClass(), dimensions);
        render.setAccessible(true);
        Minecraft minecraft = mock(Minecraft.class, RETURNS_DEEP_STUBS);
        try (MockedStatic<RenderSystem> system = mockStatic(RenderSystem.class, RETURNS_DEEP_STUBS);
             MockedStatic<Minecraft> game = mockStatic(Minecraft.class);
             MockedStatic<PortalClipScope> clipping = mockStatic(PortalClipScope.class);
             MockedStatic<PortalShaderScope> scopes = mockStatic(PortalShaderScope.class);
             MockedStatic<PortalIrisTerrain> terrain = mockStatic(PortalIrisTerrain.class);
             MockedConstruction<PortalShaderCamera> shaderCameras = mockConstruction(PortalShaderCamera.class,
                 (shaderCamera, context) -> when(shaderCamera.getViewRotationMatrix(any())).thenAnswer(call -> call.getArgument(0)))) {
            game.when(Minecraft::getInstance).thenReturn(minecraft);
            system.when(RenderSystem::getDynamicUniforms).thenReturn(uniforms);
            system.when(RenderSystem::getModelViewStack).thenReturn(new Matrix4fStack(8));
            render.invoke(renderer, portal, size);
            assertSame(null, get(section, "transform"));
            camera.pos = new Vec3(4, 5, 6);
            InvocationTargetException thrown = assertThrows(InvocationTargetException.class, () -> render.invoke(renderer, portal, size));
            assertSame(failure, thrown.getCause());
            assertSame(null, get(section, "transform"));
            render.invoke(renderer, portal, size);
            verify(uniforms, times(3)).writeTransform(any(DynamicGpuData.Transform.class));
            verify(environment, times(3)).endFrame();
            verify(features, times(6)).closeFrame();
            verifyNoInteractions(first, second, third);
        } finally {
            renderer.clear();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void shaderTerrainDrawsWithoutAllocatingVanillaClippingBuffers() throws ReflectiveOperationException {
        ClientPortalRenderer renderer = ClientPortalRenderer.instance();
        renderer.clear();
        renderer.replaceScene(1, scene());
        Object portal = ((Map<?, ?>) get(renderer, "portals")).get(1);
        PortalShaderRenderer.Session shader = mock(PortalShaderRenderer.Session.class);
        set(portal, "shader", shader);
        set(portal, "environment", mock(PortalEnvironmentRenderer.class));
        set(portal, "viewport", new PortalViewport(0, 0, 1920, 1080));
        CameraRenderState camera = new CameraRenderState();
        camera.pos = new Vec3(1, 2, 3);
        camera.viewRotationMatrix = new Matrix4f();
        set(renderer, "camera", camera);
        Class<?> sectionType = Class.forName(ClientPortalRenderer.class.getName() + "$Section");
        Constructor<?> constructor = sectionType.getDeclaredConstructor(long.class, long.class);
        constructor.setAccessible(true);
        Object section = constructor.newInstance(SectionPos.asLong(0, 0, 0), 1L);
        PortalGpuMesh mesh = mock(PortalGpuMesh.class);
        ((EnumMap<ChunkSectionLayer, PortalGpuMesh>) get(section, "layers")).put(ChunkSectionLayer.CUTOUT, mesh);
        ((List<Object>) get(portal, "drawSections")).add(section);
        Method draw = ClientPortalRenderer.class.getDeclaredMethod("drawTerrain", portal.getClass(),
            ChunkSectionLayer.class, RenderPass.class);
        draw.setAccessible(true);
        RenderPass pass = mock(RenderPass.class);
        GpuDevice device = mock(GpuDevice.class, RETURNS_DEEP_STUBS);
        Minecraft minecraft = mock(Minecraft.class, RETURNS_DEEP_STUBS);
        try (MockedStatic<RenderSystem> system = mockStatic(RenderSystem.class, RETURNS_DEEP_STUBS);
             MockedStatic<Minecraft> game = mockStatic(Minecraft.class);
             MockedStatic<PortalIrisTerrain> terrain = mockStatic(PortalIrisTerrain.class)) {
            system.when(RenderSystem::getDevice).thenReturn(device);
            game.when(Minecraft::getInstance).thenReturn(minecraft);
            draw.invoke(renderer, portal, ChunkSectionLayer.CUTOUT, pass);
            verify(mesh).draw(pass);
            verify(shader).endTerrain();
            verifyNoInteractions(device);
        } finally {
            renderer.clear();
        }
    }

    @Test
    public void failedTerrainBindingEndsThePrivateIrisTerrainPhase() throws ReflectiveOperationException {
        ClientPortalRenderer renderer = ClientPortalRenderer.instance();
        renderer.clear();
        PortalShaderRenderer.Session shader = mock(PortalShaderRenderer.Session.class);
        IllegalStateException failure = new IllegalStateException("Terrain binding interrupted");
        when(shader.terrain(ChunkSectionLayer.CUTOUT, false)).thenThrow(failure);
        try {
            renderer.replaceScene(1, scene());
            Field portals = ClientPortalRenderer.class.getDeclaredField("portals");
            portals.setAccessible(true);
            Object portal = ((Map<?, ?>) portals.get(renderer)).get(1);
            Field session = portal.getClass().getDeclaredField("shader");
            session.setAccessible(true);
            session.set(portal, shader);
            Method draw = ClientPortalRenderer.class.getDeclaredMethod("drawTerrain", portal.getClass(),
                ChunkSectionLayer.class, RenderPass.class);
            draw.setAccessible(true);
            InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                () -> draw.invoke(renderer, portal, ChunkSectionLayer.CUTOUT, mock(RenderPass.class)));
            assertSame(failure, thrown.getCause());
            verify(shader).endTerrain();
        } finally {
            renderer.clear();
        }
    }

    @Test
    public void incompatibleShadersRetainNativeScenesAndOnlyRetryAfterCooldown() {
        ClientPortalRenderer renderer = ClientPortalRenderer.instance();
        renderer.clear();
        CameraRenderState camera = new CameraRenderState();
        RenderPass pass = mock(RenderPass.class);
        PortalScene scene = scene();

        try (MockedStatic<RenderSystem> system = mockStatic(RenderSystem.class);
             MockedStatic<PortalShaderScope> shaders = mockStatic(PortalShaderScope.class)) {
            shaders.when(PortalShaderScope::rendering).thenThrow(new IllegalStateException("Shader binding unavailable"));
            renderer.prepare(camera, null);
            renderer.composite(pass);
            shaders.verify(PortalShaderScope::rendering, never());
            shaders.verify(PortalShaderScope::vertices, never());

            renderer.replaceScene(1, scene);
            renderer.prepare(camera, null);
            renderer.composite(pass);
            renderer.prepare(camera, null);
            renderer.composite(pass);
            renderer.prepare(camera, null);
            renderer.composite(pass);

            shaders.verify(PortalShaderScope::rendering, times(1));
            assertFalse(renderer.available(1));
            assertTrue(renderer.debugLine().contains("gpu=1/0"));
            assertTrue(renderer.debugLine().contains("unavailable=1"));
            verifyNoInteractions(pass);
            system.verify(() -> RenderSystem.setShaderFog(null), times(4));
            renderer.retryUnavailable(System.nanoTime() + ClientPortalRenderer.RETRY_NANOS + 1L);
            assertTrue(renderer.available(1));
            assertTrue(renderer.debugLine().contains("unavailable=1"));
            renderer.prepare(camera, null);
            shaders.verify(PortalShaderScope::rendering, times(2));
            assertFalse(renderer.available(1));
            assertTrue(renderer.debugLine().contains("gpu=1/0"));
            renderer.remove(1);
            renderer.prepare(camera, null);
            shaders.verify(PortalShaderScope::rendering, times(2));
            renderer.replaceScene(1, scene);
            renderer.featureFailed(1, new IllegalStateException("Feature extraction unavailable"));
            assertFalse(renderer.available(1));
            renderer.retryUnavailable(System.nanoTime() + ClientPortalRenderer.RETRY_NANOS + 1L);
            renderer.featuresReady(1);
            assertTrue(renderer.available(1));
            assertTrue(renderer.debugLine().contains("unavailable=0"));
        } finally {
            renderer.clear();
        }
    }

    @Test
    public void failedDestinationDisposesItsInterruptedPipelineBeforeTheCooldownRetry() throws ReflectiveOperationException {
        ClientPortalRenderer renderer = ClientPortalRenderer.instance();
        renderer.clear();
        PortalShaderRenderer shaders = mock(PortalShaderRenderer.class);
        Field pool = ClientPortalRenderer.class.getDeclaredField("shaderRenderer");
        pool.setAccessible(true);
        pool.set(renderer, shaders);
        try {
            renderer.replaceScene(1, scene());
            renderer.replaceScene(2, scene());
            clearInvocations(shaders);
            renderer.featureFailed(1, new IllegalStateException("Destination draw interrupted"));
            verify(shaders).discard(1);
            verify(shaders, never()).discard(2);
            assertFalse(renderer.available(1));
            assertTrue(renderer.available(2));
            renderer.featureFailed(1, new IllegalStateException("Repeated stale callback"));
            verify(shaders).discard(1);
            renderer.retryUnavailable(System.nanoTime() + ClientPortalRenderer.RETRY_NANOS + 1L);
            assertTrue(renderer.available(1));
            verify(shaders).discard(1);
        } finally {
            renderer.clear();
        }
    }

    @Test
    public void readyShaderPrewarmLeavesSectionMaintenanceToTheVisibleRenderPass() throws ReflectiveOperationException {
        ClientPortalRenderer renderer = ClientPortalRenderer.instance();
        renderer.clear();
        PortalScene scene = scene();
        when(scene.environment()).thenReturn(PortalEnvironmentTest.environment(OpticTransform.IDENTITY));
        long behindCamera = SectionPos.asLong(0, 0, 1000);
        when(scene.sectionKeys()).thenReturn(LongArrayList.of(behindCamera));
        PortalShaderRenderer shaders = mock(PortalShaderRenderer.class);
        PortalShaderRenderer.Session session = mock(PortalShaderRenderer.Session.class);
        when(shaders.acquire(anyInt(), any(), anyInt(), anyInt())).thenReturn(session);
        when(session.ready()).thenReturn(true);
        when(session.materials()).thenReturn(PortalTerrainMaterials.VANILLA);
        renderer.replaceScene(1, scene);
        CameraRenderState camera = new CameraRenderState();
        camera.pos = Vec3.ZERO;
        camera.blockPos = BlockPos.ZERO;
        Field pool = ClientPortalRenderer.class.getDeclaredField("shaderRenderer");
        pool.setAccessible(true);
        pool.set(renderer, shaders);
        Field root = ClientPortalRenderer.class.getDeclaredField("rootCamera");
        root.setAccessible(true);
        root.set(renderer, camera);
        Field portals = ClientPortalRenderer.class.getDeclaredField("portals");
        portals.setAccessible(true);
        Object portal = ((Map<?, ?>) portals.get(renderer)).get(1);
        Class<?> dimensions = Class.forName(ClientPortalRenderer.class.getName() + "$RenderDimensions");
        Constructor<?> dimensionsConstructor = dimensions.getDeclaredConstructor(int.class, int.class, int.class);
        dimensionsConstructor.setAccessible(true);
        Method prewarm = ClientPortalRenderer.class.getDeclaredMethod("prewarmTree", portal.getClass(), Matrix4d.class, dimensions);
        prewarm.setAccessible(true);
        clearInvocations(scene);
        try {
            assertEquals(false, prewarm.invoke(renderer, portal, new Matrix4d(), dimensionsConstructor.newInstance(512, 256, 0)));
            assertTrue(renderer.available(1));
            verify(shaders, never()).remove(1);
            verify(scene, never()).sectionKeys();
        } finally {
            renderer.clear();
        }
    }

    @Test
    public void pendingShadersPrepareMeshesWithoutNativeCapturesAndRetainMaterialsWhenReady() throws ReflectiveOperationException {
        ClientPortalRenderer renderer = ClientPortalRenderer.instance();
        renderer.clear();
        PortalScene scene = scene();
        EnvironmentState viewEnvironment = PortalEnvironmentTest.environment(OpticTransform.IDENTITY);
        when(scene.environment()).thenReturn(viewEnvironment);
        renderer.replaceScene(1, scene);
        PortalScene childScene = scene(1);
        when(childScene.environment()).thenReturn(viewEnvironment);
        renderer.replaceScene(2, childScene);
        PortalScene siblingScene = scene();
        when(siblingScene.environment()).thenReturn(viewEnvironment);
        renderer.replaceScene(3, siblingScene);
        Field portals = ClientPortalRenderer.class.getDeclaredField("portals");
        portals.setAccessible(true);
        Object portal = ((Map<?, ?>) portals.get(renderer)).get(1);
        Object child = ((Map<?, ?>) portals.get(renderer)).get(2);
        Object sibling = ((Map<?, ?>) portals.get(renderer)).get(3);
        PortalShaderRenderer shaders = mock(PortalShaderRenderer.class);
        PortalShaderRenderer.Session session = mock(PortalShaderRenderer.Session.class);
        TextureTarget shaded = mock(TextureTarget.class);
        PortalTerrainMaterials materials = new PortalTerrainMaterials(true, Map.of(), 1, PortalTerrainMaterials.Lighting.VANILLA);
        when(session.materials()).thenReturn(materials);
        when(shaders.acquire(1, scene.environment(), 1920, 1080)).thenReturn(session);
        when(session.target()).thenReturn(shaded);
        PortalShaderRenderer.Session childSession = mock(PortalShaderRenderer.Session.class);
        when(childSession.materials()).thenReturn(materials);
        TextureTarget childTarget = mock(TextureTarget.class);
        when(shaders.acquire(2, childScene.environment(), 1920, 1080)).thenReturn(childSession);
        when(childSession.target()).thenReturn(childTarget);
        PortalShaderRenderer.Session siblingSession = mock(PortalShaderRenderer.Session.class);
        when(siblingSession.materials()).thenReturn(materials);
        TextureTarget siblingTarget = mock(TextureTarget.class);
        when(shaders.acquire(3, viewEnvironment, 1920, 1080)).thenReturn(siblingSession);
        when(siblingSession.target()).thenReturn(siblingTarget);
        set(renderer, "shaderRenderer", shaders);
        CameraRenderState camera = new CameraRenderState();
        camera.pos = Vec3.ZERO;
        camera.blockPos = BlockPos.ZERO;
        set(renderer, "camera", camera);
        Frustum frustum = mock(Frustum.class);
        when(frustum.isVisible(any(AABB.class))).thenReturn(true);
        set(portal, "cullFrustum", frustum);
        set(child, "cullFrustum", frustum);
        set(sibling, "cullFrustum", frustum);
        long section = SectionPos.asLong(0, 0, 0);
        when(scene.sectionKeys()).thenReturn(LongArrayList.of(section));
        when(scene.revision(section)).thenReturn(1L);
        set(portal, "apertureMesh", mock(PortalGpuMesh.class));
        set(portal, "apertureReady", true);
        PortalEnvironmentRenderer environment = mock(PortalEnvironmentRenderer.class);
        set(portal, "environment", environment);
        Class<?> dimensions = Class.forName(ClientPortalRenderer.class.getName() + "$RenderDimensions");
        Constructor<?> constructor = dimensions.getDeclaredConstructor(int.class, int.class, int.class);
        constructor.setAccessible(true);
        Object size = constructor.newInstance(1920, 1080, 0);
        set(portal, "uniformWidth", 1920);
        set(portal, "uniformHeight", 1080);
        set(child, "uniformWidth", 1920);
        set(child, "uniformHeight", 1080);
        set(child, "compositeUniform", mock(GpuBuffer.class));
        set(child, "apertureMesh", mock(PortalGpuMesh.class));
        set(child, "apertureReady", true);
        set(child, "environment", mock(PortalEnvironmentRenderer.class));
        set(sibling, "uniformWidth", 1920);
        set(sibling, "uniformHeight", 1080);
        set(sibling, "compositeUniform", mock(GpuBuffer.class));
        set(sibling, "apertureMesh", mock(PortalGpuMesh.class));
        set(sibling, "apertureReady", true);
        set(sibling, "environment", mock(PortalEnvironmentRenderer.class));
        Method prepare = ClientPortalRenderer.class.getDeclaredMethod("prepareDestination", portal.getClass(), dimensions);
        prepare.setAccessible(true);
        Method render = ClientPortalRenderer.class.getDeclaredMethod("renderPortal", portal.getClass(), dimensions);
        render.setAccessible(true);
        Method nextBuild = ClientPortalRenderer.class.getDeclaredMethod("nextBuildPortal", boolean.class, boolean.class, long.class);
        nextBuild.setAccessible(true);
        Method releaseTarget = ClientPortalRenderer.class.getDeclaredMethod("releaseTarget", portal.getClass());
        releaseTarget.setAccessible(true);
        try (MockedStatic<RenderSystem> system = mockStatic(RenderSystem.class, RETURNS_DEEP_STUBS);
             MockedStatic<Minecraft> game = mockStatic(Minecraft.class);
             MockedStatic<PortalClipScope> clipping = mockStatic(PortalClipScope.class);
             MockedStatic<PortalShaderScope> shaderScopes = mockStatic(PortalShaderScope.class);
             MockedStatic<PortalIrisTerrain> irisTerrain = mockStatic(PortalIrisTerrain.class);
             MockedConstruction<PortalShaderCamera> shaderCameras = mockConstruction(PortalShaderCamera.class,
                 (shaderCamera, context) -> when(shaderCamera.getViewRotationMatrix(any())).thenAnswer(invocation -> invocation.getArgument(0)))) {
            assertEquals(false, render.invoke(renderer, portal, size));
            assertSame(null, get(portal, "shader"));
            assertSame(shaded, get(portal, "target"));
            assertSame(null, get(portal, "compositeUniform"));
            assertSame(session, get(portal, "destination"));
            assertFalse((boolean) get(portal, "rendered"));
            assertSame(portal, nextBuild.invoke(renderer, false, true, Long.MAX_VALUE));
            assertTrue(((LongSet) get(portal, "dirty")).contains(section));
            assertEquals(false, render.invoke(renderer, sibling, size));
            assertSame(null, get(sibling, "shader"));
            assertSame(siblingTarget, get(sibling, "target"));
            assertSame(shaded, get(portal, "target"));
            assertSame(materials, get(portal, "materials"));
            int pendingGeneration = (int) get(portal, "generation");
            verify(session, never()).begin(any());
            assertEquals(false, render.invoke(renderer, child, constructor.newInstance(1920, 1080, 1)));
            assertSame(null, get(child, "shader"));
            assertSame(childTarget, get(child, "target"));
            assertSame(shaded, get(portal, "target"));
            system.verifyNoInteractions();
            verify(environment).endFrame();
            verify(environment, never()).renderSky(any());
            verify(session, never()).sky();
            verify(childSession, never()).begin(any());
            verify(childSession, never()).sky();
            verify(siblingSession, never()).sky();
            when(childSession.ready()).thenReturn(true);
            prepare.invoke(renderer, child, constructor.newInstance(1920, 1080, 1));
            assertSame(childSession, get(child, "shader"));
            assertSame(childTarget, get(child, "target"));
            assertSame(null, get(portal, "shader"));
            when(session.ready()).thenReturn(true);
            set(portal, "compositeUniform", mock(GpuBuffer.class));
            prepare.invoke(renderer, portal, size);
            assertSame(session, get(portal, "shader"));
            assertSame(shaded, get(portal, "target"));
            assertSame(materials, get(portal, "materials"));
            assertEquals(pendingGeneration, get(portal, "generation"));
            verify(shaders, times(2)).acquire(1, scene.environment(), 1920, 1080);
            verify(environment, times(2)).prepare(scene.environment(), camera);
            set(child, "active", false);
            set(renderer, "shaderSizes", List.of(new PortalShaderRenderer.Resolution(1920, 1080),
                new PortalShaderRenderer.Resolution(1920, 1080)));
            set(portal, "camera", camera);
            set(portal, "contentCamera", camera);
            set(portal, "viewport", new PortalViewport(0, 0, 1920, 1080));
            PortalFeatureRenderer features = mock(PortalFeatureRenderer.class);
            ((PortalFeatureRenderer[]) get(get(renderer, "targets"), "features"))[0] = features;
            ((ProjectionMatrixBuffer[]) get(get(renderer, "targets"), "projections"))[0] = mock(ProjectionMatrixBuffer.class);
            Minecraft minecraft = mock(Minecraft.class, RETURNS_DEEP_STUBS);
            doReturn(null).when(minecraft).getCameraEntity();
            game.when(Minecraft::getInstance).thenReturn(minecraft);
            system.when(RenderSystem::getModelViewStack).thenReturn(new Matrix4fStack(8));
            SkyRenderer sky = mock(SkyRenderer.class);
            when(session.sky()).thenReturn(sky);
            when(session.begin(any())).thenReturn(mock(PortalShaderRenderer.Frame.class));
            assertEquals(true, render.invoke(renderer, portal, size));
            verify(session).begin(argThat(view -> view.target() == shaded && view.environment() == viewEnvironment));
            verify(session).prepare();
            verify(session).terrain(ChunkSectionLayer.SOLID, false);
            verify(session).terrain(ChunkSectionLayer.CUTOUT, false);
            verify(session).terrain(ChunkSectionLayer.TRANSLUCENT, false);
            verify(session).finish();
            verify(environment).renderSky(sky);
            assertSame(materials, get(portal, "materials"));
            assertEquals(pendingGeneration, get(portal, "generation"));
            releaseTarget.invoke(null, sibling);
            assertSame(null, get(sibling, "destination"));
            assertSame(null, get(sibling, "target"));
            assertSame(session, get(portal, "destination"));
            verify(siblingSession, never()).begin(any());
        } finally {
            renderer.clear();
        }
    }

    @Test
    public void pendingRootAndNestedShaderViewsReturnWithoutMarkingCapturesComposable() throws ReflectiveOperationException {
        ClientPortalRenderer renderer = ClientPortalRenderer.instance();
        renderer.clear();
        EnvironmentState environment = PortalEnvironmentTest.environment(OpticTransform.IDENTITY);
        PortalShaderRenderer shaders = mock(PortalShaderRenderer.class);
        PortalShaderRenderer.Session session = mock(PortalShaderRenderer.Session.class);
        when(shaders.acquire(anyInt(), any(), anyInt(), anyInt())).thenReturn(session);
        when(session.materials()).thenReturn(PortalTerrainMaterials.VANILLA);
        set(renderer, "shaderRenderer", shaders);
        CameraRenderState camera = new CameraRenderState();
        camera.pos = new Vec3(1, 1, 3);
        camera.blockPos = BlockPos.containing(camera.pos);
        set(renderer, "rootCamera", camera);
        GpuDevice device = mock(GpuDevice.class, RETURNS_DEEP_STUBS);
        PortalViewport viewport = mock(PortalViewport.class);
        when(viewport.frustum(any(), any(), anyInt(), anyInt())).thenReturn(mock(Frustum.class));
        Class<?> dimensions = Class.forName(ClientPortalRenderer.class.getName() + "$RenderDimensions");
        Constructor<?> constructor = dimensions.getDeclaredConstructor(int.class, int.class, int.class);
        constructor.setAccessible(true);
        Class<?> portalType = Class.forName(ClientPortalRenderer.class.getName() + "$Portal");
        Method render = ClientPortalRenderer.class.getDeclaredMethod("renderTree", portalType, Matrix4d.class,
            PortalViewport.class, dimensions);
        render.setAccessible(true);
        try (MockedStatic<RenderSystem> system = mockStatic(RenderSystem.class);
             MockedStatic<PortalViewport> viewports = mockStatic(PortalViewport.class)) {
            system.when(RenderSystem::getDevice).thenReturn(device);
            viewports.when(() -> PortalViewport.coverage(any(), any(), anyInt(), anyInt(), any(Boolean.class)))
                .thenReturn(viewport);
            for (int key = 1; key <= 2; key++) {
                PortalScene scene = scene(key == 1 ? 0 : 1);
                when(scene.environment()).thenReturn(environment);
                renderer.replaceScene(key, scene);
                Object portal = ((Map<?, ?>) get(renderer, "portals")).get(key);
                set(portal, "uniformWidth", 1920);
                set(portal, "uniformHeight", 1080);
                set(portal, "compositeUniform", mock(GpuBuffer.class));
                PortalGpuMesh aperture = mock(PortalGpuMesh.class);
                set(portal, "apertureMesh", aperture);
                set(portal, "apertureReady", true);
                PortalEnvironmentRenderer destination = mock(PortalEnvironmentRenderer.class);
                set(portal, "environment", destination);
                assertEquals(false, render.invoke(renderer, portal, new Matrix4d(), null,
                    constructor.newInstance(1920, 1080, key - 1)));
                assertFalse((boolean) get(portal, "rendered"));
                assertFalse((boolean) get(portal, "rendering"));
                verify(destination).prepare(environment, camera);
                verify(destination).endFrame();
                verify(aperture, never()).draw(any());
            }
            verify(device, never()).createCommandEncoder();
            verify(session, never()).begin(any());
            verify(session, never()).sky();
        } finally {
            renderer.clear();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void sceneSideRefreshRetainsGpuSectionsAndOnlyResetsClippingAndHistory() throws ReflectiveOperationException {
        ClientPortalRenderer renderer = ClientPortalRenderer.instance();
        renderer.clear();
        PortalScene original = scene();
        renderer.replaceScene(1, original);
        Object portal = ((Map<?, ?>) get(renderer, "portals")).get(1);
        PortalShaderRenderer shaders = mock(PortalShaderRenderer.class);
        set(renderer, "shaderRenderer", shaders);
        long key = SectionPos.asLong(0, 4, 0);
        Class<?> sectionType = Class.forName(ClientPortalRenderer.class.getName() + "$Section");
        Constructor<?> constructor = sectionType.getDeclaredConstructor(long.class, long.class);
        constructor.setAccessible(true);
        Object section = constructor.newInstance(key, 7L);
        PortalGpuMesh mesh = mock(PortalGpuMesh.class);
        ((EnumMap<ChunkSectionLayer, PortalGpuMesh>) get(section, "layers")).put(ChunkSectionLayer.CUTOUT, mesh);
        GpuBuffer sectionClip = mock(GpuBuffer.class);
        GpuBuffer parentClip = mock(GpuBuffer.class);
        PortalGpuMesh aperture = mock(PortalGpuMesh.class);
        set(section, "clip", sectionClip);
        set(portal, "parentClip", parentClip);
        set(portal, "apertureMesh", aperture);
        set(portal, "apertureReady", true);
        ((Long2ObjectOpenHashMap<Object>) get(portal, "sections")).put(key, section);
        int generation = (int) get(portal, "generation");
        try {
            PortalScene rebound = scene();
            renderer.refreshScene(1, rebound, false);
            assertSame(portal, ((Map<?, ?>) get(renderer, "portals")).get(1));
            assertSame(rebound, get(portal, "scene"));
            assertSame(section, ((Long2ObjectOpenHashMap<Object>) get(portal, "sections")).get(key));
            verifyNoInteractions(shaders);
            PortalScene reboundLevel = scene();
            renderer.refreshScene(1, reboundLevel, true);
            assertSame(reboundLevel, get(portal, "scene"));
            assertSame(section, ((Long2ObjectOpenHashMap<Object>) get(portal, "sections")).get(key));
            assertSame(sectionClip, get(section, "clip"));
            assertSame(parentClip, get(portal, "parentClip"));
            assertSame(aperture, get(portal, "apertureMesh"));
            verify(shaders).resetHistory(1);
            verify(mesh, never()).close();
            PortalScene back = scene(0, false);
            renderer.refreshScene(1, back, false);
            assertSame(back, get(portal, "scene"));
            assertSame(back.geometry(), get(portal, "geometry"));
            assertEquals(generation, get(portal, "generation"));
            assertSame(section, ((Long2ObjectOpenHashMap<Object>) get(portal, "sections")).get(key));
            assertEquals(7L, get(section, "revision"));
            assertSame(null, get(section, "clip"));
            verify(sectionClip).close();
            verify(parentClip).close();
            verify(aperture).close();
            verify(mesh, never()).close();
            verify(shaders, times(2)).resetHistory(1);
            verify(shaders, never()).remove(1);
            renderer.invalidate(1, key, true);
            assertTrue(((LongSet) get(portal, "dirty")).contains(key));
            verify(mesh, never()).close();
        } finally {
            renderer.clear();
        }
        verify(mesh).close();
    }

    @Test
    @SuppressWarnings("unchecked")
    public void changedResidentSectionIsSelectedBeforeItsQueuedNeighborhood() throws ReflectiveOperationException {
        ClientPortalRenderer renderer = ClientPortalRenderer.instance();
        renderer.clear();
        PortalScene scene = scene();
        when(scene.revision(anyLong())).thenReturn(2L);
        renderer.replaceScene(1, scene);
        Object portal = ((Map<?, ?>) get(renderer, "portals")).get(1);
        Frustum frustum = mock(Frustum.class);
        when(frustum.isVisible(any(AABB.class))).thenReturn(true);
        set(portal, "cullFrustum", frustum);
        CameraRenderState camera = new CameraRenderState();
        camera.pos = Vec3.ZERO;
        set(renderer, "camera", camera);
        Class<?> sectionType = Class.forName(ClientPortalRenderer.class.getName() + "$Section");
        Constructor<?> constructor = sectionType.getDeclaredConstructor(long.class, long.class);
        constructor.setAccessible(true);
        Long2ObjectOpenHashMap<Object> sections = (Long2ObjectOpenHashMap<Object>) get(portal, "sections");
        long center = SectionPos.asLong(0, 0, 0);
        Method select = ClientPortalRenderer.class.getDeclaredMethod("selectNextSection", portal.getClass());
        select.setAccessible(true);
        try {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    for (int x = -1; x <= 1; x++) {
                        long key = SectionPos.asLong(x, y, z);
                        sections.put(key, constructor.newInstance(key, 1L));
                        renderer.invalidate(1, key, false);
                    }
                }
            }
            renderer.invalidate(1, center, true);
            renderer.invalidate(1, center, true);
            renderer.invalidate(1, SectionPos.asLong(-1, -1, -1), false);
            assertEquals(27, ((LongSet) get(portal, "dirty")).size());
            assertEquals(true, select.invoke(renderer, portal));
            assertEquals(center, get(portal, "nextSection"));
            ((LongSet) get(portal, "dirty")).remove(center);
            assertEquals(true, select.invoke(renderer, portal));
            assertEquals(SectionPos.asLong(-1, -1, -1), get(portal, "nextSection"));
        } finally {
            renderer.clear();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void nestedVisibleResidentUpdatesBeatThousandsOfNearerInitialBuildsWithoutStarvingThem() throws ReflectiveOperationException {
        ClientPortalRenderer renderer = ClientPortalRenderer.instance();
        renderer.clear();
        PortalScene scene = scene(1);
        when(scene.revision(anyLong())).thenReturn(2L);
        renderer.replaceScene(2, scene);
        Object portal = ((Map<?, ?>) get(renderer, "portals")).get(2);
        Frustum frustum = mock(Frustum.class);
        when(frustum.isVisible(any(AABB.class))).thenReturn(true);
        set(portal, "cullFrustum", frustum);
        CameraRenderState camera = new CameraRenderState();
        camera.pos = Vec3.ZERO;
        camera.cullFrustum = mock(Frustum.class);
        set(renderer, "camera", camera);
        LongSet dirty = (LongSet) get(portal, "dirty");
        for (int section = 0; section < 9000; section++) {
            dirty.add(SectionPos.asLong(section, 0, 0));
        }
        long changed = SectionPos.asLong(10000, 0, 0);
        Class<?> sectionType = Class.forName(ClientPortalRenderer.class.getName() + "$Section");
        Constructor<?> constructor = sectionType.getDeclaredConstructor(long.class, long.class);
        constructor.setAccessible(true);
        ((Long2ObjectOpenHashMap<Object>) get(portal, "sections")).put(changed, constructor.newInstance(changed, 1L));
        renderer.invalidate(2, changed, true);
        Method select = ClientPortalRenderer.class.getDeclaredMethod("selectNextSection", portal.getClass());
        select.setAccessible(true);
        try {
            for (int rebuild = 0; rebuild < 3; rebuild++) {
                set(renderer, "residentBuilds", rebuild);
                assertEquals(true, select.invoke(renderer, portal));
                assertEquals(changed, get(portal, "nextSection"));
                assertEquals(rebuild, get(renderer, "residentBuilds"));
            }
            set(renderer, "residentBuilds", 3);
            assertEquals(true, select.invoke(renderer, portal));
            assertEquals(SectionPos.asLong(0, 0, 0), get(portal, "nextSection"));
            set(renderer, "residentBuilds", 0);
            assertEquals(true, select.invoke(renderer, portal));
            assertEquals(changed, get(portal, "nextSection"));
        } finally {
            renderer.clear();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void cheapEmptySectionsDoNotUseAsyncBuilderSlotsAndRemainBounded() throws ReflectiveOperationException {
        ClientPortalRenderer renderer = ClientPortalRenderer.instance();
        renderer.clear();
        CameraRenderState camera = new CameraRenderState();
        camera.pos = Vec3.ZERO;
        camera.blockPos = BlockPos.ZERO;
        set(renderer, "camera", camera);
        LongArrayList keys = new LongArrayList(200);
        for (int index = 0; index < 200; index++) {
            keys.add(SectionPos.asLong(index, 0, 0));
        }
        PortalScene scene = scene();
        when(scene.sectionKeys()).thenReturn(keys);
        when(scene.revision(anyLong())).thenReturn(1L);
        when(scene.empty(anyLong())).thenReturn(true);
        renderer.replaceScene(1, scene);
        Object portal = ((Map<?, ?>) get(renderer, "portals")).get(1);
        Frustum frustum = mock(Frustum.class);
        when(frustum.isVisible(any(AABB.class))).thenReturn(true);
        set(portal, "camera", camera);
        set(portal, "contentCamera", camera);
        set(portal, "cullFrustum", frustum);
        Class<?> portalType = Class.forName(ClientPortalRenderer.class.getName() + "$Portal");
        Method maintain = ClientPortalRenderer.class.getDeclaredMethod("maintain", portalType);
        Method dispatch = ClientPortalRenderer.class.getDeclaredMethod("dispatchBuilds", long.class);
        maintain.setAccessible(true);
        dispatch.setAccessible(true);
        try {
            maintain.invoke(renderer, portal);
            dispatch.invoke(renderer, 0L);
            assertTrue(((Map<?, ?>) get(portal, "sections")).isEmpty());
            dispatch.invoke(renderer, Long.MAX_VALUE);
            assertEquals(200, ((Map<?, ?>) get(portal, "sections")).size());
            assertEquals(0, get(renderer, "pendingBuilds"));
            assertTrue(((LongSet) get(portal, "building")).isEmpty());
            assertTrue(((LongSet) get(portal, "dirty")).isEmpty());
        } finally {
            renderer.clear();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void childFirstCollectionCannotConsumeTheSharedSlotsBeforeParentUpdatesRegister() throws ReflectiveOperationException {
        ClientPortalRenderer renderer = ClientPortalRenderer.instance();
        renderer.clear();
        int[] order = {8, 9, 7, 1};
        CameraRenderState camera = new CameraRenderState();
        camera.pos = Vec3.ZERO;
        camera.blockPos = BlockPos.ZERO;
        set(renderer, "camera", camera);
        Class<?> portalType = Class.forName(ClientPortalRenderer.class.getName() + "$Portal");
        Class<?> sectionType = Class.forName(ClientPortalRenderer.class.getName() + "$Section");
        Constructor<?> constructor = sectionType.getDeclaredConstructor(long.class, long.class);
        constructor.setAccessible(true);
        Method maintain = ClientPortalRenderer.class.getDeclaredMethod("maintain", portalType);
        maintain.setAccessible(true);
        Method next = ClientPortalRenderer.class.getDeclaredMethod("nextBuildPortal", boolean.class, boolean.class, long.class);
        next.setAccessible(true);
        Method dispatch = ClientPortalRenderer.class.getDeclaredMethod("dispatchBuilds");
        dispatch.setAccessible(true);
        try {
            for (int key : order) {
                long resident = SectionPos.asLong(key, 0, 0);
                long initial = SectionPos.asLong(-key, 0, 0);
                PortalScene scene = scene(key == 1 ? 0 : 1);
                when(scene.revision(anyLong())).thenReturn(2L);
                when(scene.sectionKeys()).thenReturn(new LongArrayList(new long[] {resident, initial}));
                renderer.replaceScene(key, scene);
                Object portal = ((Map<?, ?>) get(renderer, "portals")).get(key);
                Frustum frustum = mock(Frustum.class);
                when(frustum.isVisible(any(AABB.class))).thenReturn(true);
                set(portal, "camera", camera);
                set(portal, "contentCamera", camera);
                set(portal, "cullFrustum", frustum);
                ((Long2ObjectOpenHashMap<Object>) get(portal, "sections")).put(resident,
                    constructor.newInstance(resident, 1L));
                renderer.invalidate(key, resident, true);
                maintain.invoke(renderer, portal);
                assertEquals(0, get(renderer, "pendingBuilds"));
                assertTrue(((LongSet) get(portal, "building")).isEmpty());
                set(portal, "rendered", true);
            }
            assertEquals(4, ((List<?>) get(renderer, "buildDemand")).size());
            assertEquals(8, get(next.invoke(renderer, true, true, Long.MAX_VALUE), "key"));
            set(renderer, "lastBuildPortal", 8);
            assertEquals(9, get(next.invoke(renderer, true, true, Long.MAX_VALUE), "key"));
            set(renderer, "lastBuildPortal", 9);
            set(renderer, "pendingBuilds", 2);
            dispatch.invoke(renderer);
            for (Object portal : ((Map<?, ?>) get(renderer, "portals")).values()) {
                assertTrue(((LongSet) get(portal, "building")).isEmpty());
            }
            set(renderer, "pendingBuilds", 0);
            assertEquals(7, get(next.invoke(renderer, true, true, Long.MAX_VALUE), "key"));
            set(renderer, "lastBuildPortal", 7);
            assertEquals(1, get(next.invoke(renderer, true, true, Long.MAX_VALUE), "key"));
            set(renderer, "residentBuilds", 3);
            assertEquals(1, get(next.invoke(renderer, false, true, Long.MAX_VALUE), "key"));
            assertEquals(3, get(renderer, "residentBuilds"));
        } finally {
            set(renderer, "pendingBuilds", 0);
            renderer.clear();
        }
        assertTrue(((List<?>) get(renderer, "buildDemand")).isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void residentInvalidationDuringBuildRemainsQueuedAndResumesAheadOfInitialWork() throws ReflectiveOperationException {
        ClientPortalRenderer renderer = ClientPortalRenderer.instance();
        renderer.clear();
        PortalScene scene = scene();
        when(scene.revision(anyLong())).thenReturn(3L);
        renderer.replaceScene(1, scene);
        Object portal = ((Map<?, ?>) get(renderer, "portals")).get(1);
        Frustum frustum = mock(Frustum.class);
        when(frustum.isVisible(any(AABB.class))).thenReturn(true);
        set(portal, "cullFrustum", frustum);
        CameraRenderState camera = new CameraRenderState();
        camera.pos = Vec3.ZERO;
        set(renderer, "camera", camera);
        long changed = SectionPos.asLong(20, 0, 0);
        long initial = SectionPos.asLong(0, 0, 0);
        Class<?> sectionType = Class.forName(ClientPortalRenderer.class.getName() + "$Section");
        Constructor<?> constructor = sectionType.getDeclaredConstructor(long.class, long.class);
        constructor.setAccessible(true);
        ((Long2ObjectOpenHashMap<Object>) get(portal, "sections")).put(changed, constructor.newInstance(changed, 1L));
        LongSet dirty = (LongSet) get(portal, "dirty");
        LongSet building = (LongSet) get(portal, "building");
        building.add(changed);
        dirty.add(initial);
        renderer.invalidate(1, changed, true);
        renderer.invalidate(1, changed, true);
        Method select = ClientPortalRenderer.class.getDeclaredMethod("selectNextSection", portal.getClass());
        select.setAccessible(true);
        try {
            assertEquals(true, select.invoke(renderer, portal));
            assertEquals(initial, get(portal, "nextSection"));
            assertTrue(dirty.contains(changed));
            assertEquals(2, dirty.size());
            PortalSectionMesh mesh = mock(PortalSectionMesh.class);
            when(mesh.meshes()).thenReturn(Map.of());
            set(renderer, "pendingBuilds", 1);
            Method finish = ClientPortalRenderer.class.getDeclaredMethod("finish", portal.getClass(), long.class, long.class,
                int.class, PortalScene.MeshIdentity.class, PortalSectionMesh.class, Throwable.class);
            finish.setAccessible(true);
            finish.invoke(renderer, portal, changed, 2L, get(portal, "generation"), null, mesh, null);
            assertEquals(0, get(renderer, "pendingBuilds"));
            assertFalse(building.contains(changed));
            assertTrue(dirty.contains(changed));
            assertEquals(true, select.invoke(renderer, portal));
            assertEquals(changed, get(portal, "nextSection"));
        } finally {
            renderer.clear();
        }
    }

    @Test
    public void sourceDimensionRebuildPreservesTheSamePackButResourceReloadStillInvalidates() throws ReflectiveOperationException {
        ClientPortalRenderer renderer = ClientPortalRenderer.instance();
        renderer.clear();
        PortalScene scene = scene();
        when(scene.sectionKeys()).thenReturn(new LongArrayList());
        renderer.replaceScene(1, scene);
        PortalShaderRenderer shaders = mock(PortalShaderRenderer.class);
        Object pack = new Object();
        when(shaders.usesPack(pack)).thenReturn(true);
        set(renderer, "shaderRenderer", shaders);
        try {
            renderer.sourcePipelineDestroying(pack);
            assertSame(shaders, get(renderer, "shaderRenderer"));
            verify(shaders, never()).close();
            renderer.sourcePipelineDestroying(new Object());
            assertSame(null, get(renderer, "shaderRenderer"));
            verify(shaders).close();
            PortalShaderRenderer resources = mock(PortalShaderRenderer.class);
            when(resources.usesPack(pack)).thenReturn(true);
            set(renderer, "shaderRenderer", resources);
            renderer.resourceReload();
            assertSame(null, get(renderer, "shaderRenderer"));
            verify(resources).close();
            PortalShaderRenderer disabled = mock(PortalShaderRenderer.class);
            set(renderer, "shaderRenderer", disabled);
            renderer.sourcePipelineDestroying(null);
            assertSame(null, get(renderer, "shaderRenderer"));
            verify(disabled).close();
        } finally {
            renderer.clear();
        }
    }

    @Test
    public void reflectedCameraCullsInItsTransformedWorldSpace() {
        CameraRenderState root = new CameraRenderState();
        root.pos = new Vec3(778, 192, 12);
        root.projectionMatrix = new Matrix4f().setPerspective((float) Math.toRadians(70), 16.0f / 9.0f, 200, 0.05f, false);
        CameraRenderState reflected = ClientPortalRenderer.transformedCamera(root,
            new Matrix4d().m22(-1).m32(17), root.projectionMatrix);
        assertEquals(new Vec3(778, 192, 5), reflected.pos);
        assertTrue(reflected.cullFrustum.isVisible(new AABB(777, 191, 27, 779, 194, 29)));
        assertFalse(reflected.cullFrustum.isVisible(new AABB(777, 191, -28, 779, 194, -26)));
    }

    @Test
    public void terrainFogUsesCameraRelativePositionsAcrossSectionBoundaries() {
        CameraRenderState camera = new CameraRenderState();
        camera.pos = new Vec3(6.5, 125.62, 8.5);
        camera.viewRotationMatrix = new Matrix4f().rotateX((float) (Math.PI / 2.0));
        DynamicGpuData.Transform near = ClientPortalRenderer.terrainTransform(camera, SectionPos.asLong(0, 0, 0));
        DynamicGpuData.Transform far = ClientPortalRenderer.terrainTransform(camera, SectionPos.asLong(0, -1, 0));
        Vector3f nearBoundary = new Vector3f(7, 0, 9).add(near.modelOffset());
        Vector3f farBoundary = new Vector3f(7, 16, 9).add(far.modelOffset());
        assertEquals(new Vector3f(0.5f, -125.62f, 0.5f), nearBoundary);
        assertEquals(nearBoundary.x, farBoundary.x, 0.00002f);
        assertEquals(nearBoundary.y, farBoundary.y, 0.00002f);
        assertEquals(nearBoundary.z, farBoundary.z, 0.00002f);
        assertEquals(camera.viewRotationMatrix, near.modelView());
        Vector3f transformed = near.modelView().transformPosition(new Vector3f(nearBoundary));
        assertEquals(0.5f, transformed.x, 0.0001f);
        assertEquals(-0.5f, transformed.y, 0.0001f);
        assertEquals(-125.62f, transformed.z, 0.0001f);
    }

    @Test
    public void shaderTerrainSectionTranslationIsPartOfTheModelViewMatrix() {
        CameraRenderState camera = new CameraRenderState();
        camera.pos = new Vec3(520.5, 81, 14.5);
        camera.viewRotationMatrix = new Matrix4f().rotateY((float) Math.PI / 2);
        long section = SectionPos.asLong(32, 5, 0);
        DynamicGpuData.Transform vanilla = ClientPortalRenderer.terrainTransform(camera, section);
        DynamicGpuData.Transform shader = ClientPortalRenderer.shaderTerrainTransform(camera, section);
        Vector3f vertex = new Vector3f(8, 1, 4);
        Vector3f expected = vanilla.modelView().transformPosition(new Vector3f(vertex).add(vanilla.modelOffset()));
        Vector3f actual = shader.modelView().transformPosition(new Vector3f(vertex));
        assertEquals(expected.x, actual.x, 0.0001f);
        assertEquals(expected.y, actual.y, 0.0001f);
        assertEquals(expected.z, actual.z, 0.0001f);
        assertEquals(new Vector3f(), shader.modelOffset());
    }

    @Test
    public void rootCompositeUploadsDisabledClipPlaneAndNativePixelViewport() {
        GpuDevice device = mock(GpuDevice.class, RETURNS_DEEP_STUBS);
        GpuBuffer buffer = mock(GpuBuffer.class);
        when(device.createBuffer(any(), anyInt(), any(ByteBuffer.class))).thenAnswer(invocation -> {
            ByteBuffer data = invocation.getArgument(2);
            assertEquals(48, data.remaining());
            for (int component = 0; component < 4; component++) {
                assertEquals(0.0f, data.getFloat(component * Float.BYTES), 0.0f);
            }
            assertEquals(768.0f, data.getFloat(16), 0.0f);
            assertEquals(1080.0f, data.getFloat(20), 0.0f);
            assertEquals(576.0f, data.getFloat(24), 0.0f);
            assertEquals(0.0f, data.getFloat(28), 0.0f);
            assertEquals(device.getDeviceInfo().isZZeroToOne() ? 1.0f : 0.5f, data.getFloat(32), 0.0f);
            assertEquals(device.getDeviceInfo().isZZeroToOne() ? 0.0f : 0.5f, data.getFloat(36), 0.0f);
            return buffer;
        });
        try (MockedStatic<RenderSystem> system = mockStatic(RenderSystem.class)) {
            system.when(RenderSystem::getDevice).thenReturn(device);
            assertSame(buffer, ClientPortalRenderer.compositeUniform(new PortalViewport(576, 0, 768, 1080)));
            when(device.getDeviceInfo().isZZeroToOne()).thenReturn(true);
            assertSame(buffer, ClientPortalRenderer.compositeUniform(new PortalViewport(576, 0, 768, 1080)));
        }
    }
    private static Object get(Object owner, String name) throws ReflectiveOperationException {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static void set(Object owner, String name, Object value) throws ReflectiveOperationException {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }

    private static Constructor<?> sectionConstructor() throws ReflectiveOperationException {
        Class<?> sectionType = Class.forName(ClientPortalRenderer.class.getName() + "$Section");
        Constructor<?> constructor = sectionType.getDeclaredConstructor(long.class, long.class);
        constructor.setAccessible(true);
        return constructor;
    }

    private static void finish(ClientPortalRenderer renderer, Object portal, long key, long revision) throws ReflectiveOperationException {
        PortalSectionMesh mesh = mock(PortalSectionMesh.class);
        when(mesh.meshes()).thenReturn(Map.of());
        Method finish = ClientPortalRenderer.class.getDeclaredMethod("finish", portal.getClass(), long.class, long.class,
            int.class, PortalScene.MeshIdentity.class, PortalSectionMesh.class, Throwable.class);
        finish.setAccessible(true);
        finish.invoke(renderer, portal, key, revision, get(portal, "generation"), null, mesh, null);
    }

    @SuppressWarnings("unchecked")
    private static Object drawableSection(Constructor<?> constructor, long key) throws ReflectiveOperationException {
        Object section = constructor.newInstance(key, 1L);
        Map<ChunkSectionLayer, PortalGpuMesh> layers = (Map<ChunkSectionLayer, PortalGpuMesh>) get(section, "layers");
        layers.put(ChunkSectionLayer.SOLID, mock(PortalGpuMesh.class));
        return section;
    }

    private static PortalScene scene() {
        return scene(0);
    }

    private static PortalScene scene(int parent) {
        return scene(parent, true);
    }

    private static PortalScene scene(int parent, boolean front) {
        PortalScene scene = mock(PortalScene.class);
        ApertureCells aperture = new ApertureCells();
        aperture.setArea(new Box(0, 1.999, 0, 1.999, 0, 0.999));
        ApertureDescriptor geometry = ApertureDescriptor.fromPortal(new ApertureDescriptor.Source(aperture,
            Frame.canonical(Face.S), front, false, 0, 0, 0, 0, 64, 0,
            ApertureDescriptor.BLACKOUT_OFF, 0, ApertureDescriptor.MASK_AIR_PROJECT,
            BlockClaim.LightingPolicy.LOCAL, 0, ApertureKind.FRAME, 0.0D, parent, 0, ShapeDescriptor.FULL, List.of())).orElseThrow();
        when(scene.geometry()).thenReturn(geometry);
        when(scene.sectionKeys()).thenReturn(new LongArrayList());
        return scene;
    }

}
