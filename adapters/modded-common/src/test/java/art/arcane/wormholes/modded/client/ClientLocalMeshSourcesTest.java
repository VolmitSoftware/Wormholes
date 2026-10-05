package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.client.render.PortalEnvironmentTest;
import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.network.client.ClientViewCapability;
import art.arcane.wormholes.network.client.ClientViewEnvironment;
import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.wormholes.network.client.ClientViewProtocol;
import art.arcane.wormholes.network.client.Brick;
import art.arcane.wormholes.network.client.SectionBiomes;
import art.arcane.wormholes.render.client.ClientPortalGeometry;
import art.arcane.wormholes.render.client.session.ClientMeshPlan;
import art.arcane.wormholes.render.plate.PlateBox;
import art.arcane.wormholes.util.Direction;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderOwner;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.junit.Test;

import java.util.ArrayList;
import java.lang.reflect.Field;
import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.clearInvocations;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ClientLocalMeshSourcesTest extends MinecraftTestBase {
    private static final PlateBox BOUNDS = new PlateBox(0, 0, 0, 16, 16, 16);

    @Test
    public void populatedServerAndHistorySectionsSkipCaptureWhileMissingPreviewsAndLocalRefreshStillWork() throws Exception {
        for (boolean history : new boolean[] {false, true}) {
            Fixture fixture = new Fixture();
            fixture.awaitSection();
            ClientMeshSections.Section original = fixture.session.meshes().view(1).section(0L);
            Object snapshot = snapshot(fixture, "minecraft:overworld", 0L);
            Field statesField = snapshot.getClass().getDeclaredField("states");
            statesField.setAccessible(true);
            LevelChunkSection states = (LevelChunkSection) statesField.get(snapshot);
            fixture.session.clearPortals();
            int populatedKey = history ? 1 : 2;
            fixture.add(populatedKey);
            if (!history) {
                fixture.session.handle(new ClientViewMessage.MeshSection(populatedKey, 1, 0, 0, 0,
                    1, 0, Brick.empty(0), SectionBiomes.NONE), fixture.sink);
            }
            ClientMeshSections.Section populated = fixture.session.meshes().view(populatedKey).section(0L);
            assertNotNull(populated);
            if (history) {
                assertSame(original, populated);
            }
            Field revisionField = ClientMeshSections.class.getDeclaredField("revision");
            revisionField.setAccessible(true);
            int revision = revisionField.getInt(fixture.session.meshes());
            clearInvocations(states);
            ClientLevel current = mock(ClientLevel.class);
            when(current.dimension()).thenReturn(Level.NETHER);
            for (int tick = 0; tick < 20; tick++) {
                fixture.sources.update(fixture.session, current, 0, 0, 0);
            }
            verify(states, never()).getBlockState(anyInt(), anyInt(), anyInt());
            assertEquals(revision, revisionField.getInt(fixture.session.meshes()));
            assertSame(populated, fixture.session.meshes().view(populatedKey).section(0L));
            assertTrue(dependents(fixture, populatedKey).isEmpty());
            fixture.add(3);
            for (int tick = 0; tick < 100 && fixture.session.meshes().view(3).section(0L) == null; tick++) {
                fixture.sources.update(fixture.session, current, 0, 0, 0);
            }
            assertNotNull(fixture.session.meshes().view(3).section(0L));
            assertTrue(revisionField.getInt(fixture.session.meshes()) > revision);
            assertFalse(dependents(fixture, 3).isEmpty());
            fixture.state.set(Blocks.GOLD_BLOCK.defaultBlockState());
            fixture.sources.blockChanged(fixture.level, BlockPos.ZERO);
            for (int tick = 0; tick < 100; tick++) {
                fixture.update();
                ClientMeshSections.Section section = fixture.session.meshes().view(populatedKey).section(0L);
                if (section.state(0) == Blocks.GOLD_BLOCK.defaultBlockState()) {
                    break;
                }
            }
            assertSame(Blocks.GOLD_BLOCK.defaultBlockState(), fixture.session.meshes().view(populatedKey).section(0L).state(0));
        }
    }

    @Test
    public void vanillaVisitedChunksWithoutAnyPortalSurviveDimensionDetachAsHashValidatedVisualPreviews() throws Exception {
        Fixture fixture = new Fixture();
        fixture.session.clearPortals();
        fixture.update();
        for (LevelChunk chunk : fixture.chunks.values()) {
            when(chunk.getMinSectionY()).thenReturn(-1);
            when(chunk.getSectionsCount()).thenReturn(3);
            fixture.sources.chunkChanged(fixture.level, chunk.getPos().x(), chunk.getPos().z());
        }
        for (int tick = 0; tick < 200; tick++) {
            fixture.update();
        }
        assertTrue(fixture.sources.bytes() > 0);
        assertTrue(fixture.session.portals().isEmpty());
        long capturedBytes = fixture.sources.bytes();
        ClientLevel destination = mock(ClientLevel.class);
        when(destination.dimension()).thenReturn(Level.NETHER);
        fixture.add(2);
        fixture.messages.clear();
        for (int tick = 0; tick < 100 && fixture.session.meshes().view(2).section(0L) == null; tick++) {
            fixture.sources.update(fixture.session, destination, 0, 0, 0);
        }
        ClientMeshSections.Section preview = fixture.session.meshes().view(2).section(0L);
        assertNotNull(preview);
        assertSame(Blocks.STONE.defaultBlockState(), preview.state(0));
        assertEquals(capturedBytes, fixture.sources.bytes());
        assertFalse(fixture.messages.stream().anyMatch(message -> message instanceof ClientViewMessage.MeshLocal local && local.available()));
        fixture.sources.update(fixture.session, destination, 0, 0, 0);
        ClientViewMessage.MeshCached claim = (ClientViewMessage.MeshCached) fixture.messages.stream()
            .filter(message -> message instanceof ClientViewMessage.MeshCached).findFirst().orElseThrow();
        fixture.session.handle(new ClientViewMessage.MeshReuse(2, 1, 0, 0, 0, 1, claim.claims().getFirst().hash()), fixture.sink);
        assertSame(preview, fixture.session.meshes().view(2).section(0L));
        verify(fixture.sink).meshAck(new ClientViewMessage.MeshAck(2, 1, 0, 0, 0, 1));
        fixture.sources.clear();
        fixture.session.clearPortals();
        fixture.session.accept(new ClientViewMessage.Accept(2, ClientViewCapability.ALL, 20, ClientViewProtocol.DEFAULT_MAX_FRAME_BYTES, 8, 8));
        fixture.add(3);
        fixture.sources.update(fixture.session, destination, 0, 0, 0);
        assertEquals(0, fixture.sources.bytes());
        assertNull(fixture.session.meshes().view(3).section(0L));
    }

    @Test
    public void visitedWorldSnapshotsCannotPreviewAnotherWorldWithTheSameDimensionType() throws Exception {
        Fixture fixture = new Fixture();
        fixture.session.clearPortals();
        fixture.update();
        LevelChunk chunk = fixture.chunks.get(ChunkPos.pack(0, 0));
        when(chunk.getMinSectionY()).thenReturn(0);
        when(chunk.getSectionsCount()).thenReturn(1);
        fixture.sources.chunkChanged(fixture.level, 0, 0);
        for (int tick = 0; tick < 20 && fixture.sources.bytes() == 0; tick++) {
            fixture.update();
        }
        assertTrue(fixture.sources.bytes() > 0);
        long copiedBytes = fixture.sources.bytes();
        ClientLevel current = mock(ClientLevel.class);
        DimensionType dimensionType = fixture.level.dimensionType();
        when(current.dimensionType()).thenReturn(dimensionType);
        when(current.dimension()).thenReturn(ResourceKey.create(Registries.DIMENSION, Identifier.parse("test:current_pocket")));
        fixture.add(2);
        ClientViewEnvironment original = PortalEnvironmentTest.environment(PortalEnvironmentTest.identity());
        ClientViewEnvironment.World previous = original.world();
        ClientViewEnvironment.World unvisited = new ClientViewEnvironment.World("test:unvisited_pocket", previous.clockTime(),
            previous.biomeKey(), previous.seaLevel(), previous.blockLight(), previous.skyLight(), previous.logicalHeight(),
            previous.hasCeiling(), previous.ambientLight(), previous.eyeMedium(), previous.hasFixedTime());
        fixture.session.handle(new ClientViewMessage.Environment(2, new ClientViewEnvironment(original.gameTime(), original.sky(),
            original.fog(), original.lighting(), original.clouds(), original.transform(), original.dimension(), unvisited)), fixture.sink);
        fixture.messages.clear();
        for (int tick = 0; tick < 20; tick++) {
            fixture.sources.update(fixture.session, current, 0, 0, 0);
        }
        assertEquals(copiedBytes, fixture.sources.bytes());
        assertNull(fixture.session.meshes().view(2).section(0L));
        assertFalse(fixture.messages.stream().anyMatch(message -> message instanceof ClientViewMessage.MeshCached));
    }

    @Test
    public void nativeChunkReceiptInvalidationQueuesBoundedCopyWorkInsteadOfCopyingInTheCallback() throws Exception {
        Fixture fixture = new Fixture();
        fixture.session.clearPortals();
        fixture.update();
        LevelChunk chunk = fixture.chunks.get(ChunkPos.pack(0, 0));
        when(chunk.getMinSectionY()).thenReturn(0);
        when(chunk.getSectionsCount()).thenReturn(1);
        clearInvocations(chunk);
        fixture.sources.chunkChanged(fixture.level, 0, 0);
        verify(chunk, never()).getSection(anyInt());
        assertEquals(0, fixture.sources.bytes());
        for (int tick = 0; tick < 20 && fixture.sources.bytes() == 0; tick++) {
            fixture.update();
        }
        assertTrue(fixture.sources.bytes() > 0);
        fixture.sources.chunkChanged(fixture.level, 0, 0);
        assertEquals(0, fixture.sources.bytes());
        fixture.update();
        assertTrue(fixture.sources.bytes() > 0);
    }

    @Test
    public void inactiveRetainedSectionChangesInvalidateOnlyMatchingSnapshotsAndRefreshOnReturn() throws Exception {
        Fixture source = new Fixture();
        source.awaitSection();
        LevelChunk retainedChunk = source.chunks.get(ChunkPos.pack(0, 0));
        Object originalSnapshot = snapshot(source, "minecraft:overworld", 0L);
        long unrelatedSection = SectionPos.asLong(-1, 0, 0);
        Object unrelatedSnapshot = snapshot(source, "minecraft:overworld", unrelatedSection);
        assertNotNull(originalSnapshot);
        assertNotNull(unrelatedSnapshot);
        source.session.clearPortals();
        source.update();
        Fixture active = new Fixture();
        when(active.level.dimension()).thenReturn(Level.NETHER);
        ClientViewEnvironment environment = PortalEnvironmentTest.environment(PortalEnvironmentTest.identity());
        ClientViewEnvironment.World originalWorld = environment.world();
        ClientViewEnvironment.World nether = new ClientViewEnvironment.World("minecraft:the_nether", originalWorld.clockTime(),
            originalWorld.biomeKey(), originalWorld.seaLevel(), originalWorld.blockLight(), originalWorld.skyLight(),
            originalWorld.logicalHeight(), originalWorld.hasCeiling(), originalWorld.ambientLight(), originalWorld.eyeMedium(),
            originalWorld.hasFixedTime());
        active.session.handle(new ClientViewMessage.Environment(1, new ClientViewEnvironment(environment.gameTime(),
            environment.sky(), environment.fog(), environment.lighting(), environment.clouds(), environment.transform(),
            environment.dimension(), nether)), active.sink);
        for (int tick = 0; tick < 100 && active.session.meshes().view(1).section(0L) == null; tick++) {
            source.sources.update(active.session, active.level, 0, 0, 0);
        }
        ClientMeshSections.Section activeSection = active.session.meshes().view(1).section(0L);
        assertNotNull(activeSection);
        Object activeRoute = route(source, 1);
        Object activeSnapshot = snapshot(source, "minecraft:the_nether", 0L);
        assertNotNull(activeSnapshot);
        long beforeBytes = source.sources.bytes();
        source.state.set(Blocks.GOLD_BLOCK.defaultBlockState());
        HolderOwner<Biome> owner = mock(HolderOwner.class);
        source.biome.set(Holder.Reference.createStandAlone(owner,
            ResourceKey.create(Registries.BIOME, Identifier.parse("minecraft:desert"))));
        LayerLightEventListener lighting = source.level.getLightEngine().getLayerListener(LightLayer.BLOCK);
        when(lighting.getDataLayerData(SectionPos.of(0, 0, 0))).thenReturn(new DataLayer(13));
        source.sources.blockChanged(source.level, BlockPos.ZERO);
        source.sources.blockChanged(source.level, BlockPos.ZERO);
        Field pendingField = ClientLocalMeshSources.class.getDeclaredField("pendingSnapshots");
        pendingField.setAccessible(true);
        assertTrue(((Map<?, ?>) pendingField.get(source.sources)).isEmpty());
        assertNull(snapshot(source, "minecraft:overworld", 0L));
        assertEquals(beforeBytes - 32_768L, source.sources.bytes());
        assertSame(unrelatedSnapshot, snapshot(source, "minecraft:overworld", unrelatedSection));
        assertSame(activeSnapshot, snapshot(source, "minecraft:the_nether", 0L));
        assertSame(activeRoute, route(source, 1));
        assertSame(activeSection, active.session.meshes().view(1).section(0L));
        active.session.clearPortals();
        source.sources.update(active.session, active.level, 0, 0, 0);
        source.add(1);
        for (int tick = 0; tick < 100; tick++) {
            source.update();
            ClientMeshSections.Section section = source.session.meshes().view(1).section(0L);
            if (section != null && section.state(0) == Blocks.GOLD_BLOCK.defaultBlockState()) {
                break;
            }
        }
        ClientMeshSections.Section refreshed = source.session.meshes().view(1).section(0L);
        assertNotNull(refreshed);
        assertSame(Blocks.GOLD_BLOCK.defaultBlockState(), refreshed.state(0));
        assertEquals(13, refreshed.light(false, 0));
        assertTrue(refreshed.biomes().palette().contains("minecraft:desert"));
        assertSame(retainedChunk, source.chunks.get(ChunkPos.pack(0, 0)));
        assertNotEquals(originalSnapshot, snapshot(source, "minecraft:overworld", 0L));
        assertSame(unrelatedSnapshot, snapshot(source, "minecraft:overworld", unrelatedSection));
    }

    private static Object route(Fixture fixture, int key) throws ReflectiveOperationException {
        Field field = ClientLocalMeshSources.class.getDeclaredField("routes");
        field.setAccessible(true);
        return ((Map<?, ?>) field.get(fixture.sources)).get(key);
    }

    private static Object snapshot(Fixture fixture, String world, long section) throws ReflectiveOperationException {
        Class<?> keyType = Class.forName(ClientLocalMeshSources.class.getName() + "$SnapshotKey");
        Constructor<?> constructor = keyType.getDeclaredConstructor(String.class, long.class);
        constructor.setAccessible(true);
        Field field = ClientLocalMeshSources.class.getDeclaredField("snapshots");
        field.setAccessible(true);
        return ((Map<?, ?>) field.get(fixture.sources)).get(constructor.newInstance(world, section));
    }

    @Test
    public void localFootprintMatchesTheSenderAndReplansAfterCameraMovement() throws Exception {
        Fixture fixture = new Fixture();
        ClientPortalGeometry geometry = fixture.session.portal(1).geometry().withDepth(128);
        fixture.session.handle(new ClientViewMessage.Portal(1, 2, geometry), fixture.sink);
        fixture.session.handle(new ClientViewMessage.MeshBegin(1, 2, ClientMeshPlan.bounds(geometry), ClientMeshPlan.capacity(geometry)), fixture.sink);
        fixture.session.handle(new ClientViewMessage.Environment(1, PortalEnvironmentTest.environment(PortalEnvironmentTest.identity())), fixture.sink);
        GeometryVector firstEye = new GeometryVector(0.5, 0.5, 5);
        fixture.sources.update(fixture.session, fixture.level, firstEye.x(), firstEye.y(), firstEye.z());
        List<Long> first = selection(fixture);
        assertEquals(selected(geometry, firstEye), first);
        assertTrue(first.size() < ClientMeshPlan.capacity(geometry));

        GeometryVector moved = new GeometryVector(48, 20, 16);
        for (int tick = 0; tick < 8; tick++) {
            fixture.sources.update(fixture.session, fixture.level, moved.x(), moved.y(), moved.z());
        }
        List<Long> next = selection(fixture);
        assertEquals(selected(geometry, moved), next);
        assertNotEquals(first, next);
    }

    private static List<Long> selected(ClientPortalGeometry geometry, GeometryVector eye) {
        List<Long> result = new ArrayList<>();
        for (ClientMeshPlan.Section section : ClientMeshPlan.visible(geometry, eye)) {
            result.add(SectionPos.asLong(section.x(), section.y(), section.z()));
        }
        return result;
    }

    private static List<Long> selection(Fixture fixture) throws ReflectiveOperationException {
        Field routesField = ClientLocalMeshSources.class.getDeclaredField("routes");
        routesField.setAccessible(true);
        Map<?, ?> routes = (Map<?, ?>) routesField.get(fixture.sources);
        Object route = routes.get(1);
        Field selectionField = route.getClass().getDeclaredField("selection");
        selectionField.setAccessible(true);
        List<Long> result = new ArrayList<>();
        for (Object section : (List<?>) selectionField.get(route)) {
            result.add((Long) section);
        }
        return result;
    }

    @Test
    public void blockPlacementPromotesItsExistingQueuedSectionAheadOfPendingRefreshes() throws Exception {
        Fixture fixture = new Fixture();
        fixture.awaitSection();
        Field routesField = ClientLocalMeshSources.class.getDeclaredField("routes");
        routesField.setAccessible(true);
        Object route = ((Map<?, ?>) routesField.get(fixture.sources)).get(1);
        Field dirtyField = route.getClass().getDeclaredField("dirty");
        dirtyField.setAccessible(true);
        LongLinkedOpenHashSet dirty = (LongLinkedOpenHashSet) dirtyField.get(route);
        for (int section = 1; section <= 32; section++) {
            dirty.add(SectionPos.asLong(section, 0, 0));
        }
        dirty.add(0L);
        fixture.marker.set(Blocks.GOLD_BLOCK.defaultBlockState());
        fixture.sources.blockChanged(fixture.level, new BlockPos(2, 1, 3));
        fixture.sources.blockChanged(fixture.level, new BlockPos(2, 1, 3));
        assertEquals(33, dirty.size());
        fixture.update();
        ClientMeshSections.Section changed = fixture.session.meshes().view(1).section(0L);
        assertSame(Blocks.GOLD_BLOCK.defaultBlockState(), changed.state(1 << 8 | 3 << 4 | 2));
    }

    @Test
    public void rotatedTranslatedMirrorUsesCellCentersForContentAndDirtyUpdates() throws Exception {
        Fixture fixture = new Fixture();
        fixture.marker.set(Blocks.DIRT.defaultBlockState());
        ClientViewEnvironment.Transform transform = new ClientViewEnvironment.Transform(Direction.S, Direction.U, Direction.E,
            new GeometryVector(4, 0, 6));
        fixture.session.handle(new ClientViewMessage.Environment(1, PortalEnvironmentTest.environment(transform)), fixture.sink);
        fixture.awaitSection();
        ClientMeshSections.Section original = fixture.session.meshes().view(1).section(0L);
        int displayCell = 1 << 8 | 8 << 4 | 7;
        assertSame(Blocks.DIRT.defaultBlockState(), original.state(displayCell));
        assertSame(Blocks.STONE.defaultBlockState(), original.state(displayCell - 1));

        fixture.marker.set(Blocks.GOLD_BLOCK.defaultBlockState());
        fixture.sources.blockChanged(fixture.level, new BlockPos(2, 1, 3));
        fixture.update();
        ClientMeshSections.Section changed = fixture.session.meshes().view(1).section(0L);
        assertSame(Blocks.GOLD_BLOCK.defaultBlockState(), changed.state(displayCell));
        assertTrue(changed.revision() > original.revision());
    }

    @Test
    public void nativePeersWithoutLocalCapabilityStillRenderLocalUpdatesWithoutSendingNewMessages() throws Exception {
        Fixture fixture = new Fixture();
        fixture.session.accept(new ClientViewMessage.Accept(1, ClientViewCapability.ALL & ~ClientViewCapability.LOCAL_MESH.mask(),
            20, ClientViewProtocol.DEFAULT_MAX_FRAME_BYTES, 7, 8));
        fixture.awaitSection();
        ClientMeshSections.Section original = fixture.session.meshes().view(1).section(0L);
        fixture.state.set(Blocks.DIRT.defaultBlockState());
        fixture.sources.blockChanged(fixture.level, net.minecraft.core.BlockPos.ZERO);
        fixture.update();

        assertSame(Blocks.DIRT.defaultBlockState(), fixture.session.meshes().view(1).section(0L).state(0));
        assertTrue(fixture.session.meshes().view(1).section(0L).revision() > original.revision());
        assertTrue(fixture.messages.isEmpty());
    }

    @Test
    public void loadedLocalDataUpdatesWithoutPacketsAndRetractsOnChunkUnload() throws Exception {
        Fixture fixture = new Fixture();
        fixture.awaitSection();
        ClientMeshSections.Section original = fixture.session.meshes().view(1).section(0L);
        assertSame(Blocks.STONE.defaultBlockState(), original.state(0));
        assertEquals("minecraft:plains", original.biomes().biome(0));
        assertTrue(fixture.messages.stream().anyMatch(message -> message instanceof ClientViewMessage.MeshLocal local && local.available()));
        fixture.state.set(Blocks.DIRT.defaultBlockState());
        fixture.sources.blockChanged(fixture.level, net.minecraft.core.BlockPos.ZERO);
        fixture.update();
        ClientMeshSections.Section changed = fixture.session.meshes().view(1).section(0L);
        assertSame(Blocks.DIRT.defaultBlockState(), changed.state(0));
        assertTrue(changed.revision() > original.revision());
        fixture.chunks.remove(ChunkPos.pack(0, 0));
        fixture.sources.chunkChanged(fixture.level, 0, 0);
        fixture.update();
        assertSame(changed, fixture.session.meshes().view(1).section(0L));
        assertTrue(fixture.messages.stream().anyMatch(message -> message instanceof ClientViewMessage.MeshLocal local && !local.available()));
    }

    @Test
    public void missingBiomeHaloAndWorldIdentityMismatchKeepNormalStreamAvailable() throws Exception {
        Fixture fixture = new Fixture();
        fixture.chunks.remove(ChunkPos.pack(-1, -1));
        for (int tick = 0; tick < 10; tick++) {
            fixture.update();
        }
        assertNull(fixture.session.meshes().view(1).section(0L));
        assertTrue(fixture.messages.isEmpty());
        when(fixture.level.dimension()).thenReturn(Level.NETHER);
        fixture.update();
        assertNull(fixture.session.meshes().view(1).section(0L));
        assertTrue(fixture.messages.isEmpty());
    }

    @Test
    public void unchangedOverlappingMirrorsShareSourceSnapshotsAndDoNotReannounceEveryTick() throws Exception {
        Fixture fixture = new Fixture();
        fixture.awaitSection();
        long capturedBytes = fixture.sources.bytes();
        int announcements = fixture.messages.size();
        ClientMeshSections.Section first = fixture.session.meshes().view(1).section(0L);
        for (int tick = 0; tick < 20; tick++) {
            fixture.update();
        }
        assertEquals(announcements, fixture.messages.size());
        assertSame(first, fixture.session.meshes().view(1).section(0L));
        fixture.add(2);
        for (int tick = 0; tick < 100 && fixture.session.meshes().view(2).section(0L) == null; tick++) {
            fixture.update();
        }
        assertNotNull(fixture.session.meshes().view(2).section(0L));
        assertEquals(capturedBytes, fixture.sources.bytes());
        fixture.sources.clear();
        assertEquals(0, fixture.sources.bytes());
        assertFalse(fixture.sources.localEntity(1, java.util.UUID.randomUUID()));
    }

    @Test
    public void overlappingMirrorsRefreshSharedHaloDependenciesWithoutDirectDisplayHits() throws Exception {
        Fixture fixture = new Fixture();
        fixture.add(2);
        fixture.awaitSection();
        for (int tick = 0; tick < 100 && fixture.session.meshes().view(2).section(0L) == null; tick++) {
            fixture.update();
        }
        ClientMeshSections.Section first = fixture.session.meshes().view(1).section(0L);
        ClientMeshSections.Section second = fixture.session.meshes().view(2).section(0L);
        assertNotNull(second);
        long halo = SectionPos.asLong(-1, -1, -1);
        assertTrue(dependents(fixture, 1).get(halo).contains(0L));
        assertTrue(dependents(fixture, 2).get(halo).contains(0L));
        HolderOwner<Biome> owner = mock(HolderOwner.class);
        fixture.biome.set(Holder.Reference.createStandAlone(owner,
            ResourceKey.create(Registries.BIOME, Identifier.parse("minecraft:desert"))));
        fixture.sources.blockChanged(fixture.level, new BlockPos(-1, -1, -1));
        fixture.sources.blockChanged(fixture.level, new BlockPos(-1, -1, -1));
        for (int tick = 0; tick < 100 && (fixture.session.meshes().view(1).section(0L) == first
            || fixture.session.meshes().view(2).section(0L) == second); tick++) {
            fixture.update();
        }
        assertEquals("minecraft:desert", fixture.session.meshes().view(1).section(0L).biomes().biome(0));
        assertEquals("minecraft:desert", fixture.session.meshes().view(2).section(0L).biomes().biome(0));
        assertTrue(fixture.session.meshes().view(1).section(0L).revision() > first.revision());
        assertTrue(fixture.session.meshes().view(2).section(0L).revision() > second.revision());
        assertEquals(1, dependents(fixture, 1).get(halo).size());
        assertEquals(1, dependents(fixture, 2).get(halo).size());
    }

    @Test
    public void removedDerivedMeshesReleaseDependenciesAndRecaptureRestoresOnlyCurrentRoutes() throws Exception {
        Fixture fixture = new Fixture();
        fixture.awaitSection();
        long halo = SectionPos.asLong(-1, -1, -1);
        assertTrue(dependents(fixture, 1).get(halo).contains(0L));
        LevelChunk center = fixture.chunks.remove(ChunkPos.pack(0, 0));
        fixture.sources.chunkChanged(fixture.level, 0, 0);
        for (int tick = 0; tick < 20 && !dependents(fixture, 1).isEmpty(); tick++) {
            fixture.update();
        }
        assertTrue(dependents(fixture, 1).isEmpty());
        fixture.chunks.put(ChunkPos.pack(0, 0), center);
        fixture.sources.chunkChanged(fixture.level, 0, 0);
        for (int tick = 0; tick < 100 && dependents(fixture, 1).isEmpty(); tick++) {
            fixture.update();
        }
        assertTrue(dependents(fixture, 1).get(halo).contains(0L));
        fixture.session.clearPortals();
        fixture.update();
        fixture.add(2);
        for (int tick = 0; tick < 100 && fixture.session.meshes().view(2).section(0L) == null; tick++) {
            fixture.update();
        }
        assertTrue(dependents(fixture, 2).get(halo).contains(0L));
        Field routes = ClientLocalMeshSources.class.getDeclaredField("routes");
        routes.setAccessible(true);
        assertFalse(((Map<?, ?>) routes.get(fixture.sources)).containsKey(1));
        fixture.sources.clear();
        assertTrue(((Map<?, ?>) routes.get(fixture.sources)).isEmpty());
    }

    @SuppressWarnings("unchecked")
    private static Long2ObjectMap<LongSet> dependents(Fixture fixture, int key) throws ReflectiveOperationException {
        Field routes = ClientLocalMeshSources.class.getDeclaredField("routes");
        routes.setAccessible(true);
        Object route = ((Map<?, ?>) routes.get(fixture.sources)).get(key);
        Field dependencies = route.getClass().getDeclaredField("dependents");
        dependencies.setAccessible(true);
        return (Long2ObjectMap<LongSet>) dependencies.get(route);
    }

    static final class Fixture {
        private final ClientLevel level = mock(ClientLevel.class);
        private final Map<Long, LevelChunk> chunks = new HashMap<>();
        final AtomicReference<BlockState> state = new AtomicReference<>(Blocks.STONE.defaultBlockState());
        private final AtomicReference<BlockState> marker = new AtomicReference<>();
        private final AtomicReference<Holder<Biome>> biome = new AtomicReference<>();
        private final List<ClientViewMessage> messages = new ArrayList<>();
        final ClientLocalMeshSources sources = new ClientLocalMeshSources(messages::add);
        final ClientViewSession session;
        final ClientViewSession.Sink sink = mock(ClientViewSession.Sink.class);

        Fixture() throws Exception {
            WormholesClientConfig config = new WormholesClientConfig();
            config.normalize();
            session = new ClientViewSession(config, new ClientPalette(BuiltInRegistries.BLOCK), 1, "test");
            session.accept(new ClientViewMessage.Accept(1, ClientViewCapability.ALL, 20, ClientViewProtocol.DEFAULT_MAX_FRAME_BYTES, 7, 8));
            when(level.dimension()).thenReturn(Level.OVERWORLD);
            when(level.getMinY()).thenReturn(-64);
            when(level.getMaxY()).thenReturn(320);
            when(level.getMinSectionY()).thenReturn(-4);
            when(level.getMaxSectionY()).thenReturn(19);
            DimensionType dimension = mock(DimensionType.class);
            when(level.dimensionType()).thenReturn(dimension);
            LevelLightEngine lighting = mock(LevelLightEngine.class);
            LayerLightEventListener light = mock(LayerLightEventListener.class);
            when(level.getLightEngine()).thenReturn(lighting);
            when(lighting.getLayerListener(any())).thenReturn(light);
            when(light.getDataLayerData(any())).thenReturn(new DataLayer(0));
            when(level.entitiesForRendering()).thenReturn(List.of());
            ClientChunkCache cache = mock(ClientChunkCache.class);
            when(level.getChunkSource()).thenReturn(cache);
            when(cache.getChunk(anyInt(), anyInt(), eq(ChunkStatus.FULL), eq(false)))
                .thenAnswer(invocation -> chunks.get(ChunkPos.pack(invocation.getArgument(0), invocation.getArgument(1))));
            HolderOwner<Biome> owner = mock(HolderOwner.class);
            biome.set(Holder.Reference.createStandAlone(owner, ResourceKey.create(Registries.BIOME, Identifier.parse("minecraft:plains"))));
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    LevelChunk chunk = mock(LevelChunk.class);
                    LevelChunkSection section = mock(LevelChunkSection.class);
                    when(chunk.getPos()).thenReturn(new ChunkPos(x, z));
                    when(chunk.getSection(anyInt())).thenReturn(section);
                    when(section.copy()).thenAnswer(ignored -> {
                        BlockState captured = state.get();
                        BlockState capturedMarker = marker.get();
                        Holder<Biome> capturedBiome = biome.get();
                        LevelChunkSection immutable = mock(LevelChunkSection.class);
                        when(immutable.getBlockState(anyInt(), anyInt(), anyInt())).thenAnswer(invocation ->
                            capturedMarker != null && (int) invocation.getArgument(0) == 2 && (int) invocation.getArgument(1) == 1
                                && (int) invocation.getArgument(2) == 3 ? capturedMarker : captured);
                        when(immutable.getNoiseBiome(anyInt(), anyInt(), anyInt())).thenReturn(capturedBiome);
                        return immutable;
                    });
                    when(chunk.getBlockEntities()).thenReturn(Map.of());
                    when(section.getBlockState(anyInt(), anyInt(), anyInt())).thenAnswer(ignored -> state.get());
                    when(section.getNoiseBiome(anyInt(), anyInt(), anyInt())).thenAnswer(ignored -> biome.get());
                    chunks.put(ChunkPos.pack(x, z), chunk);
                }
            }
            add(1);
        }

        private void add(int key) throws Exception {
            ClientPortalGeometry geometry = new ClientPortalGeometry(0, 0, 0, Direction.S.ordinal(), true, 0, true, 1, 1,
                new long[] {1}, 0, 0, 0, 16, 0, 0, 0, 0, 0, 0, 0, 0, key, List.of());
            session.handle(new ClientViewMessage.Portal(key, 1, geometry), sink);
            session.handle(new ClientViewMessage.MeshBegin(key, 1, BOUNDS, 1), sink);
            session.handle(new ClientViewMessage.Environment(key, PortalEnvironmentTest.environment(PortalEnvironmentTest.identity())), sink);
        }

        private void update() throws Exception {
            sources.update(session, level, 0, 0, 0);
        }

        void awaitSection() throws Exception {
            for (int tick = 0; tick < 100 && session.meshes().view(1).section(SectionPos.asLong(0, 0, 0)) == null; tick++) {
                update();
            }
            assertNotNull(session.meshes().view(1).section(0L));
        }
    }
}
