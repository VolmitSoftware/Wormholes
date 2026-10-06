package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.optics.stream.Brick;
import art.arcane.optics.stream.BrickCodec;
import art.arcane.optics.stream.ProjectionEnvironment;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.math.Face;
import art.arcane.wormholes.modded.client.render.PortalEnvironmentTest;
import art.arcane.optics.stream.SectionBiomes;
import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.optics.entity.EntitySnapshot;
import art.arcane.optics.plate.PlateBox;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoubleBlockCombiner;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

public class ClientMeshEntitiesTest extends MinecraftTestBase {
    @Test
    public void localEntitiesUseIndependentRenderStatesWithoutTickingOrMutatingSourceEntities() {
        ClientLevel level = mock(ClientLevel.class);
        ClientMeshEntities first = new ClientMeshEntities(mock(ClientMeshSections.View.class), level);
        ClientMeshEntities second = new ClientMeshEntities(mock(ClientMeshSections.View.class), level);
        Entity entity = mock(Entity.class);
        UUID id = UUID.randomUUID();
        when(entity.getUUID()).thenReturn(id);
        when(level.entitiesForRendering()).thenReturn(List.of(entity));
        EntityRenderDispatcher renderer = mock(EntityRenderDispatcher.class);
        EntityRenderState firstState = new EntityRenderState();
        EntityRenderState secondState = new EntityRenderState();
        when(renderer.extractEntity(entity, 0.5F)).thenAnswer(call -> {
            assertNull(ClientMeshEntities.active());
            return firstState;
        }).thenAnswer(call -> {
            assertNull(ClientMeshEntities.active());
            return secondState;
        });
        List<EntityRenderState> firstStates = new ArrayList<>();
        List<EntityRenderState> secondStates = new ArrayList<>();
        try (MockedStatic<ClientMeshEntities> ownership = mockStatic(ClientMeshEntities.class, CALLS_REAL_METHODS)) {
            ownership.when(() -> ClientMeshEntities.hiddenFromWorld(entity)).thenReturn(false);
            clearInvocations(entity);
            first.inDestinationWorld(() -> {
                first.extractLocalEntities(Set.of(id), renderer, 0.5F, firstStates);
                assertSame(first, ClientMeshEntities.active(level));
            });
            second.inDestinationWorld(() -> {
                second.extractLocalEntities(Set.of(id), renderer, 0.5F, secondStates);
                assertSame(second, ClientMeshEntities.active(level));
            });
        }
        assertEquals(List.of(firstState), firstStates);
        assertEquals(List.of(secondState), secondStates);
        assertNotSame(firstStates.getFirst(), secondStates.getFirst());
        assertNull(ClientMeshEntities.active());
        verify(entity, times(2)).getUUID();
        verify(entity, times(2)).isRemoved();
        verifyNoMoreInteractions(entity);
    }

    @Test
    public void localEntityExtractionSkipsUncoveredRemovedAndOwnedCopies() {
        ClientLevel level = mock(ClientLevel.class);
        ClientMeshEntities scene = new ClientMeshEntities(mock(ClientMeshSections.View.class), level);
        Entity uncovered = mock(Entity.class);
        Entity removed = mock(Entity.class);
        Entity owned = mock(Entity.class);
        UUID removedId = UUID.randomUUID();
        UUID ownedId = UUID.randomUUID();
        when(uncovered.getUUID()).thenReturn(UUID.randomUUID());
        when(removed.getUUID()).thenReturn(removedId);
        when(owned.getUUID()).thenReturn(ownedId);
        when(removed.isRemoved()).thenReturn(true);
        when(level.entitiesForRendering()).thenReturn(List.of(uncovered, removed, owned));
        EntityRenderDispatcher renderer = mock(EntityRenderDispatcher.class);
        List<EntityRenderState> states = new ArrayList<>();
        try (MockedStatic<ClientMeshEntities> ownership = mockStatic(ClientMeshEntities.class, CALLS_REAL_METHODS)) {
            ownership.when(() -> ClientMeshEntities.hiddenFromWorld(removed)).thenReturn(false);
            ownership.when(() -> ClientMeshEntities.hiddenFromWorld(owned)).thenReturn(true);
            scene.extractLocalEntities(Set.of(removedId, ownedId), renderer, 0.25F, states);
        }
        assertTrue(states.isEmpty());
        assertNull(ClientMeshEntities.active());
        verifyNoInteractions(renderer);
        verify(uncovered).getUUID();
        verifyNoMoreInteractions(uncovered);
    }

    @Test
    public void localEntityExtractionRestoresDestinationScopeAfterRenderFailure() {
        ClientLevel level = mock(ClientLevel.class);
        ClientMeshEntities scene = new ClientMeshEntities(mock(ClientMeshSections.View.class), level);
        Entity entity = mock(Entity.class);
        UUID id = UUID.randomUUID();
        when(entity.getUUID()).thenReturn(id);
        when(level.entitiesForRendering()).thenReturn(List.of(entity));
        EntityRenderDispatcher renderer = mock(EntityRenderDispatcher.class);
        when(renderer.extractEntity(entity, 0.5F)).thenAnswer(call -> {
            assertNull(ClientMeshEntities.active());
            throw new IllegalStateException("local render failed");
        });
        try (MockedStatic<ClientMeshEntities> ownership = mockStatic(ClientMeshEntities.class, CALLS_REAL_METHODS)) {
            ownership.when(() -> ClientMeshEntities.hiddenFromWorld(entity)).thenReturn(false);
            scene.inDestinationWorld(() -> {
                assertThrows(IllegalStateException.class,
                    () -> scene.extractLocalEntities(Set.of(id), renderer, 0.5F, new ArrayList<>()));
                assertSame(scene, ClientMeshEntities.active(level));
            });
            assertNull(ClientMeshEntities.active());
            assertThrows(IllegalStateException.class,
                () -> scene.extractLocalEntities(Set.of(id), renderer, 0.5F, new ArrayList<>()));
            assertNull(ClientMeshEntities.active());
        }
    }

    @Test
    public void nestedFeatureCameraFollowsRotatedAndReflectedAncestorSpaces() {
        ProjectionEnvironment.Transform root = new ProjectionEnvironment.Transform(Face.S, Face.U, Face.W,
            new Vec3d(100, 20, -50));
        ProjectionEnvironment.Transform child = new ProjectionEnvironment.Transform(Face.W, Face.U, Face.S,
            new Vec3d(6, 0, 0));
        assertEquals(new Vec3d(2, 3, 4), ClientMeshEntities.contentPoint(List.of(root, child), 96, 23, -46));
        assertEquals(new Vec3d(4, 3, 4), ClientMeshEntities.contentPoint(List.of(root), 96, 23, -46));
        assertEquals(new Vec3d(96, 23, -46), ClientMeshEntities.contentPoint(List.of(), 96, 23, -46));
    }

    @Test
    public void halfBlockTranslationQueriesKeepTheCapturedCellAtExactCenterTies() throws Exception {
        ClientPalette palette = new ClientPalette(BuiltInRegistries.BLOCK);
        palette.apply(new ClientViewMessage.Palette(List.of(new ClientViewMessage.PaletteEntry(3, "minecraft:chest[facing=north]"))));
        ClientMeshSections store = new ClientMeshSections(palette, 1_048_576);
        store.begin(7, 1, new PlateBox(0, 0, 0, 16, 16, 16), 1);
        byte[] light = new byte[2048];
        light[0] = 7;
        store.put(new ClientViewMessage.MeshSection(7, 1, 0, 0, 0, 1, 0, oneBlock(3).withLight(light, new byte[2048]), SectionBiomes.NONE));
        ClientMeshEntities scene = new ClientMeshEntities(store.view(7), mock(ClientLevel.class));
        for (Face[] axes : new Face[][] {{Face.E, Face.U, Face.S}, {Face.W, Face.D, Face.N},
            {Face.U, Face.W, Face.S}}) {
            scene.synchronize(new ProjectionEnvironment.Transform(axes[0], axes[1], axes[2], new Vec3d(0.5D, 0.5D, 0.5D)));
            BlockEntity chest = scene.blockEntity(BlockPos.ZERO);
            assertNotNull(chest);
            assertEquals(BlockPos.ZERO, chest.getBlockPos());
            scene.inDestinationWorld(() -> {
                assertSame(chest, scene.blockEntity(BlockPos.ZERO));
                assertSame(chest.getBlockState(), scene.blockState(BlockPos.ZERO));
                assertEquals(7, scene.brightness(LightLayer.BLOCK, BlockPos.ZERO));
            });
        }
    }

    @Test
    public void visualTicksReadCapturedDestinationFluidsAndRestoreScopeAfterFailure() throws Exception {
        ClientPalette palette = new ClientPalette(BuiltInRegistries.BLOCK);
        palette.apply(new ClientViewMessage.Palette(List.of(new ClientViewMessage.PaletteEntry(3, "minecraft:water[level=0]"))));
        BlockState water = palette.state(3);
        assertSame(Blocks.WATER, water.getBlock());
        water.initCache();
        assertFalse(water.getFluidState().isEmpty());
        ClientMeshSections store = new ClientMeshSections(palette, 1_048_576);
        store.begin(7, 1, new PlateBox(0, 0, 0, 16, 16, 16), 1);
        store.put(new ClientViewMessage.MeshSection(7, 1, 0, 0, 0, 1, 0, oneBlock(3), SectionBiomes.NONE));
        ClientLevel level = mock(ClientLevel.class);
        ClientMeshEntities scene = new ClientMeshEntities(store.view(7), level);
        ProjectionEnvironment.Transform transform = new ProjectionEnvironment.Transform(Face.W, Face.U, Face.S,
            new Vec3d(101, 0, 0));
        Entity entity = mock(Entity.class);
        doAnswer(ignored -> {
            assertSame(scene, ClientMeshEntities.active(level));
            assertFalse(scene.blockState(new BlockPos(100, 0, 0)).getFluidState().isEmpty());
            assertTrue(scene.blockState(new BlockPos(200, 0, 0)).getFluidState().isEmpty());
            return null;
        }).when(entity).tick();
        scene.tickEntity(entity, transform);
        verify(entity).commonTick();
        verify(entity).tick();
        assertNull(ClientMeshEntities.active());
        verifyNoInteractions(level);
        doThrow(new IllegalStateException("failed species tick")).when(entity).tick();
        assertThrows(IllegalStateException.class, () -> scene.tickEntity(entity, transform));
        assertNull(ClientMeshEntities.active());
        assertFalse(scene.blockState(BlockPos.ZERO).getFluidState().isEmpty());
    }

    @Test
    public void sidewaysDoubleChestsUseNativePositionsNeighborsAndLightInsideScopedExtraction() throws Exception {
        ClientPalette palette = new ClientPalette(BuiltInRegistries.BLOCK);
        palette.apply(new ClientViewMessage.Palette(List.of(
            new ClientViewMessage.PaletteEntry(3, "minecraft:chest[facing=north,type=left,waterlogged=false]"),
            new ClientViewMessage.PaletteEntry(4, "minecraft:chest[facing=north,type=right,waterlogged=false]"))));
        ClientMeshSections store = new ClientMeshSections(palette, 1_048_576);
        store.begin(7, 1, new PlateBox(0, 0, 0, 16, 16, 16), 1);
        BlockState left = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.TYPE, ChestType.LEFT);
        BlockPos connected = ChestBlock.getConnectedBlockPos(BlockPos.ZERO, left);
        BlockPos display = new BlockPos(8, 8, 8);
        BlockPos displayPartner = display.offset(-connected.getY(), connected.getX(), connected.getZ());
        int firstCell = display.getY() << 8 | display.getZ() << 4 | display.getX();
        int secondCell = displayPartner.getY() << 8 | displayPartner.getZ() << 4 | displayPartner.getX();
        int[] cells = new int[4096];
        cells[firstCell] = 3;
        cells[secondCell] = 4;
        byte[] light = new byte[2048];
        light[firstCell >> 1] |= (byte) (3 << ((firstCell & 1) * 4));
        light[secondCell >> 1] |= (byte) (12 << ((secondCell & 1) * 4));
        store.put(new ClientViewMessage.MeshSection(7, 1, 0, 0, 0, 1, 0, BrickCodec.pack(0, cells).withLight(light, new byte[2048]), SectionBiomes.NONE));
        ClientLevel level = mock(ClientLevel.class);
        ClientMeshEntities scene = new ClientMeshEntities(store.view(7), level);
        ProjectionEnvironment.Transform transform = new ProjectionEnvironment.Transform(Face.U, Face.W, Face.S,
            new Vec3d(100, 50, 200));
        scene.synchronize(transform);
        BlockEntity chest = scene.blockEntity(display);
        BlockPos nativePosition = new BlockPos(-42, 91, -192);
        assertEquals(nativePosition, chest.getBlockPos());
        when(level.getBlockState(any())).thenAnswer(call -> scene.blockState(call.getArgument(0)));
        when(level.getBlockEntity(any())).thenAnswer(call -> scene.blockEntity(call.getArgument(0)));
        scene.inDestinationWorld(() -> {
            assertSame(scene, ClientMeshEntities.active(level));
            assertSame(chest, scene.blockEntity(nativePosition));
            assertEquals(left, scene.blockState(nativePosition));
            BlockPos neighbor = nativePosition.offset(connected);
            assertEquals(neighbor, scene.blockEntity(neighbor).getBlockPos());
            assertEquals(3, scene.brightness(LightLayer.BLOCK, nativePosition));
            assertEquals(12, scene.brightness(LightLayer.BLOCK, neighbor));
            assertEquals(0, scene.brightness(LightLayer.BLOCK, nativePosition.offset(1000, 0, 0)));
            DoubleBlockCombiner.Combiner<ChestBlockEntity, Boolean> combiner = mock(DoubleBlockCombiner.Combiner.class);
            ((ChestBlock) Blocks.CHEST).combine(left, level, nativePosition, true).apply(combiner);
            verify(combiner).acceptDouble(any(), any());
        });
        assertNull(ClientMeshEntities.active());
        assertSame(chest, scene.blockEntity(display));
        assertThrows(IllegalStateException.class, () -> scene.inDestinationWorld(() -> { throw new IllegalStateException("test"); }));
        assertNull(ClientMeshEntities.active());
        assertSame(chest, scene.blockEntity(display));
    }

    @Test
    public void detachedBlockEntitiesFollowSectionReplacementAndRemovalWithoutChunkWrites() throws Exception {
        ClientPalette palette = new ClientPalette(BuiltInRegistries.BLOCK);
        palette.apply(new ClientViewMessage.Palette(List.of(new ClientViewMessage.PaletteEntry(3, "minecraft:chest[facing=east]"),
            new ClientViewMessage.PaletteEntry(4, "minecraft:oak_sign[rotation=8]"))));
        ClientMeshSections store = new ClientMeshSections(palette, 1_048_576);
        store.begin(7, 1, new PlateBox(-16, -16, -16, 16, 16, 16), 1);
        store.put(new ClientViewMessage.MeshSection(7, 1, -1, -1, -1, 1, 0, oneBlock(3), SectionBiomes.NONE));
        ClientLevel level = mock(ClientLevel.class);
        ClientMeshEntities scene = new ClientMeshEntities(store.view(7), level);
        BlockPos position = new BlockPos(-16, -16, -16);
        scene.synchronize(PortalEnvironmentTest.identity());
        BlockEntity chest = scene.blockEntity(position);
        assertNotNull(chest);
        assertSame(Blocks.CHEST, chest.getBlockState().getBlock());
        assertSame(level, chest.getLevel());
        scene.synchronize(PortalEnvironmentTest.identity());
        assertSame(chest, scene.blockEntity(position));
        store.put(new ClientViewMessage.MeshSection(7, 1, -1, -1, -1, 2, 0, oneBlock(4), SectionBiomes.NONE));
        scene.synchronize(PortalEnvironmentTest.identity());
        assertNotSame(chest, scene.blockEntity(position));
        assertSame(Blocks.OAK_SIGN, scene.blockEntity(position).getBlockState().getBlock());
        assertSame(Blocks.AIR.defaultBlockState(), scene.blockState(position.offset(16, 0, 0)));
        assertNull(scene.blockEntity(position.offset(1, 0, 0)));
        store.drop(7, 1, -1, -1, -1);
        scene.synchronize(PortalEnvironmentTest.identity());
        assertNull(scene.blockEntity(position));
        verifyNoInteractions(level);
    }

    @Test
    public void palettesWithoutEntityBlocksSkipCellDiscoveryAndMissingNbtStillCreatesEntities() throws Exception {
        ClientPalette palette = new ClientPalette(BuiltInRegistries.BLOCK);
        palette.apply(new ClientViewMessage.Palette(List.of(new ClientViewMessage.PaletteEntry(3, "minecraft:chest"),
            new ClientViewMessage.PaletteEntry(4, "minecraft:stone"))));
        ClientMeshSections store = new ClientMeshSections(palette, 1_048_576);
        store.begin(7, 1, new PlateBox(0, 0, 0, 32, 16, 16), 2);
        store.put(new ClientViewMessage.MeshSection(7, 1, 0, 0, 0, 1, 0, oneBlock(4), SectionBiomes.NONE));
        store.put(new ClientViewMessage.MeshSection(7, 1, 1, 0, 0, 1, 0, oneBlock(3), SectionBiomes.NONE));
        long chestKey = SectionPos.asLong(1, 0, 0);
        ClientMeshSections.Section stone = mock(ClientMeshSections.Section.class, delegatesTo(store.view(7).section(0L)));
        ClientMeshSections.Section chest = store.view(7).section(chestKey);
        assertFalse(stone.hasEntityBlocks());
        assertTrue(chest.hasEntityBlocks());
        assertNull(chest.blockEntity(0));
        ClientMeshSections.View view = mock(ClientMeshSections.View.class, delegatesTo(store.view(7)));
        when(view.section(0L)).thenReturn(stone);
        ClientLevel level = mock(ClientLevel.class);
        ClientMeshEntities scene = new ClientMeshEntities(view, level);
        scene.synchronize(PortalEnvironmentTest.identity());
        BlockPos position = new BlockPos(16, 0, 0);
        assertTrue(scene.blockEntity(position) instanceof ChestBlockEntity);
        verify(stone, never()).state(anyInt());
        store.put(new ClientViewMessage.MeshSection(7, 1, 1, 0, 0, 2, 0, oneBlock(4), SectionBiomes.NONE));
        ClientMeshSections.Section replaced = mock(ClientMeshSections.Section.class, delegatesTo(store.view(7).section(chestKey)));
        when(view.section(chestKey)).thenReturn(replaced);
        scene.synchronize(PortalEnvironmentTest.identity());
        assertNull(scene.blockEntity(position));
        verify(replaced, never()).state(anyInt());
        verify(stone, never()).state(anyInt());
        verifyNoInteractions(level);
    }

    @Test
    public void poweredSkullAnimationAdvancesOncePerTickWithoutAWorldTicker() throws Exception {
        ClientPalette palette = new ClientPalette(BuiltInRegistries.BLOCK);
        palette.apply(new ClientViewMessage.Palette(List.of(new ClientViewMessage.PaletteEntry(3, "minecraft:dragon_head[powered=true]"))));
        ClientMeshSections store = new ClientMeshSections(palette, 1_048_576);
        store.begin(7, 1, new PlateBox(0, 0, 0, 16, 16, 16), 1);
        store.put(new ClientViewMessage.MeshSection(7, 1, 0, 0, 0, 1, 0, oneBlock(3), SectionBiomes.NONE));
        ClientLevel level = mock(ClientLevel.class);
        ClientMeshEntities scene = new ClientMeshEntities(store.view(7), level);
        scene.synchronize(PortalEnvironmentTest.identity());
        SkullBlockEntity skull = (SkullBlockEntity) scene.blockEntity(BlockPos.ZERO);
        scene.animate(1);
        assertEquals(1.0F, skull.getAnimation(0.0F), 0.0F);
        scene.animate(1);
        assertEquals(1.0F, skull.getAnimation(0.0F), 0.0F);
        scene.animate(2);
        assertEquals(2.0F, skull.getAnimation(0.0F), 0.0F);
        verifyNoInteractions(level);
    }

    @Test
    public void unchangedFramesDoNotEnumerateSectionsAndUpdatesKeepTheActiveSetCurrent() throws Exception {
        ClientPalette palette = new ClientPalette(BuiltInRegistries.BLOCK);
        palette.apply(new ClientViewMessage.Palette(List.of(new ClientViewMessage.PaletteEntry(3, "minecraft:dragon_head[powered=true]"))));
        ClientMeshSections store = new ClientMeshSections(palette, 1_048_576);
        store.begin(7, 1, new PlateBox(0, 0, 0, 32, 16, 16), 2);
        store.put(new ClientViewMessage.MeshSection(7, 1, 0, 0, 0, 1, 0, Brick.empty(0), SectionBiomes.NONE));
        store.put(new ClientViewMessage.MeshSection(7, 1, 1, 0, 0, 1, 0, oneBlock(3), SectionBiomes.NONE));
        ClientMeshSections.View view = mock(ClientMeshSections.View.class, delegatesTo(store.view(7)));
        ClientLevel level = mock(ClientLevel.class);
        ClientMeshEntities scene = new ClientMeshEntities(view, level);
        BlockPos position = new BlockPos(16, 0, 0);
        scene.synchronize(PortalEnvironmentTest.identity());
        SkullBlockEntity original = (SkullBlockEntity) scene.blockEntity(position);
        scene.animate(1);
        for (int frame = 0; frame < 100; frame++) {
            scene.synchronize(PortalEnvironmentTest.identity());
        }
        verify(view, times(1)).sectionKeys();
        assertSame(original, scene.blockEntity(position));
        store.put(new ClientViewMessage.MeshSection(7, 1, 1, 0, 0, 2, 0, oneBlock(3), SectionBiomes.NONE));
        scene.synchronize(PortalEnvironmentTest.identity());
        SkullBlockEntity replacement = (SkullBlockEntity) scene.blockEntity(position);
        assertNotSame(original, replacement);
        scene.animate(2);
        assertEquals(1.0F, original.getAnimation(0), 0);
        assertEquals(1.0F, replacement.getAnimation(0), 0);
        store.put(new ClientViewMessage.MeshSection(7, 1, 0, 0, 0, 2, 0, oneBlock(3), SectionBiomes.NONE));
        scene.synchronize(PortalEnvironmentTest.identity());
        SkullBlockEntity added = (SkullBlockEntity) scene.blockEntity(BlockPos.ZERO);
        assertSame(replacement, scene.blockEntity(position));
        scene.animate(3);
        assertEquals(1.0F, added.getAnimation(0), 0);
        assertEquals(2.0F, replacement.getAnimation(0), 0);
        store.drop(7, 1, 1, 0, 0);
        store.put(new ClientViewMessage.MeshSection(7, 1, 0, 0, 0, 3, 0, Brick.empty(0), SectionBiomes.NONE));
        scene.synchronize(PortalEnvironmentTest.identity());
        assertNull(scene.blockEntity(position));
        assertNull(scene.blockEntity(BlockPos.ZERO));
        scene.animate(4);
        assertEquals(1.0F, added.getAnimation(0), 0);
        assertEquals(2.0F, replacement.getAnimation(0), 0);
        scene.synchronize(PortalEnvironmentTest.identity());
        verify(view, times(4)).sectionKeys();
        verifyNoInteractions(level);
    }

    @Test
    public void meshEntitiesSpawnWithoutVoxelSweepsAndLoseTheirHiddenOwnershipOnDrop() {
        ClientSceneWorld world = mock(ClientSceneWorld.class);
        when(world.spawn(anyInt(), any(), any())).thenReturn(true);
        ClientProjectedEntities entities = new ClientProjectedEntities(world);
        UUID id = UUID.randomUUID();
        EntitySnapshot stand = EntitySnapshot.full(id, "minecraft:armor_stand", -20.5D, 64.0D, -40.5D, 1.975D,
            0.0D, 0.0D, -1.0D, 180.0F, 0.0F, 0.0D, 0.0D, 0.0D, true, "", "", "", null, null,
            EntitySnapshot.EMPTY, EntitySnapshot.EMPTY, 1);
        entities.apply(new ClientViewMessage.EntityFrame(7, 1, List.of(stand), List.of(id), true));
        ClientPortal portal = mock(ClientPortal.class);
        entities.tick(key -> portal, key -> true);
        int entityId = entities.entityId(7, id);
        assertTrue(ClientEntityIds.isProjected(entityId));
        assertTrue(entities.meshEntity(entityId));
        int[] visited = {0};
        entities.forEachEntity(7, value -> visited[0] = value);
        assertEquals(entityId, visited[0]);
        entities.drop(7);
        assertFalse(entities.meshEntity(entityId));
        assertEquals(0, entities.spawned());
    }

    private static Brick oneBlock(int paletteId) {
        long[] indices = new long[64];
        indices[0] = 1L;
        return new Brick(0, Brick.Encoding.PALETTED, 1, 0, 0, new int[] {0, paletteId}, indices, null, null, null);
    }
}
