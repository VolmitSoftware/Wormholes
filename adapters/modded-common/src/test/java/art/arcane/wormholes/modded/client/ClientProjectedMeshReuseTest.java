package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.client.render.ClientPortalRenderer;
import art.arcane.wormholes.modded.client.render.PortalEnvironmentTest;
import art.arcane.wormholes.modded.client.render.PortalScene;
import art.arcane.optics.stream.Brick;
import art.arcane.optics.stream.ProjectionEnvironment;
import art.arcane.optics.stream.ViewStreamMessage;
import art.arcane.optics.stream.SectionBiomes;
import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.math.BlockBox;
import art.arcane.optics.math.Face;
import art.arcane.optics.frame.OpticTransform;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import art.arcane.wormholes.modded.client.render.PortalTerrainMaterials;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.ObjLongConsumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.any;
import art.arcane.wormholes.network.client.TravelMessage;

public class ClientProjectedMeshReuseTest extends MinecraftTestBase {
    private static final BlockBox BOUNDS = new BlockBox(-32, -32, -32, 96, 96, 96);

    @Test
    public void droppedPortalReopensWithHistorySectionsAndTransfersExactGpuOwnershipToNewKey() throws Exception {
        try (Fixture fixture = new Fixture()) {
            ClientMeshSections.View old = fixture.store.view(7);
            ClientMeshWorld compiled = fixture.world(old, 0L);
            PortalScene.MeshIdentity captured = compiled.meshIdentity();
            fixture.install(7, old, 0L, captured);
            GpuBuffer clip = mock(GpuBuffer.class);
            set(fixture.gpuSection, "clip", clip);
            fixture.store.remove(7);
            when(fixture.scene.meshContext()).thenThrow(new IllegalStateException("removed scene environment"));
            when(fixture.scene.meshIdentity(anyLong())).thenThrow(new IllegalStateException("removed scene environment"));
            fixture.renderer.remove(7);
            verify(clip).close();
            verify(fixture.mesh, never()).close();
            fixture.store.begin(11, 1, BOUNDS, 27);
            fixture.store.bind(11, new ClientMeshSections.Identity(fixture.environment, 1, 77));
            ClientMeshSections.View reopened = fixture.store.view(11);
            assertSame(old.section(0L), reopened.section(0L));
            Object destination = fixture.portal(11, reopened);
            when(fixture.scene.meshIdentity(anyLong())).thenThrow(new IllegalStateException("unnecessary proof allocation"));
            assertTrue(fixture.reuse(destination, 0L));
            assertSame(fixture.gpuSection, fixture.sections(destination).get(0L));
            assertSame(captured, field(fixture.gpuSection, "identity"));
            assertTrue(fixture.cache().isEmpty());
            assertEquals(0L, field(fixture.renderer, "retainedProofBytes"));
            verify(fixture.mesh, never()).close();
        }
    }

    @Test
    public void newOrChangedNeighborRejectsOldSnapshotEvenWhenCenterRevisionIsUnchanged() throws Exception {
        try (Fixture fixture = new Fixture()) {
            ClientMeshSections.View view = fixture.store.view(7);
            ClientMeshWorld compiled = fixture.world(view, 0L);
            int centerRevision = view.section(0L).revision();
            fixture.install(7, view, 0L, compiled.meshIdentity());
            fixture.renderer.remove(7);
            fixture.put(1, 0, 0, 1, 3);
            assertEquals(centerRevision, view.section(0L).revision());
            assertFalse(compiled.meshIdentity().same(fixture.world(view, 0L).meshIdentity()));
            Object destination = fixture.portal(11, view);
            assertFalse(fixture.reuse(destination, 0L));
            assertTrue(fixture.cache().isEmpty());
            verify(fixture.mesh).close();
        }
    }

    @Test
    public void sharedSectionPayloadsCountOnceAndEvictionOrAdoptionReleasesOnlyItsOwnReferences() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.put(1, 0, 0, 1, 3);
            ClientMeshSections.View view = fixture.store.view(7);
            PortalScene.MeshIdentity first = fixture.world(view, 0L).meshIdentity();
            fixture.install(7, view, 0L, first);
            fixture.renderer.remove(7);
            long neighbor = SectionPos.asLong(1, 0, 0);
            PortalScene.MeshIdentity second = fixture.world(view, neighbor).meshIdentity();
            fixture.install(11, view, neighbor, second);
            fixture.renderer.remove(11);
            @SuppressWarnings("unchecked")
            IdentityHashMap<Object, Integer> references = (IdentityHashMap<Object, Integer>) field(fixture.renderer, "retainedProofReferences");
            assertEquals(Integer.valueOf(2), references.get(view.section(0L)));
            assertEquals(Integer.valueOf(2), references.get(view.section(neighbor)));
            long expected = 2L * (112 + 16 + 27 * 8) + view.section(0L).bytes() + view.section(neighbor).bytes();
            assertEquals(expected, field(fixture.renderer, "retainedProofBytes"));
            Object destination = fixture.portal(12, view);
            assertTrue(fixture.reuse(destination, 0L));
            assertEquals(Integer.valueOf(1), references.get(view.section(0L)));
            Method evict = ClientPortalRenderer.class.getDeclaredMethod("evictRetainedMesh");
            evict.setAccessible(true);
            evict.invoke(fixture.renderer);
            assertEquals(0L, field(fixture.renderer, "retainedProofBytes"));
            assertTrue(references.isEmpty());
        }
    }

    @Test
    public void invalidatingDisplayedGpuKeepsGeometryButDropsItsProofAndRejectsRetention() throws Exception {
        try (Fixture fixture = new Fixture()) {
            ClientMeshSections.View view = fixture.store.view(7);
            fixture.install(7, view, 0L, fixture.world(view, 0L).meshIdentity());
            fixture.renderer.invalidate(7, 0L, false);
            assertTrue(field(fixture.gpuSection, "identity") == null);
            verify(fixture.mesh, never()).close();
            fixture.renderer.remove(7);
            assertTrue(fixture.cache().isEmpty());
            verify(fixture.mesh).close();
        }
    }

    @Test
    public void asynchronousCompletionKeepsSubmittedSnapshotAndNeverReadsFreshProof() throws Exception {
        try (Fixture fixture = new Fixture()) {
            ClientMeshSections.View view = fixture.store.view(7);
            PortalScene.MeshIdentity submitted = fixture.world(view, 0L).meshIdentity();
            Object portal = fixture.portal(7, view);
            fixture.put(1, 0, 0, 1, 3);
            when(fixture.scene.meshIdentity(anyLong())).thenThrow(new IllegalStateException("fresh proof during completion"));
            fixture.complete(portal, submitted);
            assertSame(submitted, field(fixture.sections(portal).get(0L), "identity"));
            fixture.renderer.remove(7);
            Object destination = fixture.portal(11, view);
            assertFalse(fixture.reuse(destination, 0L));
            assertTrue(fixture.cache().isEmpty());
        }
    }

    @Test
    public void neighborRedirtyDuringAsyncCompletionDisplaysSnapshotWithoutRetainableProof() throws Exception {
        try (Fixture fixture = new Fixture()) {
            ClientMeshSections.View view = fixture.store.view(7);
            PortalScene.MeshIdentity submitted = fixture.world(view, 0L).meshIdentity();
            fixture.install(7, view, 0L, submitted);
            Object portal = ((Map<?, ?>) field(fixture.renderer, "portals")).get(7);
            ((LongSet) field(portal, "building")).add(0L);
            fixture.put(1, 0, 0, 1, 3);
            fixture.renderer.invalidate(7, 0L, false);
            verify(fixture.mesh, never()).close();
            assertSame(fixture.gpuSection, fixture.sections(portal).get(0L));
            assertTrue(field(fixture.gpuSection, "identity") == null);
            fixture.complete(portal, submitted);
            verify(fixture.mesh).close();
            assertTrue(field(fixture.sections(portal).get(0L), "identity") == null);
            assertTrue(((LongSet) field(portal, "dirty")).contains(0L));
            fixture.renderer.remove(7);
            assertTrue(fixture.cache().isEmpty());
        }
    }

    @Test
    public void absentContextOrChangedMaterialsAvoidsExactSnapshotWorkAndReloadClosesCache() throws Exception {
        try (Fixture fixture = new Fixture()) {
            ClientMeshSections.View view = fixture.store.view(7);
            Object destination = fixture.portal(7, view);
            when(fixture.scene.meshIdentity(anyLong())).thenThrow(new IllegalStateException("unnecessary snapshot"));
            assertFalse(fixture.reuse(destination, 0L));
            fixture.install(7, view, 0L, fixture.world(view, 0L).meshIdentity());
            fixture.renderer.remove(7);
            destination = fixture.portal(11, view);
            set(destination, "materials", new PortalTerrainMaterials(true, Map.of(), 2, PortalTerrainMaterials.Lighting.VANILLA));
            when(fixture.scene.meshIdentity(anyLong())).thenThrow(new IllegalStateException("unnecessary snapshot"));
            assertFalse(fixture.reuse(destination, 0L));
            verify(fixture.mesh, never()).close();
            fixture.renderer.resourceReload();
            verify(fixture.mesh).close();
            assertTrue(fixture.cache().isEmpty());
            assertEquals(0L, field(fixture.renderer, "retainedProofBytes"));
        }
    }

    @Test
    public void proofMemoryBoundEvictsAndReleasesAllReferences() throws Exception {
        try (Fixture fixture = new Fixture()) {
            ClientMeshSections.View view = fixture.store.view(7);
            PortalScene.MeshIdentity large = mock(PortalScene.MeshIdentity.class);
            Object token = new Object();
            doAnswer(call -> {
                ObjLongConsumer<Object> visitor = call.getArgument(0);
                visitor.accept(token, 33L * 1024 * 1024);
                return null;
            }).when(large).references(any());
            fixture.install(7, view, 0L, large);
            fixture.renderer.remove(7);
            verify(fixture.mesh).close();
            assertTrue(fixture.cache().isEmpty());
            assertEquals(0L, field(fixture.renderer, "retainedProofBytes"));
            assertTrue(((Map<?, ?>) field(fixture.renderer, "retainedProofReferences")).isEmpty());
        }
    }

    @Test
    public void travelAndProjectedMeshesShareByteBudgetsAndClearReleasesEveryReference() throws Exception {
        try (Fixture fixture = new Fixture()) {
            ClientMeshSections.View view = fixture.store.view(7);
            PortalScene.MeshIdentity projected = fixture.world(view, 0L).meshIdentity();
            Class<?> travelType = Class.forName("art.arcane.wormholes.modded.client.render.ClientTravelScene$MeshIdentity");
            Constructor<?> travelConstructor = travelType.getDeclaredConstructor(TravelMessage.TravelWorld.class, byte[][].class);
            travelConstructor.setAccessible(true);
            PortalScene.MeshIdentity travel = (PortalScene.MeshIdentity) travelConstructor.newInstance(new TravelMessage.TravelWorld(
                "minecraft:overworld", "minecraft:overworld", 7, false, false, 63, -64, 384), new byte[][]{new byte[]{1}});
            Object portal = fixture.portal(7, view);
            when(fixture.scene.revision(anyLong())).thenReturn(1L);
            Class<?> sectionType = Class.forName(ClientPortalRenderer.class.getName() + "$Section");
            Constructor<?> constructor = sectionType.getDeclaredConstructor(long.class, long.class);
            constructor.setAccessible(true);
            Method retain = ClientPortalRenderer.class.getDeclaredMethod("retainMesh", portal.getClass(), sectionType);
            retain.setAccessible(true);
            Object old = constructor.newInstance(-1L, 1L);
            set(old, "identity", travel);
            assertTrue((boolean) retain.invoke(fixture.renderer, portal, old));
            for (int key = 0; key < 2048; key++) {
                Object section = constructor.newInstance((long) key, 1L);
                set(section, "identity", projected);
                assertTrue((boolean) retain.invoke(fixture.renderer, portal, section));
            }
            assertEquals(2049, fixture.cache().size());
            assertTrue((long) field(fixture.renderer, "retainedProofBytes") < 32L * 1024 * 1024);
            assertEquals(0L, field(fixture.renderer, "gpuBytes"));
            @SuppressWarnings("unchecked")
            IdentityHashMap<Object, Integer> references = (IdentityHashMap<Object, Integer>) field(fixture.renderer, "retainedProofReferences");
            assertEquals(Integer.valueOf(1), references.get(travel));
            assertEquals(Integer.valueOf(2048), references.get(projected));
            assertEquals(Integer.valueOf(2048), references.get(view.section(0L)));
            fixture.renderer.clear();
            assertTrue(fixture.cache().isEmpty());
            assertTrue(references.isEmpty());
            assertEquals(0L, field(fixture.renderer, "retainedProofBytes"));
        }
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

    private static final class Fixture implements AutoCloseable {
        private final ClientPortalRenderer renderer = ClientPortalRenderer.instance();
        private final ClientMeshSections store;
        private final ProjectionEnvironment environment = PortalEnvironmentTest.environment(OpticTransform.IDENTITY);
        private final RegistryAccess registry = mock(RegistryAccess.class);
        private PortalScene scene;
        private Object gpuSection;
        private AutoCloseable mesh;

        @SuppressWarnings("unchecked")
        private Fixture() throws Exception {
            renderer.clear();
            Registry<Biome> biomes = mock(Registry.class);
            Biome biome = mock(Biome.class);
            when(biomes.getOptional(Identifier.parse("minecraft:plains"))).thenReturn(Optional.of(biome));
            when(registry.lookupOrThrow(Registries.BIOME)).thenReturn(biomes);
            ClientPalette palette = new ClientPalette(BuiltInRegistries.BLOCK);
            palette.apply(new ViewStreamMessage.Palette(List.of(new ViewStreamMessage.PaletteEntry(3, "minecraft:stone"))));
            store = new ClientMeshSections(palette, 1024 * 1024);
            store.epoch(1);
            store.begin(7, 1, BOUNDS, 27);
            store.bind(7, new ClientMeshSections.Identity(environment, 1, 77));
            put(0, 0, 0, 1, 3);
        }

        private void put(int x, int y, int z, int revision, int state) throws Exception {
            store.put(new ViewStreamMessage.MeshSection(7, 1, x, y, z, revision, state, Brick.single(0, state),
                new SectionBiomes(List.of("minecraft:plains"), new byte[0])));
        }

        private ClientMeshWorld.Snapshot snapshot(ClientMeshSections.View view, long key) {
            return new ClientMeshWorld.Snapshot(view, key, registry, environment, 1);
        }

        private ClientMeshWorld world(ClientMeshSections.View view, long key) {
            return new ClientMeshWorld(snapshot(view, key));
        }

        private Object portal(int key, ClientMeshSections.View view) throws Exception {
            scene = mock(PortalScene.class);
            ApertureDescriptor geometry = new ApertureDescriptor(0, 64, 0, Face.S.ordinal(), true, 0, true,
                1, 2, new long[]{3}, 0, 0, 1, 64, 3, 0, 0, 0, 0, 0, ApertureDescriptor.KIND_FRAME, 0.0D, 0, 77, List.of());
            when(scene.geometry()).thenReturn(geometry);
            when(scene.sectionKeys()).thenReturn(new LongArrayList());
            when(scene.revision(anyLong())).thenAnswer(call -> {
                ClientMeshSections.Section section = view.section(call.getArgument(0));
                return section == null ? -1L : (long) section.revision();
            });
            when(scene.meshContext()).thenAnswer(call -> ClientMeshWorld.meshContext(snapshot(view, 0L)));
            when(scene.meshIdentity(anyLong())).thenAnswer(call -> ClientMeshWorld.meshIdentity(snapshot(view, call.getArgument(0))));
            when(scene.matchesMeshIdentity(anyLong(), any())).thenAnswer(call ->
                ClientMeshWorld.matchesMeshIdentity(view, call.getArgument(0), registry, scene.meshContext(), call.getArgument(1)));
            renderer.replaceScene(key, scene);
            return ((Map<?, ?>) field(renderer, "portals")).get(key);
        }

        @SuppressWarnings("unchecked")
        private void install(int portalKey, ClientMeshSections.View view, long key, PortalScene.MeshIdentity identity) throws Exception {
            Object portal = portal(portalKey, view);
            Class<?> section = Class.forName(ClientPortalRenderer.class.getName() + "$Section");
            Constructor<?> constructor = section.getDeclaredConstructor(long.class, long.class);
            constructor.setAccessible(true);
            gpuSection = constructor.newInstance(key, (long) view.section(key).revision());
            set(gpuSection, "identity", identity);
            Class<?> meshType = Class.forName("art.arcane.wormholes.modded.client.render.PortalGpuMesh");
            mesh = (AutoCloseable) mock(meshType);
            ((EnumMap<ChunkSectionLayer, Object>) field(gpuSection, "layers")).put(ChunkSectionLayer.SOLID, mesh);
            sections(portal).put(key, gpuSection);
        }

        @SuppressWarnings("unchecked")
        private Long2ObjectOpenHashMap<Object> sections(Object portal) throws Exception {
            return (Long2ObjectOpenHashMap<Object>) field(portal, "sections");
        }

        private boolean reuse(Object portal, long key) throws Exception {
            Method reuse = ClientPortalRenderer.class.getDeclaredMethod("reuseMesh", portal.getClass(), long.class);
            reuse.setAccessible(true);
            return (boolean) reuse.invoke(renderer, portal, key);
        }

        private void complete(Object portal, PortalScene.MeshIdentity identity) throws Exception {
            Class<?> meshType = Class.forName("art.arcane.wormholes.modded.client.render.PortalSectionMesh");
            Object completed = mock(meshType);
            Method meshes = meshType.getDeclaredMethod("meshes");
            meshes.setAccessible(true);
            when(meshes.invoke(completed)).thenReturn(Map.of());
            ((LongSet) field(portal, "building")).add(0L);
            set(renderer, "pendingBuilds", 1);
            Method finish = ClientPortalRenderer.class.getDeclaredMethod("finish", portal.getClass(), long.class,
                long.class, int.class, PortalScene.MeshIdentity.class, meshType, Throwable.class);
            finish.setAccessible(true);
            finish.invoke(renderer, portal, 0L, 1L, field(portal, "generation"), identity, completed, null);
            assertEquals(0, field(renderer, "pendingBuilds"));
        }

        private Map<?, ?> cache() throws Exception {
            return (Map<?, ?>) field(renderer, "retainedMeshes");
        }

        @Override
        public void close() {
            renderer.clear();
        }
    }
}
