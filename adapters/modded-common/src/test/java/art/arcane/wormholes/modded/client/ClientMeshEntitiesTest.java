package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.network.client.Brick;
import art.arcane.wormholes.network.client.BrickCodec;
import art.arcane.wormholes.network.client.ClientViewEnvironment;
import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.util.Direction;
import art.arcane.wormholes.modded.client.render.PortalEnvironmentTest;
import art.arcane.wormholes.network.client.SectionBiomes;
import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.wormholes.network.view.EntityVisual;
import art.arcane.wormholes.render.plate.PlateBox;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoubleBlockCombiner;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class ClientMeshEntitiesTest {
    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void nestedFeatureCameraFollowsRotatedAndReflectedAncestorSpaces() {
        ClientViewEnvironment.Transform root = new ClientViewEnvironment.Transform(Direction.S, Direction.U, Direction.W,
            new GeometryVector(100, 20, -50));
        ClientViewEnvironment.Transform child = new ClientViewEnvironment.Transform(Direction.W, Direction.U, Direction.S,
            new GeometryVector(6, 0, 0));
        assertEquals(new GeometryVector(2, 3, 4), ClientMeshEntities.contentPoint(List.of(root, child), 96, 23, -46));
        assertEquals(new GeometryVector(4, 3, 4), ClientMeshEntities.contentPoint(List.of(root), 96, 23, -46));
        assertEquals(new GeometryVector(96, 23, -46), ClientMeshEntities.contentPoint(List.of(), 96, 23, -46));
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
        for (Direction[] axes : new Direction[][] {{Direction.E, Direction.U, Direction.S}, {Direction.W, Direction.D, Direction.N},
            {Direction.U, Direction.W, Direction.S}}) {
            scene.synchronize(new ClientViewEnvironment.Transform(axes[0], axes[1], axes[2], new GeometryVector(0.5D, 0.5D, 0.5D)));
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
        ClientViewEnvironment.Transform transform = new ClientViewEnvironment.Transform(Direction.U, Direction.W, Direction.S,
            new GeometryVector(100, 50, 200));
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
        when(world.spawn(anyInt(), any())).thenReturn(true);
        ClientProjectedEntities entities = new ClientProjectedEntities(world);
        UUID id = UUID.randomUUID();
        EntityVisual stand = EntityVisual.full(id, "minecraft:armor_stand", -20.5D, 64.0D, -40.5D, 1.975D,
            0.0D, 0.0D, -1.0D, 180.0F, 0.0F, 0.0D, 0.0D, 0.0D, true, "", "", "", null, null,
            EntityVisual.EMPTY, EntityVisual.EMPTY, 1);
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
