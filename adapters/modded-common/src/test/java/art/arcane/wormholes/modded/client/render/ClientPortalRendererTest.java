package art.arcane.wormholes.modded.client.render;

import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.network.client.ClientViewEnvironment;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import art.arcane.wormholes.portal.PortalGeometry;
import art.arcane.wormholes.render.ProjectedBlockClaim;
import art.arcane.wormholes.render.client.ClientPortalGeometry;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import net.minecraft.client.renderer.DynamicGpuData;
import net.minecraft.client.renderer.SkyRenderer;
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

import java.nio.ByteBuffer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import java.util.EnumMap;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;

public class ClientPortalRendererTest {
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
        when(scene.environment()).thenReturn(PortalEnvironmentTest.environment(ClientViewEnvironment.Transform.IDENTITY));
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
    public void pendingShaderUsesItsPrivateTargetForNativePreviewThenAdoptsTheSameReadySession() throws ReflectiveOperationException {
        ClientPortalRenderer renderer = ClientPortalRenderer.instance();
        renderer.clear();
        PortalScene scene = scene();
        ClientViewEnvironment viewEnvironment = PortalEnvironmentTest.environment(ClientViewEnvironment.Transform.IDENTITY);
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
        when(shaders.acquire(1, scene.environment(), 1920, 1080)).thenReturn(session);
        when(session.target()).thenReturn(shaded);
        SkyRenderer pendingSky = mock(SkyRenderer.class);
        when(session.sky()).thenReturn(pendingSky);
        PortalShaderRenderer.Session childSession = mock(PortalShaderRenderer.Session.class);
        TextureTarget childTarget = mock(TextureTarget.class);
        when(shaders.acquire(2, childScene.environment(), 1920, 1080)).thenReturn(childSession);
        when(childSession.target()).thenReturn(childTarget);
        PortalShaderRenderer.Session siblingSession = mock(PortalShaderRenderer.Session.class);
        TextureTarget siblingTarget = mock(TextureTarget.class);
        SkyRenderer siblingSky = mock(SkyRenderer.class);
        when(shaders.acquire(3, viewEnvironment, 1920, 1080)).thenReturn(siblingSession);
        when(siblingSession.target()).thenReturn(siblingTarget);
        when(siblingSession.sky()).thenReturn(siblingSky);
        set(renderer, "shaderRenderer", shaders);
        CameraRenderState camera = new CameraRenderState();
        camera.pos = Vec3.ZERO;
        set(renderer, "camera", camera);
        set(portal, "compositeUniform", mock(GpuBuffer.class));
        set(portal, "apertureMesh", mock(PortalGpuMesh.class));
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
        set(child, "environment", mock(PortalEnvironmentRenderer.class));
        set(sibling, "uniformWidth", 1920);
        set(sibling, "uniformHeight", 1080);
        set(sibling, "compositeUniform", mock(GpuBuffer.class));
        set(sibling, "apertureMesh", mock(PortalGpuMesh.class));
        set(sibling, "environment", mock(PortalEnvironmentRenderer.class));
        Method prepare = ClientPortalRenderer.class.getDeclaredMethod("prepareDestination", portal.getClass(), dimensions);
        prepare.setAccessible(true);
        Method sky = ClientPortalRenderer.class.getDeclaredMethod("destinationSky", portal.getClass(), dimensions);
        sky.setAccessible(true);
        Method releaseTarget = ClientPortalRenderer.class.getDeclaredMethod("releaseTarget", portal.getClass());
        releaseTarget.setAccessible(true);
        try {
            prepare.invoke(renderer, portal, size);
            assertSame(null, get(portal, "shader"));
            assertSame(shaded, get(portal, "target"));
            assertSame(session, get(portal, "destination"));
            assertSame(pendingSky, sky.invoke(renderer, portal, size));
            prepare.invoke(renderer, sibling, size);
            assertSame(null, get(sibling, "shader"));
            assertSame(siblingTarget, get(sibling, "target"));
            assertSame(siblingSky, sky.invoke(renderer, sibling, size));
            assertSame(shaded, get(portal, "target"));
            assertSame(PortalTerrainMaterials.VANILLA, get(portal, "materials"));
            verify(session, never()).begin(any());
            prepare.invoke(renderer, child, constructor.newInstance(1920, 1080, 1));
            assertSame(null, get(child, "shader"));
            assertSame(childTarget, get(child, "target"));
            assertSame(shaded, get(portal, "target"));
            when(childSession.ready()).thenReturn(true);
            prepare.invoke(renderer, child, constructor.newInstance(1920, 1080, 1));
            assertSame(childSession, get(child, "shader"));
            assertSame(childTarget, get(child, "target"));
            assertSame(null, get(portal, "shader"));
            when(session.ready()).thenReturn(true);
            prepare.invoke(renderer, portal, size);
            assertSame(session, get(portal, "shader"));
            assertSame(shaded, get(portal, "target"));
            verify(shaders, times(2)).acquire(1, scene.environment(), 1920, 1080);
            verify(environment, times(2)).prepare(scene.environment(), camera);
            releaseTarget.invoke(null, sibling);
            assertSame(null, get(sibling, "destination"));
            assertSame(null, get(sibling, "target"));
            assertSame(session, get(portal, "destination"));
            verify(siblingSession, never()).begin(any());
            verify(siblingSession).sky();
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
        ((Long2ObjectOpenHashMap<Object>) get(portal, "sections")).put(key, section);
        int generation = (int) get(portal, "generation");
        try {
            PortalScene rebound = scene();
            renderer.refreshScene(1, rebound);
            assertSame(portal, ((Map<?, ?>) get(renderer, "portals")).get(1));
            assertSame(rebound, get(portal, "scene"));
            assertSame(section, ((Long2ObjectOpenHashMap<Object>) get(portal, "sections")).get(key));
            verifyNoInteractions(shaders);
            PortalScene back = scene(0, false);
            renderer.refreshScene(1, back);
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
            verify(shaders).resetHistory(1);
            verify(shaders, never()).remove(1);
            renderer.invalidate(1, key);
            assertTrue(((LongSet) get(portal, "dirty")).contains(key));
            verify(mesh, never()).close();
        } finally {
            renderer.clear();
        }
        verify(mesh).close();
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
        renderer.invalidate(2, changed);
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
        Method next = ClientPortalRenderer.class.getDeclaredMethod("nextBuildPortal", boolean.class);
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
                set(portal, "cullFrustum", frustum);
                ((Long2ObjectOpenHashMap<Object>) get(portal, "sections")).put(resident,
                    constructor.newInstance(resident, 1L));
                renderer.invalidate(key, resident);
                maintain.invoke(renderer, portal);
                assertEquals(0, get(renderer, "pendingBuilds"));
                assertTrue(((LongSet) get(portal, "building")).isEmpty());
                set(portal, "rendered", true);
            }
            assertEquals(4, ((List<?>) get(renderer, "buildDemand")).size());
            assertEquals(8, get(next.invoke(renderer, true), "key"));
            set(renderer, "lastBuildPortal", 8);
            assertEquals(9, get(next.invoke(renderer, true), "key"));
            set(renderer, "lastBuildPortal", 9);
            set(renderer, "pendingBuilds", 2);
            dispatch.invoke(renderer);
            for (Object portal : ((Map<?, ?>) get(renderer, "portals")).values()) {
                assertTrue(((LongSet) get(portal, "building")).isEmpty());
            }
            set(renderer, "pendingBuilds", 0);
            assertEquals(7, get(next.invoke(renderer, true), "key"));
            set(renderer, "lastBuildPortal", 7);
            assertEquals(1, get(next.invoke(renderer, true), "key"));
            set(renderer, "residentBuilds", 3);
            assertEquals(1, get(next.invoke(renderer, false), "key"));
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
        renderer.invalidate(1, changed);
        renderer.invalidate(1, changed);
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
                int.class, PortalSectionMesh.class, Throwable.class);
            finish.setAccessible(true);
            finish.invoke(renderer, portal, changed, 2L, get(portal, "generation"), mesh, null);
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

    private static PortalScene scene() {
        return scene(0);
    }

    private static PortalScene scene(int parent) {
        return scene(parent, true);
    }

    private static PortalScene scene(int parent, boolean front) {
        PortalScene scene = mock(PortalScene.class);
        PortalGeometry aperture = new PortalGeometry();
        aperture.setArea(new AxisAlignedBB(0, 1.999, 0, 1.999, 0, 0.999));
        ClientPortalGeometry geometry = ClientPortalGeometry.fromPortal(new ClientPortalGeometry.Source(aperture,
            PortalFrame.canonical(Direction.S), front, false, 0, 0, 0, 0, 64, 0,
            ClientPortalGeometry.BLACKOUT_OFF, 0, ClientPortalGeometry.MASK_AIR_PROJECT,
            ProjectedBlockClaim.LightingPolicy.LOCAL, 0, ClientPortalGeometry.KIND_FRAME, parent, 0, List.of())).orElseThrow();
        when(scene.geometry()).thenReturn(geometry);
        return scene;
    }

}
