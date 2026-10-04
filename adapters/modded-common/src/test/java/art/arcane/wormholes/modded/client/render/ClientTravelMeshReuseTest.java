package art.arcane.wormholes.modded.client.render;

import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.wormholes.render.client.ClientPortalGeometry;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.EnumMap;
import java.util.Map;
import java.util.List;

import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import net.minecraft.client.renderer.chunk.ChunkSectionLayer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ClientTravelMeshReuseTest {
    @Test
    @SuppressWarnings("unchecked")
    public void currentPreparedDestinationClaimsCompilationBeforeFutureSourceWarmup() throws ReflectiveOperationException {
        ClientPortalRenderer renderer = ClientPortalRenderer.instance();
        renderer.clear();
        PortalScene currentScene = fixtureScene();
        ClientTravelScene sourceScene = mock(ClientTravelScene.class);
        ClientPortalGeometry sourceGeometry = fixtureScene().geometry();
        when(sourceScene.geometry()).thenReturn(sourceGeometry);
        renderer.prepareTravel(currentScene, null);
        renderer.prepareTravelSource(sourceScene);
        Object current = field(renderer, "travel");
        Object source = field(renderer, "travelSource");
        set(current, "hasInitialBuild", true);
        set(current, "initialSection", 7L);
        set(source, "hasInitialBuild", true);
        set(source, "initialSection", 8L);
        set(renderer, "lastBuildPortal", field(current, "key"));
        List<Object> demand = (List<Object>) field(renderer, "buildDemand");
        demand.add(current);
        demand.add(source);
        Method select = ClientPortalRenderer.class.getDeclaredMethod("nextBuildPortal", boolean.class, boolean.class, long.class);
        select.setAccessible(true);
        try {
            assertSame(current, select.invoke(renderer, false, true, Long.MAX_VALUE));
            set(current, "hasInitialBuild", false);
            when(currentScene.sectionKeys()).thenReturn(new LongArrayList(new long[]{7L}));
            when(currentScene.revision(7L)).thenReturn(-1L);
            set(renderer, "travelDrawn", true);
            assertEquals(null, select.invoke(renderer, false, true, Long.MAX_VALUE));
            when(currentScene.revision(7L)).thenReturn(1L);
            when(currentScene.empty(7L)).thenReturn(true);
            assertSame(source, select.invoke(renderer, false, true, Long.MAX_VALUE));
            set(current, "hasInitialBuild", true);
            set(renderer, "travelTransition", true);
            assertSame(source, select.invoke(renderer, false, true, Long.MAX_VALUE));
        } finally {
            renderer.clear();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void retiringSourcePreparationDropsSceneOwnershipButKeepsReusableBuffersAndPipelineLease() throws ReflectiveOperationException {
        ClientPortalRenderer renderer = ClientPortalRenderer.instance();
        renderer.clear();
        ClientTravelScene scene = mock(ClientTravelScene.class);
        ClientPortalGeometry geometry = fixtureScene().geometry();
        when(scene.geometry()).thenReturn(geometry);
        when(scene.revision(7)).thenReturn(42L);
        renderer.prepareTravel(fixtureScene(), null);
        Object destination = field(renderer, "travel");
        renderer.prepareTravelSource(scene);
        Object source = field(renderer, "travelSource");
        Class<?> sectionType = Class.forName(ClientPortalRenderer.class.getName() + "$Section");
        Constructor<?> constructor = sectionType.getDeclaredConstructor(long.class, long.class);
        constructor.setAccessible(true);
        Object section = constructor.newInstance(7L, 42L);
        set(section, "identity", identity((byte) 1));
        PortalGpuMesh mesh = mock(PortalGpuMesh.class);
        ((EnumMap<ChunkSectionLayer, PortalGpuMesh>) field(section, "layers")).put(ChunkSectionLayer.SOLID, mesh);
        ((Long2ObjectOpenHashMap<Object>) field(source, "sections")).put(7, section);
        PortalShaderRenderer shaders = mock(PortalShaderRenderer.class);
        set(renderer, "shaderRenderer", shaders);
        try {
            renderer.retireTravelSource();
            assertEquals(null, field(renderer, "travelSource"));
            assertFalse(((Map<?, ?>) field(renderer, "portals")).containsKey(-3));
            assertSame(destination, field(renderer, "travel"));
            assertEquals(1, ((Map<?, ?>) field(renderer, "travelMeshes")).size());
            verify(mesh, never()).close();
            verify(shaders).remove(-3);
            verify(shaders, never()).discard(-3);
            renderer.retireTravelSource();
            renderer.resourceReload();
            verify(mesh).close();
            assertTrue(((Map<?, ?>) field(renderer, "travelMeshes")).isEmpty());
        } finally {
            renderer.clear();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void occupiedAsyncSlotsDoNotBlockCachedGpuOrEmptySectionCompletion() throws ReflectiveOperationException {
        ClientPortalRenderer renderer = ClientPortalRenderer.instance();
        renderer.clear();
        ClientTravelScene scene = mock(ClientTravelScene.class);
        ClientPortalGeometry geometry = fixtureScene().geometry();
        when(scene.geometry()).thenReturn(geometry);
        when(scene.revision(7)).thenReturn(42L);
        when(scene.revision(8)).thenReturn(42L);
        when(scene.revision(9)).thenReturn(42L);
        when(scene.empty(8)).thenReturn(true);
        ClientTravelScene.MeshIdentity identity = identity((byte) 1);
        when(scene.meshIdentity(7)).thenReturn(identity);
        CameraRenderState camera = new CameraRenderState();
        camera.pos = Vec3.ZERO;
        camera.blockPos = BlockPos.ZERO;
        renderer.prepareTravel(scene, camera);
        Object portal = field(renderer, "travel");
        set(portal, "camera", camera);
        Class<?> sectionType = Class.forName(ClientPortalRenderer.class.getName() + "$Section");
        Constructor<?> constructor = sectionType.getDeclaredConstructor(long.class, long.class);
        constructor.setAccessible(true);
        Object cached = constructor.newInstance(7L, 42L);
        set(cached, "identity", identity);
        PortalGpuMesh mesh = mock(PortalGpuMesh.class);
        ((EnumMap<ChunkSectionLayer, PortalGpuMesh>) field(cached, "layers")).put(ChunkSectionLayer.SOLID, mesh);
        Method retain = ClientPortalRenderer.class.getDeclaredMethod("retainTravelMesh", portal.getClass(), sectionType);
        retain.setAccessible(true);
        assertTrue((boolean) retain.invoke(renderer, portal, cached));
        LongSet dirty = (LongSet) field(portal, "dirty");
        dirty.add(9);
        dirty.add(7);
        dirty.add(8);
        ((List<Object>) field(renderer, "buildDemand")).add(portal);
        set(renderer, "pendingBuilds", 2);
        Method dispatch = ClientPortalRenderer.class.getDeclaredMethod("dispatchBuilds", long.class);
        dispatch.setAccessible(true);
        try {
            dispatch.invoke(renderer, Long.MAX_VALUE);
            Long2ObjectOpenHashMap<Object> sections = (Long2ObjectOpenHashMap<Object>) field(portal, "sections");
            assertSame(cached, sections.get(7));
            assertTrue(sections.containsKey(8));
            assertFalse(sections.containsKey(9));
            assertTrue(dirty.contains(9));
            assertFalse(dirty.contains(7));
            assertFalse(dirty.contains(8));
            assertEquals(2, field(renderer, "pendingBuilds"));
            assertTrue(((LongSet) field(portal, "building")).isEmpty());
            verify(mesh, never()).close();
        } finally {
            set(renderer, "pendingBuilds", 0);
            renderer.clear();
        }
    }

    @Test
    public void equivalentNativeHaloReusesExactGpuBuffersWithFreshSceneRevision() throws ReflectiveOperationException {
        reuse(false);
    }

    @Test
    public void changedHaloRequiresRebuildInsteadOfReusingStaleGpuBuffers() throws ReflectiveOperationException {
        reuse(true);
    }

    private static void reuse(boolean changed) throws ReflectiveOperationException {
        ClientPortalRenderer renderer = ClientPortalRenderer.instance();
        renderer.clear();
        ClientTravelScene scene = mock(ClientTravelScene.class);
        PortalScene geometry = fixtureScene();
        ClientPortalGeometry nativeGeometry = geometry.geometry();
        when(scene.geometry()).thenReturn(nativeGeometry);
        when(scene.revision(7)).thenReturn(42L);
        ClientTravelScene.MeshIdentity original = identity((byte) 1);
        when(scene.meshIdentity(7)).thenReturn(original);
        renderer.prepareTravel(scene, null);
        Object portal = field(renderer, "travel");
        Class<?> sectionType = Class.forName(ClientPortalRenderer.class.getName() + "$Section");
        Constructor<?> sectionConstructor = sectionType.getDeclaredConstructor(long.class, long.class);
        sectionConstructor.setAccessible(true);
        Object section = sectionConstructor.newInstance(7L, 42L);
        set(section, "identity", original);
        PortalGpuMesh mesh = mock(PortalGpuMesh.class);
        @SuppressWarnings("unchecked")
        EnumMap<ChunkSectionLayer, PortalGpuMesh> layers = (EnumMap<ChunkSectionLayer, PortalGpuMesh>) field(section, "layers");
        layers.put(ChunkSectionLayer.SOLID, mesh);
        Method retain = ClientPortalRenderer.class.getDeclaredMethod("retainTravelMesh", portal.getClass(), sectionType);
        retain.setAccessible(true);
        Method reuse = ClientPortalRenderer.class.getDeclaredMethod("reuseTravelMesh", portal.getClass(), long.class);
        reuse.setAccessible(true);
        assertTrue((boolean) retain.invoke(renderer, portal, section));
        when(scene.revision(7)).thenReturn(91L);
        when(scene.meshIdentity(7)).thenReturn(identity(changed ? (byte) 2 : (byte) 1));
        try {
            assertEquals(!changed, reuse.invoke(renderer, portal, 7L));
            if (changed) {
                verify(mesh).close();
            } else {
                assertEquals(91L, field(section, "revision"));
                assertSame(mesh, layers.get(ChunkSectionLayer.SOLID));
                verify(mesh, never()).close();
            }
        } finally {
            renderer.clear();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void compiledZeroGeometryReusesFreshProofButRejectsChangedHaloAndUncompiledAir() throws ReflectiveOperationException {
        ClientPortalRenderer renderer = ClientPortalRenderer.instance();
        renderer.clear();
        ClientTravelScene scene = mock(ClientTravelScene.class);
        ClientPortalGeometry geometry = fixtureScene().geometry();
        when(scene.geometry()).thenReturn(geometry);
        when(scene.revision(7)).thenReturn(42L);
        when(scene.meshIdentity(7)).thenReturn(identity((byte) 1));
        renderer.prepareTravel(scene, null);
        Object portal = field(renderer, "travel");
        Class<?> sectionType = Class.forName(ClientPortalRenderer.class.getName() + "$Section");
        Constructor<?> constructor = sectionType.getDeclaredConstructor(long.class, long.class);
        constructor.setAccessible(true);
        Object completed = constructor.newInstance(7L, 42L);
        set(completed, "identity", identity((byte) 1));
        Method retain = ClientPortalRenderer.class.getDeclaredMethod("retainTravelMesh", portal.getClass(), sectionType);
        retain.setAccessible(true);
        Method reuse = ClientPortalRenderer.class.getDeclaredMethod("reuseTravelMesh", portal.getClass(), long.class);
        reuse.setAccessible(true);
        Long2ObjectOpenHashMap<Object> sections = (Long2ObjectOpenHashMap<Object>) field(portal, "sections");
        try {
            assertTrue(((Map<?, ?>) field(completed, "layers")).isEmpty());
            assertTrue((boolean) retain.invoke(renderer, portal, completed));
            assertEquals(1, ((Map<?, ?>) field(renderer, "travelMeshes")).size());
            assertTrue((long) field(renderer, "travelProofBytes") > 0);
            when(scene.revision(7)).thenReturn(91L);
            when(scene.meshIdentity(7)).thenReturn(identity((byte) 1));
            assertTrue((boolean) reuse.invoke(renderer, portal, 7L));
            assertSame(completed, sections.get(7));
            assertEquals(91L, field(completed, "revision"));
            assertEquals(0, field(renderer, "pendingBuilds"));
            assertEquals(0L, field(renderer, "gpuBytes"));
            assertEquals(0L, field(renderer, "travelProofBytes"));
            assertTrue(((Map<?, ?>) field(renderer, "travelMeshes")).isEmpty());
            renderer.invalidateTravel(7);
            assertFalse(sections.containsKey(7));
            assertTrue(((LongSet) field(portal, "dirty")).contains(7));
            assertTrue((boolean) retain.invoke(renderer, portal, completed));
            when(scene.revision(7)).thenReturn(92L);
            when(scene.meshIdentity(7)).thenReturn(identity((byte) 2));
            assertFalse((boolean) reuse.invoke(renderer, portal, 7L));
            assertFalse(sections.containsKey(7));
            assertEquals(0L, field(renderer, "travelProofBytes"));
            assertTrue(((Map<?, ?>) field(renderer, "travelMeshes")).isEmpty());
            Object air = constructor.newInstance(7L, 92L);
            assertFalse((boolean) retain.invoke(renderer, portal, air));
            assertTrue(((Map<?, ?>) field(renderer, "travelMeshes")).isEmpty());
        } finally {
            renderer.clear();
        }
    }

    @Test
    public void activeGpuPressureEvictsOnlyAvailableCachedEntries() throws ReflectiveOperationException {
        ClientPortalRenderer renderer = ClientPortalRenderer.instance();
        renderer.clear();
        ClientTravelScene scene = mock(ClientTravelScene.class);
        ClientPortalGeometry geometry = fixtureScene().geometry();
        when(scene.geometry()).thenReturn(geometry);
        when(scene.revision(7)).thenReturn(42L);
        renderer.prepareTravel(scene, null);
        Object portal = field(renderer, "travel");
        Class<?> sectionType = Class.forName(ClientPortalRenderer.class.getName() + "$Section");
        Constructor<?> constructor = sectionType.getDeclaredConstructor(long.class, long.class);
        constructor.setAccessible(true);
        Object section = constructor.newInstance(7L, 42L);
        set(section, "identity", identity((byte) 1));
        PortalGpuMesh mesh = mock(PortalGpuMesh.class);
        @SuppressWarnings("unchecked")
        EnumMap<ChunkSectionLayer, PortalGpuMesh> layers = (EnumMap<ChunkSectionLayer, PortalGpuMesh>) field(section, "layers");
        layers.put(ChunkSectionLayer.SOLID, mesh);
        set(renderer, "gpuBytes", 129L * 1024 * 1024);
        Method retain = ClientPortalRenderer.class.getDeclaredMethod("retainTravelMesh", portal.getClass(), sectionType);
        retain.setAccessible(true);
        try {
            assertTrue((boolean) retain.invoke(renderer, portal, section));
            verify(mesh).close();
            assertEquals(0L, field(renderer, "travelProofBytes"));
            assertTrue(((Map<?, ?>) field(renderer, "travelMeshes")).isEmpty());
        } finally {
            set(renderer, "gpuBytes", 0L);
            renderer.clear();
        }
    }

    @Test
    public void proofIncludesExactWorldMetadataAndEveryNativeColumnByte() {
        ClientTravelScene.MeshIdentity first = identity((byte) 1);
        assertTrue(first.same(identity((byte) 1)));
        assertFalse(first.same(identity((byte) 2)));
        ClientViewMessage.TravelWorld world = new ClientViewMessage.TravelWorld("other", "minecraft:overworld", 7,
            false, false, 63, -64, 384);
        assertFalse(first.same(new ClientTravelScene.MeshIdentity(world, identity((byte) 1).columns())));
    }

    private static ClientTravelScene.MeshIdentity identity(byte last) {
        byte[][] columns = new byte[9][];
        for (int index = 0; index < columns.length; index++) {
            columns[index] = new byte[]{1, 2, 3};
        }
        columns[8][2] = last;
        return new ClientTravelScene.MeshIdentity(new ClientViewMessage.TravelWorld("minecraft:overworld", "minecraft:overworld",
            7, false, false, 63, -64, 384), columns);
    }

    private static PortalScene fixtureScene() throws ReflectiveOperationException {
        Method factory = ClientPortalRendererTest.class.getDeclaredMethod("scene");
        factory.setAccessible(true);
        return (PortalScene) factory.invoke(null);
    }

    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static void set(Object owner, String name, Object value) throws ReflectiveOperationException {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }
}
