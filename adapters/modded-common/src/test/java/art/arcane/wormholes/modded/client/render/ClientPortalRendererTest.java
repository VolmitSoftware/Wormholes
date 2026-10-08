package art.arcane.wormholes.modded.client.render;

import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.OpticTransform;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongSet;
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
import net.minecraft.client.renderer.DynamicGpuData;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.SectionPos;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.MockedConstruction;

import java.nio.ByteBuffer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Constructor;
import java.util.Map;
import java.util.EnumMap;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
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

    private static void finish(ClientPortalRenderer renderer, Object portal, long key, long revision) throws ReflectiveOperationException {
        PortalSectionMesh mesh = mock(PortalSectionMesh.class);
        when(mesh.meshes()).thenReturn(Map.of());
        Method finish = ClientPortalRenderer.class.getDeclaredMethod("finish", portal.getClass(), long.class, long.class,
            int.class, PortalScene.MeshIdentity.class, PortalSectionMesh.class, Throwable.class);
        finish.setAccessible(true);
        finish.invoke(renderer, portal, key, revision, get(portal, "generation"), null, mesh, null);
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
