package art.arcane.wormholes.modded.client.render;

import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.network.client.ClientViewEnvironment;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
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
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.DynamicGpuData;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
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

public class ClientPortalRendererTest {
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
    public void changedArrivalSectionReleasesStaleMeshWithoutReleasingDrawableCover() throws ReflectiveOperationException {
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
        Class<?> sectionType = Class.forName(ClientPortalRenderer.class.getName() + "$Section");
        Constructor<?> constructor = sectionType.getDeclaredConstructor(long.class, long.class);
        constructor.setAccessible(true);
        Object section = constructor.newInstance(key, 1L);
        PortalGpuMesh mesh = mock(PortalGpuMesh.class);
        when(mesh.bytes()).thenReturn(64L);
        ((EnumMap<ChunkSectionLayer, PortalGpuMesh>) get(section, "layers")).put(ChunkSectionLayer.SOLID, mesh);
        Long2ObjectOpenHashMap<Object> sections = (Long2ObjectOpenHashMap<Object>) get(portal, "sections");
        sections.put(key, section);
        set(renderer, "gpuBytes", 64L);
        set(portal, "orderDirty", false);
        renderer.retainArrival();
        try {
            assertTrue(renderer.arrivalDrawable());
            renderer.invalidateArrival(key);
            assertFalse(sections.containsKey(key));
            assertTrue(((LongSet) get(portal, "dirty")).contains(key));
            assertEquals(true, get(portal, "orderDirty"));
            assertEquals(0L, get(renderer, "gpuBytes"));
            verify(mesh).close();
            assertSame(portal, get(renderer, "arrival"));
            assertTrue(renderer.arrivalDrawable());
            renderer.invalidateArrival(key);
            verify(mesh, times(1)).close();
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
            long.class, int.class, PortalSectionMesh.class, Throwable.class);
        finish.setAccessible(true);
        try {
            finish.invoke(renderer, portal, key, 1L, get(portal, "generation"), mesh, null);
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
            renderer.updateTravelCamera(source, new ClientViewEnvironment.Transform(Direction.S, Direction.U, Direction.W, new GeometryVector(100, 0, 0)));
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
            long.class, int.class, PortalSectionMesh.class, Throwable.class);
        finish.setAccessible(true);
        try {
            assertFalse(renderer.travelReady());
            finish.invoke(renderer, portal, key, 1L, get(portal, "generation"), mesh, null);
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
    public void pendingShadersPrepareMeshesWithoutNativeCapturesAndRetainMaterialsWhenReady() throws ReflectiveOperationException {
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
        ClientViewEnvironment environment = PortalEnvironmentTest.environment(ClientViewEnvironment.Transform.IDENTITY);
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
        set(portal, "cullFrustum", frustum);
        Class<?> portalType = Class.forName(ClientPortalRenderer.class.getName() + "$Portal");
        Method maintain = ClientPortalRenderer.class.getDeclaredMethod("maintain", portalType);
        Method dispatch = ClientPortalRenderer.class.getDeclaredMethod("dispatchBuilds", long.class);
        maintain.setAccessible(true);
        dispatch.setAccessible(true);
        try {
            maintain.invoke(renderer, portal);
            dispatch.invoke(renderer, Long.MAX_VALUE);
            assertEquals(64, ((Map<?, ?>) get(portal, "sections")).size());
            assertEquals(0, get(renderer, "pendingBuilds"));
            assertTrue(((LongSet) get(portal, "building")).isEmpty());
            assertFalse(((LongSet) get(portal, "dirty")).isEmpty());
            dispatch.invoke(renderer, 0L);
            assertEquals(65, ((Map<?, ?>) get(portal, "sections")).size());
            assertEquals(0, get(renderer, "pendingBuilds"));
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
        PortalGeometry aperture = new PortalGeometry();
        aperture.setArea(new AxisAlignedBB(0, 1.999, 0, 1.999, 0, 0.999));
        ClientPortalGeometry geometry = ClientPortalGeometry.fromPortal(new ClientPortalGeometry.Source(aperture,
            PortalFrame.canonical(Direction.S), front, false, 0, 0, 0, 0, 64, 0,
            ClientPortalGeometry.BLACKOUT_OFF, 0, ClientPortalGeometry.MASK_AIR_PROJECT,
            ProjectedBlockClaim.LightingPolicy.LOCAL, 0, ClientPortalGeometry.KIND_FRAME, parent, 0, List.of())).orElseThrow();
        when(scene.geometry()).thenReturn(geometry);
        when(scene.sectionKeys()).thenReturn(new LongArrayList());
        return scene;
    }

}
