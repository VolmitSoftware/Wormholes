package art.arcane.wormholes.modded.client.render;

import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.optics.stream.ViewStreamMessage;
import art.arcane.optics.stream.EnvironmentState;
import art.arcane.optics.stream.Brick;
import art.arcane.optics.stream.SectionBiomes;
import art.arcane.wormholes.modded.client.ClientMeshSections;
import art.arcane.wormholes.modded.client.ClientMeshWorld;
import art.arcane.wormholes.modded.client.ClientPalette;
import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.math.BlockBox;
import art.arcane.optics.frame.OpticTransform;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import org.mockito.MockedStatic;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import art.arcane.wormholes.network.client.TravelMessage;

public class ClientTravelMeshReuseTest extends MinecraftTestBase {
    @Test
    public void bulkAdoptionRestoresExactProjectedGpuObjectsBeforeDrawingWithoutCompilerSlots() throws Exception {
        try (BulkFixture fixture = new BulkFixture()) {
            Object first = fixture.cache(0L);
            long neighbor = SectionPos.asLong(1, 0, 0);
            Object second = fixture.cache(neighbor);
            fixture.reopen();
            set(fixture.renderer, "pendingBuilds", 2);
            set(fixture.renderer, "buildBudgetNanos", 60_000_000_000L);
            fixture.restore();
            assertSame(first, fixture.sections().get(0L));
            assertSame(second, fixture.sections().get(neighbor));
            assertTrue(fixture.retained().isEmpty());
            assertTrue(((Map<?, ?>) field(fixture.renderer, "retainedMeshOrder")).isEmpty());
            assertEquals(0L, field(fixture.renderer, "retainedProofBytes"));
            assertEquals(2, field(fixture.renderer, "pendingBuilds"));
            assertTrue(((LongSet) field(fixture.portal, "building")).isEmpty());
            assertTrue(((LongSet) field(fixture.portal, "dirty")).isEmpty());
            Method ordered = ClientPortalRenderer.class.getDeclaredMethod("orderedSections", fixture.portal.getClass());
            ordered.setAccessible(true);
            assertEquals(2, ((List<?>) ordered.invoke(fixture.renderer, fixture.portal)).size());
            for (PortalGpuMesh mesh : fixture.meshes.values()) {
                verify(mesh, never()).close();
            }
        }
    }

    @Test
    public void bulkAdoptionRejectsChangedProjectedNeighborAndKeepsCapturedProofForLaterInputs() throws Exception {
        try (BulkFixture fixture = new BulkFixture()) {
            fixture.cache(0L);
            fixture.reopen();
            fixture.store.put(new ViewStreamMessage.MeshSection(11, 1, 1, 0, 0, 2, 4,
                Brick.single(0, 4), SectionBiomes.NONE));
            set(fixture.renderer, "buildBudgetNanos", 60_000_000_000L);
            fixture.restore();
            assertTrue(fixture.sections().isEmpty());
            assertEquals(1, fixture.retained().size());
            verify(fixture.meshes.get(0L), never()).close();
            fixture.renderer.resourceReload();
            verify(fixture.meshes.get(0L)).close();
            assertTrue(fixture.retained().isEmpty());
            assertEquals(0L, field(fixture.renderer, "retainedProofBytes"));
        }
    }

    @Test
    public void bulkAdoptionExcludesWrongMaterialsAbsentSectionsAndRunningBuildsBeforeProofCapture() throws Exception {
        try (BulkFixture fixture = new BulkFixture()) {
            Object cached = fixture.cache(0L);
            fixture.reopen();
            when(fixture.scene.meshIdentity(anyLong())).thenThrow(new IllegalStateException("unnecessary proof capture"));
            set(fixture.renderer, "buildBudgetNanos", 60_000_000_000L);
            set(fixture.portal, "materials", new PortalTerrainMaterials(true, Map.of(), 99, PortalTerrainMaterials.Lighting.VANILLA));
            fixture.restore();
            assertEquals(1, fixture.retained().size());
            set(fixture.portal, "materials", PortalTerrainMaterials.VANILLA);
            ((LongSet) field(fixture.portal, "building")).add(0L);
            fixture.restore();
            assertTrue(fixture.sections().isEmpty());
            ((LongSet) field(fixture.portal, "building")).clear();
            assertTrue(fixture.store.drop(11, 1, 0, 0, 0));
            fixture.restore();
            assertTrue(fixture.sections().isEmpty());
            assertEquals(1, fixture.retained().size());
            assertSame(cached, fixture.retained().values().iterator().next());
        }
    }

    @Test
    public void inactiveOrExhaustedBudgetAdoptionDoesNotCaptureProofAndDispatchSharesTheRemainder() throws Exception {
        try (BulkFixture fixture = new BulkFixture()) {
            fixture.cache(0L);
            fixture.reopen();
            when(fixture.scene.meshContext()).thenThrow(new IllegalStateException("unnecessary context capture"));
            set(fixture.renderer, "buildBudgetNanos", 0L);
            fixture.restore();
            assertEquals(0L, field(fixture.renderer, "buildBudgetNanos"));
            set(fixture.renderer, "buildBudgetNanos", 1234L);
            set(fixture.portal, "active", false);
            fixture.restore();
            assertEquals(1234L, field(fixture.renderer, "buildBudgetNanos"));
            assertEquals(1, fixture.retained().size());
            set(fixture.portal, "active", true);
            set(fixture.renderer, "buildBudgetNanos", 0L);
            Method dispatch = ClientPortalRenderer.class.getDeclaredMethod("dispatchBuilds");
            dispatch.setAccessible(true);
            dispatch.invoke(fixture.renderer);
            assertTrue(fixture.sections().isEmpty());
            assertEquals(0L, field(fixture.renderer, "buildBudgetNanos"));
            fixture.renderer.clear();
            assertEquals(2_000_000L, field(fixture.renderer, "buildBudgetNanos"));
        }
    }

    @Test
    public void bulkAdoptionProgressesPastFullCachePrefixWithoutPinningEvictedProofs() throws Exception {
        try (BulkFixture fixture = new BulkFixture()) {
            for (int index = 0; index < 2047; index++) {
                fixture.retainOtherContext(SectionPos.asLong(index + 100, 0, 0));
            }
            Object matched = fixture.cache(0L);
            fixture.reopen();
            PortalScene.MeshIdentity actualContext = ClientMeshWorld.meshContext(fixture.snapshot(0L));
            PortalScene.MeshIdentity context = mock(PortalScene.MeshIdentity.class);
            when(context.contextHash()).thenReturn(actualContext.contextHash());
            AtomicInteger inspected = new AtomicInteger();
            when(context.sameContext(any())).thenAnswer(invocation -> {
                boolean compatible = actualContext.sameContext(invocation.getArgument(0));
                if (!compatible) {
                    inspected.incrementAndGet();
                    long until = System.nanoTime() + 200_000L;
                    while (System.nanoTime() < until) {
                        Thread.onSpinWait();
                    }
                }
                return compatible;
            });
            when(fixture.scene.meshContext()).thenReturn(context);
            set(fixture.renderer, "pendingBuilds", 2);
            assertEquals(2048, fixture.retained().size());
            int frames = 0;
            while (fixture.sections().isEmpty() && frames++ < 4096) {
                set(fixture.renderer, "buildBudgetNanos", inspected.get() == 2047 ? 10_000_000L : 100_000L);
                fixture.restore();
            }
            assertSame(matched, fixture.sections().get(0L));
            assertEquals(2047, inspected.get());
            assertEquals(2047, fixture.retained().size());
            assertEquals(0L, field(fixture.portal, "restoreSequence"));
        }
    }

    @Test
    public void resumedBulkAdoptionSeeksLateCompatibleEntryWithoutTraversingVisitedPrefix() throws Exception {
        try (BulkFixture fixture = new BulkFixture()) {
            for (int index = 0; index < 4096; index++) {
                fixture.retainOtherContext(SectionPos.asLong(index + 100, 0, 0));
            }
            Object matched = fixture.cache(0L);
            fixture.reopen();
            LinkedHashMap<Object, Object> cached = spy(new LinkedHashMap<>(fixture.retained()));
            set(fixture.renderer, "retainedMeshes", cached);
            set(fixture.portal, "restoreSequence", (long) field(matched, "retainedSequence") - 1L);
            set(fixture.renderer, "buildBudgetNanos", 10_000_000L);
            fixture.restore();
            assertSame(matched, fixture.sections().get(0L));
            verify(cached, never()).entrySet();
            assertEquals(4096, cached.size());
            assertEquals(4096, ((Map<?, ?>) field(fixture.renderer, "retainedMeshOrder")).size());
            fixture.renderer.clear();
            assertTrue(((Map<?, ?>) field(fixture.renderer, "retainedMeshOrder")).isEmpty());
        }
    }

    @Test
    public void retainedSequenceIndexReleasesReplacedAndEvictedProofsWithoutChangingCacheOrder() throws Exception {
        try (BulkFixture fixture = new BulkFixture()) {
            PortalGpuMesh firstMesh = mock(PortalGpuMesh.class);
            Object first = fixture.retain(0L, identity((byte) 1), firstMesh);
            long firstSequence = (long) field(first, "retainedSequence");
            Object neighbor = fixture.retain(1L, identity((byte) 2), mock(PortalGpuMesh.class));
            PortalGpuMesh replacementMesh = mock(PortalGpuMesh.class);
            Object replacement = fixture.retain(0L, identity((byte) 3), replacementMesh);
            Map<?, ?> order = (Map<?, ?>) field(fixture.renderer, "retainedMeshOrder");
            assertEquals(2, order.size());
            assertFalse(order.containsKey(firstSequence));
            assertTrue(order.containsKey(field(replacement, "retainedSequence")));
            assertSame(neighbor, fixture.retained().values().iterator().next());
            verify(firstMesh).close();
            Method evict = ClientPortalRenderer.class.getDeclaredMethod("evictRetainedMesh");
            evict.setAccessible(true);
            evict.invoke(fixture.renderer);
            assertEquals(1, order.size());
            assertFalse(order.containsKey(field(neighbor, "retainedSequence")));
            assertSame(replacement, fixture.retained().values().iterator().next());
            fixture.renderer.resourceReload();
            assertTrue(order.isEmpty());
            assertTrue(fixture.retained().isEmpty());
            assertEquals(0L, field(fixture.renderer, "retainedProofBytes"));
            verify(replacementMesh).close();
        }
    }

    @Test
    public void availableCompilerSlotsReserveDispatchBudgetDuringBulkAdoption() throws Exception {
        try (BulkFixture fixture = new BulkFixture()) {
            fixture.cache(0L);
            fixture.reopen();
            set(fixture.renderer, "buildBudgetNanos", 100_000_000L);
            fixture.restore();
            assertEquals(1, fixture.sections().size());
            assertTrue((long) field(fixture.renderer, "buildBudgetNanos") > 50_000_000L);
            Method dispatch = ClientPortalRenderer.class.getDeclaredMethod("dispatchBuilds");
            dispatch.setAccessible(true);
            dispatch.invoke(fixture.renderer);
            assertTrue((long) field(fixture.renderer, "buildBudgetNanos") < 100_000_000L);
        }
    }

    @Test
    public void retainedGpuMeshesBeyond2048StayOwnedWhileBothByteBudgetsAllowThem() throws Exception {
        try (GpuFixture gpu = new GpuFixture(0L); BulkFixture fixture = new BulkFixture()) {
            Object first = null;
            Object last = null;
            PortalGpuMesh lastMesh = null;
            for (int index = 0; index < 2050; index++) {
                PortalGpuMesh mesh = gpu.mesh();
                Object section = fixture.retain(SectionPos.asLong(index + 100, 0, 0), identity((byte) index), mesh);
                if (index == 0) {
                    first = section;
                }
                last = section;
                lastMesh = mesh;
            }
            assertEquals(2050, fixture.retained().size());
            assertSame(first, fixture.retained().values().iterator().next());
            assertTrue(fixture.retained().containsValue(last));
            assertSame(lastMesh, ((Map<?, ?>) field(last, "layers")).get(ChunkSectionLayer.SOLID));
            assertTrue((long) field(fixture.renderer, "retainedProofBytes") > 0L);
            assertTrue((long) field(fixture.renderer, "retainedProofBytes") < 32L * 1024 * 1024);
            assertEquals(2050L * 60, field(fixture.renderer, "gpuBytes"));
            for (GpuBuffer buffer : gpu.buffers) {
                verify(buffer, never()).close();
            }
            fixture.renderer.resourceReload();
            assertTrue(fixture.retained().isEmpty());
            assertEquals(0L, field(fixture.renderer, "retainedProofBytes"));
            assertEquals(0L, field(fixture.renderer, "gpuBytes"));
            for (GpuBuffer buffer : gpu.buffers) {
                verify(buffer).close();
            }
        }
    }

    @Test
    public void canonicalProofBytePressureEvictsOnlyOldestRetainedGpuOwnership() throws Exception {
        try (GpuFixture gpu = new GpuFixture(0L); BulkFixture fixture = new BulkFixture()) {
            Object first = fixture.retain(0L, largeIdentity(), gpu.mesh());
            Object second = fixture.retain(SectionPos.asLong(1, 0, 0), largeIdentity(), gpu.mesh());
            assertEquals(1, fixture.retained().size());
            assertFalse(fixture.retained().containsValue(first));
            assertSame(second, fixture.retained().values().iterator().next());
            assertTrue((long) field(fixture.renderer, "retainedProofBytes") < 32L * 1024 * 1024);
            assertEquals(60L, field(fixture.renderer, "gpuBytes"));
            verify(gpu.buffers.get(0)).close();
            verify(gpu.buffers.get(1)).close();
            verify(gpu.buffers.get(2), never()).close();
            verify(gpu.buffers.get(3), never()).close();
        }
    }

    @Test
    public void totalGpuBytePressureEvictsOldestRetainedMeshWithoutChangingProofBudget() throws Exception {
        try (GpuFixture gpu = new GpuFixture(20L * 1024 * 1024); BulkFixture fixture = new BulkFixture()) {
            Object first = null;
            Object second = null;
            for (int index = 0; index < 4; index++) {
                Object section = fixture.retain(SectionPos.asLong(index, 0, 0), identity((byte) index), gpu.mesh());
                if (index == 0) {
                    first = section;
                } else if (index == 1) {
                    second = section;
                }
            }
            assertEquals(3, fixture.retained().size());
            assertFalse(fixture.retained().containsValue(first));
            assertSame(second, fixture.retained().values().iterator().next());
            assertEquals(120L * 1024 * 1024, field(fixture.renderer, "gpuBytes"));
            assertTrue((long) field(fixture.renderer, "retainedProofBytes") < 32L * 1024 * 1024);
            verify(gpu.buffers.get(0)).close();
            verify(gpu.buffers.get(1)).close();
            for (GpuBuffer buffer : gpu.buffers.subList(2, gpu.buffers.size())) {
                verify(buffer, never()).close();
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void currentPreparedDestinationClaimsCompilationBeforeFutureSourceWarmup() throws ReflectiveOperationException {
        ClientPortalRenderer renderer = ClientPortalRenderer.instance();
        renderer.clear();
        PortalScene currentScene = fixtureScene();
        ClientTravelScene sourceScene = mock(ClientTravelScene.class);
        ApertureDescriptor sourceGeometry = fixtureScene().geometry();
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
        doCallRealMethod().when(scene).matchesMeshIdentity(anyLong(), any());
        ApertureDescriptor geometry = fixtureScene().geometry();
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
            assertEquals(1, ((Map<?, ?>) field(renderer, "retainedMeshes")).size());
            verify(mesh, never()).close();
            verify(shaders).remove(-3);
            verify(shaders, never()).discard(-3);
            renderer.retireTravelSource();
            renderer.resourceReload();
            verify(mesh).close();
            assertTrue(((Map<?, ?>) field(renderer, "retainedMeshes")).isEmpty());
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
        doCallRealMethod().when(scene).matchesMeshIdentity(anyLong(), any());
        ApertureDescriptor geometry = fixtureScene().geometry();
        when(scene.geometry()).thenReturn(geometry);
        when(scene.revision(7)).thenReturn(42L);
        when(scene.revision(8)).thenReturn(42L);
        when(scene.revision(9)).thenReturn(42L);
        when(scene.empty(8)).thenReturn(true);
        ClientTravelScene.MeshIdentity identity = identity((byte) 1);
        when(scene.meshContext()).thenReturn(identity((byte) 1));
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
        Method retain = ClientPortalRenderer.class.getDeclaredMethod("retainMesh", portal.getClass(), sectionType);
        retain.setAccessible(true);
        assertTrue((boolean) retain.invoke(renderer, portal, cached));
        LongSet dirty = (LongSet) field(portal, "dirty");
        dirty.add(9);
        dirty.add(7);
        dirty.add(8);
        set(portal, "hasInitialBuild", true);
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
    @SuppressWarnings("unchecked")
    public void busyCompilersDoNotRescanRebuildPrioritiesForEachCachedCompletion() throws ReflectiveOperationException {
        ClientPortalRenderer renderer = ClientPortalRenderer.instance();
        renderer.clear();
        ClientTravelScene scene = mock(ClientTravelScene.class);
        doCallRealMethod().when(scene).matchesMeshIdentity(anyLong(), any());
        ApertureDescriptor geometry = fixtureScene().geometry();
        when(scene.geometry()).thenReturn(geometry);
        AtomicInteger revisionReads = new AtomicInteger();
        when(scene.revision(anyLong())).thenAnswer(invocation -> {
            revisionReads.incrementAndGet();
            return 42L;
        });
        when(scene.empty(anyLong())).thenReturn(true);
        CameraRenderState camera = new CameraRenderState();
        camera.pos = Vec3.ZERO;
        camera.blockPos = BlockPos.ZERO;
        renderer.prepareTravel(scene, camera);
        Object portal = field(renderer, "travel");
        set(portal, "camera", camera);
        set(portal, "hasInitialBuild", true);
        LongSet dirty = (LongSet) field(portal, "dirty");
        for (long key = 0; key < 1024; key++) {
            dirty.add(key);
        }
        ((List<Object>) field(renderer, "buildDemand")).add(portal);
        set(renderer, "pendingBuilds", 2);
        Method dispatch = ClientPortalRenderer.class.getDeclaredMethod("dispatchBuilds", long.class);
        dispatch.setAccessible(true);
        try {
            dispatch.invoke(renderer, Long.MAX_VALUE);
            Long2ObjectOpenHashMap<Object> sections = (Long2ObjectOpenHashMap<Object>) field(portal, "sections");
            assertEquals(1024, sections.size());
            assertEquals(0, dirty.size());
            assertEquals(2, field(renderer, "pendingBuilds"));
            assertTrue("Repeated full rebuild-priority scans: " + revisionReads.get(), revisionReads.get() <= 2048);
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
        doCallRealMethod().when(scene).matchesMeshIdentity(anyLong(), any());
        PortalScene geometry = fixtureScene();
        ApertureDescriptor nativeGeometry = geometry.geometry();
        when(scene.geometry()).thenReturn(nativeGeometry);
        when(scene.revision(7)).thenReturn(42L);
        ClientTravelScene.MeshIdentity original = identity((byte) 1);
        when(scene.meshContext()).thenReturn(identity((byte) 1));
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
        Method retain = ClientPortalRenderer.class.getDeclaredMethod("retainMesh", portal.getClass(), sectionType);
        retain.setAccessible(true);
        Method reuse = ClientPortalRenderer.class.getDeclaredMethod("reuseMesh", portal.getClass(), long.class);
        reuse.setAccessible(true);
        assertTrue((boolean) retain.invoke(renderer, portal, section));
        when(scene.revision(7)).thenReturn(91L);
        when(scene.meshContext()).thenReturn(identity((byte) 1));
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
        doCallRealMethod().when(scene).matchesMeshIdentity(anyLong(), any());
        ApertureDescriptor geometry = fixtureScene().geometry();
        when(scene.geometry()).thenReturn(geometry);
        when(scene.revision(7)).thenReturn(42L);
        when(scene.meshContext()).thenReturn(identity((byte) 1));
        when(scene.meshIdentity(7)).thenReturn(identity((byte) 1));
        renderer.prepareTravel(scene, null);
        Object portal = field(renderer, "travel");
        Class<?> sectionType = Class.forName(ClientPortalRenderer.class.getName() + "$Section");
        Constructor<?> constructor = sectionType.getDeclaredConstructor(long.class, long.class);
        constructor.setAccessible(true);
        Object completed = constructor.newInstance(7L, 42L);
        set(completed, "identity", identity((byte) 1));
        Method retain = ClientPortalRenderer.class.getDeclaredMethod("retainMesh", portal.getClass(), sectionType);
        retain.setAccessible(true);
        Method reuse = ClientPortalRenderer.class.getDeclaredMethod("reuseMesh", portal.getClass(), long.class);
        reuse.setAccessible(true);
        Long2ObjectOpenHashMap<Object> sections = (Long2ObjectOpenHashMap<Object>) field(portal, "sections");
        try {
            assertTrue(((Map<?, ?>) field(completed, "layers")).isEmpty());
            assertTrue((boolean) retain.invoke(renderer, portal, completed));
            assertEquals(1, ((Map<?, ?>) field(renderer, "retainedMeshes")).size());
            assertTrue((long) field(renderer, "retainedProofBytes") > 0);
            when(scene.revision(7)).thenReturn(91L);
            when(scene.meshContext()).thenReturn(identity((byte) 1));
            when(scene.meshIdentity(7)).thenReturn(identity((byte) 1));
            assertTrue((boolean) reuse.invoke(renderer, portal, 7L));
            assertSame(completed, sections.get(7));
            assertEquals(91L, field(completed, "revision"));
            assertEquals(0, field(renderer, "pendingBuilds"));
            assertEquals(0L, field(renderer, "gpuBytes"));
            assertEquals(0L, field(renderer, "retainedProofBytes"));
            assertTrue(((Map<?, ?>) field(renderer, "retainedMeshes")).isEmpty());
            assertTrue(((Map<?, ?>) field(renderer, "retainedMeshOrder")).isEmpty());
            renderer.invalidateTravel(7);
            assertFalse(sections.containsKey(7));
            assertTrue(((LongSet) field(portal, "dirty")).contains(7));
            assertFalse((boolean) retain.invoke(renderer, portal, completed));
            ((LongSet) field(portal, "dirty")).remove(7);
            assertTrue((boolean) retain.invoke(renderer, portal, completed));
            when(scene.revision(7)).thenReturn(92L);
            when(scene.meshContext()).thenReturn(identity((byte) 1));
            when(scene.meshIdentity(7)).thenReturn(identity((byte) 2));
            assertFalse((boolean) reuse.invoke(renderer, portal, 7L));
            assertFalse(sections.containsKey(7));
            assertEquals(0L, field(renderer, "retainedProofBytes"));
            assertTrue(((Map<?, ?>) field(renderer, "retainedMeshes")).isEmpty());
            assertTrue(((Map<?, ?>) field(renderer, "retainedMeshOrder")).isEmpty());
            Object air = constructor.newInstance(7L, 92L);
            assertFalse((boolean) retain.invoke(renderer, portal, air));
            assertTrue(((Map<?, ?>) field(renderer, "retainedMeshes")).isEmpty());
        } finally {
            renderer.clear();
        }
    }

    @Test
    public void activeGpuPressureEvictsOnlyAvailableCachedEntries() throws ReflectiveOperationException {
        ClientPortalRenderer renderer = ClientPortalRenderer.instance();
        renderer.clear();
        ClientTravelScene scene = mock(ClientTravelScene.class);
        doCallRealMethod().when(scene).matchesMeshIdentity(anyLong(), any());
        ApertureDescriptor geometry = fixtureScene().geometry();
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
        Method retain = ClientPortalRenderer.class.getDeclaredMethod("retainMesh", portal.getClass(), sectionType);
        retain.setAccessible(true);
        try {
            assertTrue((boolean) retain.invoke(renderer, portal, section));
            verify(mesh).close();
            assertEquals(0L, field(renderer, "retainedProofBytes"));
            assertTrue(((Map<?, ?>) field(renderer, "retainedMeshes")).isEmpty());
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
        TravelMessage.TravelWorld world = new TravelMessage.TravelWorld("other", "minecraft:overworld", 7,
            false, false, 63, -64, 384);
        assertFalse(first.same(new ClientTravelScene.MeshIdentity(world, identity((byte) 1).columns())));
    }

    private static final class BulkFixture implements AutoCloseable {
        private static final BlockBox BOUNDS = new BlockBox(-32, -32, -32, 96, 96, 96);
        private final ClientPortalRenderer renderer = ClientPortalRenderer.instance();
        private final EnvironmentState environment = PortalEnvironmentTest.environment(OpticTransform.IDENTITY);
        private final RegistryAccess registry = mock(RegistryAccess.class);
        private final ClientMeshSections store;
        private final Long2ObjectOpenHashMap<PortalGpuMesh> meshes = new Long2ObjectOpenHashMap<>();
        private ClientMeshSections.View view;
        private PortalScene scene;
        private Object portal;

        private BulkFixture() throws Exception {
            renderer.clear();
            ClientPalette palette = new ClientPalette(BuiltInRegistries.BLOCK);
            palette.apply(new ViewStreamMessage.Palette(List.of(new ViewStreamMessage.PaletteEntry(3, "minecraft:stone"),
                new ViewStreamMessage.PaletteEntry(4, "minecraft:dirt"))));
            store = new ClientMeshSections(palette, 1024 * 1024);
            store.begin(7, 1, BOUNDS, 64);
            bind(7);
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    for (int x = -1; x <= 1; x++) {
                        store.put(new ViewStreamMessage.MeshSection(7, 1, x, y, z, 1, 3,
                            Brick.single(0, 3), SectionBiomes.NONE));
                    }
                }
            }
            open(7);
        }

        private void bind(int key) throws Exception {
            Class<?> identityType = Class.forName(ClientMeshSections.class.getName() + "$Identity");
            Constructor<?> constructor = identityType.getDeclaredConstructor(EnvironmentState.class, long.class, long.class);
            constructor.setAccessible(true);
            Object identity = constructor.newInstance(environment, 1L, 77L);
            Method bind = ClientMeshSections.class.getDeclaredMethod("bind", int.class, identityType);
            bind.setAccessible(true);
            bind.invoke(store, key, identity);
        }

        private void open(int key) throws Exception {
            view = store.view(key);
            scene = mock(PortalScene.class, CALLS_REAL_METHODS);
            ApertureDescriptor geometry = fixtureScene().geometry();
            when(scene.geometry()).thenReturn(geometry);
            when(scene.sectionKeys()).thenAnswer(invocation -> view.sectionKeys());
            when(scene.revision(anyLong())).thenAnswer(invocation -> {
                ClientMeshSections.Section section = view.section(invocation.getArgument(0));
                return section == null ? -1L : section.revision();
            });
            when(scene.meshContext()).thenAnswer(invocation -> ClientMeshWorld.meshContext(snapshot(0L)));
            when(scene.meshIdentity(anyLong())).thenAnswer(invocation -> ClientMeshWorld.meshIdentity(snapshot(invocation.getArgument(0))));
            renderer.replaceScene(key, scene);
            portal = ((Map<?, ?>) field(renderer, "portals")).get(key);
            CameraRenderState camera = new CameraRenderState();
            camera.pos = Vec3.ZERO;
            camera.blockPos = BlockPos.ZERO;
            set(renderer, "camera", camera);
            set(portal, "camera", camera);
        }

        private ClientMeshWorld.Snapshot snapshot(long key) {
            return new ClientMeshWorld.Snapshot(view, key, registry, environment, 0);
        }

        @SuppressWarnings("unchecked")
        private Object cache(long key) throws Exception {
            Class<?> sectionType = Class.forName(ClientPortalRenderer.class.getName() + "$Section");
            Constructor<?> constructor = sectionType.getDeclaredConstructor(long.class, long.class);
            constructor.setAccessible(true);
            Object section = constructor.newInstance(key, view.section(key).revision());
            set(section, "identity", ClientMeshWorld.meshIdentity(snapshot(key)));
            PortalGpuMesh mesh = mock(PortalGpuMesh.class);
            ((EnumMap<ChunkSectionLayer, PortalGpuMesh>) field(section, "layers")).put(ChunkSectionLayer.SOLID, mesh);
            meshes.put(key, mesh);
            sections().put(key, section);
            return section;
        }

        private void retainOtherContext(long key) throws Exception {
            retain(key, identity((byte) 1), mock(PortalGpuMesh.class));
        }

        @SuppressWarnings("unchecked")
        private Object retain(long key, PortalScene.MeshIdentity identity, PortalGpuMesh mesh) throws Exception {
            Class<?> sectionType = Class.forName(ClientPortalRenderer.class.getName() + "$Section");
            Constructor<?> constructor = sectionType.getDeclaredConstructor(long.class, long.class);
            constructor.setAccessible(true);
            Object section = constructor.newInstance(key, 1L);
            set(section, "identity", identity);
            ((EnumMap<ChunkSectionLayer, PortalGpuMesh>) field(section, "layers")).put(ChunkSectionLayer.SOLID, mesh);
            when(scene.revision(key)).thenReturn(1L);
            set(renderer, "gpuBytes", (long) field(renderer, "gpuBytes") + mesh.bytes());
            Method retain = ClientPortalRenderer.class.getDeclaredMethod("retainMesh", portal.getClass(), sectionType);
            retain.setAccessible(true);
            assertTrue((boolean) retain.invoke(renderer, portal, section));
            return section;
        }

        private void reopen() throws Exception {
            renderer.remove(7);
            store.remove(7);
            store.begin(11, 1, BOUNDS, 64);
            bind(11);
            open(11);
            ((LongSet) field(portal, "dirty")).addAll(meshes.keySet());
        }

        private void restore() throws Exception {
            Method restore = ClientPortalRenderer.class.getDeclaredMethod("restoreRetainedMeshes", portal.getClass());
            restore.setAccessible(true);
            restore.invoke(renderer, portal);
        }

        @SuppressWarnings("unchecked")
        private Long2ObjectOpenHashMap<Object> sections() throws Exception {
            return (Long2ObjectOpenHashMap<Object>) field(portal, "sections");
        }

        private Map<?, ?> retained() throws Exception {
            return (Map<?, ?>) field(renderer, "retainedMeshes");
        }

        @Override
        public void close() throws Exception {
            set(renderer, "pendingBuilds", 0);
            renderer.clear();
        }
    }

    private static final class GpuFixture implements AutoCloseable {
        private final GpuDevice device = mock(GpuDevice.class);
        private final List<GpuBuffer> buffers = new ArrayList<>();
        private final ByteBufferBuilder allocation = new ByteBufferBuilder(256);
        private final MockedStatic<RenderSystem> system = mockStatic(RenderSystem.class);
        private final MeshData data;

        private GpuFixture(long bufferBytes) {
            system.when(RenderSystem::getDevice).thenReturn(device);
            when(device.createBuffer(any(), anyInt(), any(ByteBuffer.class))).thenAnswer(invocation -> {
                ByteBuffer bytes = invocation.getArgument(2);
                GpuBuffer buffer = mock(GpuBuffer.class);
                when(buffer.size()).thenReturn(bufferBytes == 0L ? (long) bytes.remaining() : bufferBytes);
                buffers.add(buffer);
                return buffer;
            });
            BufferBuilder builder = new BufferBuilder(allocation, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION);
            builder.addVertex(0, 0, 0);
            builder.addVertex(1, 0, 0);
            builder.addVertex(1, 1, 0);
            builder.addVertex(0, 1, 0);
            data = builder.buildOrThrow();
        }

        private PortalGpuMesh mesh() {
            return new PortalGpuMesh(data, null);
        }

        @Override
        public void close() {
            data.close();
            allocation.close();
            system.close();
        }
    }

    private static ClientTravelScene.MeshIdentity largeIdentity() {
        byte[][] columns = new byte[9][];
        columns[0] = new byte[17 * 1024 * 1024];
        for (int index = 1; index < columns.length; index++) {
            columns[index] = new byte[0];
        }
        return new ClientTravelScene.MeshIdentity(identity((byte) 1).world(), columns);
    }

    private static ClientTravelScene.MeshIdentity identity(byte last) {
        byte[][] columns = new byte[9][];
        for (int index = 0; index < columns.length; index++) {
            columns[index] = new byte[]{1, 2, 3};
        }
        columns[8][2] = last;
        return new ClientTravelScene.MeshIdentity(new TravelMessage.TravelWorld("minecraft:overworld", "minecraft:overworld",
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
