package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.client.render.PortalEnvironmentTest;
import art.arcane.optics.stream.Brick;
import art.arcane.optics.stream.BrickCodec;
import art.arcane.optics.stream.ViewStreamMessage;
import art.arcane.optics.stream.ViewStreamLimits;
import art.arcane.optics.stream.SectionBiomes;
import art.arcane.optics.math.BlockBox;
import art.arcane.optics.math.Face;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.frame.AxisPermutation;
import com.mojang.blaze3d.vertex.QuadInstance;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyDouble;

public class ClientMeshWorldTest extends MinecraftTestBase {
    @Test
    public void destinationNeighborsFluidsAndLightRoundTripAcrossRotatedSectionEdges() throws Exception {
        ClientPalette palette = new ClientPalette(BuiltInRegistries.BLOCK);
        palette.apply(new ViewStreamMessage.Palette(List.of(new ViewStreamMessage.PaletteEntry(3, "minecraft:stone"),
            new ViewStreamMessage.PaletteEntry(4, "minecraft:water[level=0]"),
            new ViewStreamMessage.PaletteEntry(5, "minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]"))));
        ClientMeshSections store = new ClientMeshSections(palette, 65536);
        store.begin(7, 1, new BlockBox(-16, -16, -16, 32, 32, 32), 8);
        int[] cells = new int[4096];
        byte[] block = new byte[ViewStreamLimits.LIGHT_NIBBLE_BYTES];
        byte[] sky = new byte[ViewStreamLimits.LIGHT_NIBBLE_BYTES];
        for (int cell = 0; cell < cells.length; cell++) {
            int x = cell & 15;
            int z = cell >> 4 & 15;
            int y = cell >> 8;
            cells[cell] = 3 + (x + y * 2 + z * 4) % 3;
            block[cell >> 1] |= (byte) (x << ((cell & 1) * 4));
            sky[cell >> 1] |= (byte) (z << ((cell & 1) * 4));
        }
        for (int x = -1; x <= 0; x++) {
            for (int y = -1; y <= 0; y++) {
                for (int z = -1; z <= 0; z++) {
                    store.put(new ViewStreamMessage.MeshSection(7, 1, x, y, z, 1, 3,
                        BrickCodec.pack(0, cells).withLight(block, sky), new SectionBiomes(List.of("minecraft:plains"), new byte[0])));
                }
            }
        }
        for (Face[] axes : new Face[][] {{Face.U, Face.W, Face.S},
            {Face.D, Face.E, Face.S}, {Face.W, Face.U, Face.S}, {Face.S, Face.U, Face.W}}) {
            OpticTransform transform = OpticTransform.of(AxisPermutation.of(axes[0], axes[1], axes[2]), 100, -31, 200);
            ClientMeshWorld snapshot = snapshot(store, 0L, transform, 0);
            BlockAndTintGetter destination = snapshot.destination();
            BlockPos.MutableBlockPos center = new BlockPos.MutableBlockPos();
            snapshot.destinationBlock(0, 0, 0, center);
            assertEquals(snapshot.getBlockState(BlockPos.ZERO), destination.getBlockState(center));
            for (int axis = 0; axis < 3; axis++) {
                for (int sign : new int[] {-1, 1}) {
                    BlockPos nativeNeighbor = center.offset(axis == 0 ? sign : 0, axis == 1 ? sign : 0, axis == 2 ? sign : 0);
                    BlockPos displayNeighbor = new BlockPos(axes[axis].x() * sign, axes[axis].y() * sign, axes[axis].z() * sign);
                    assertEquals(snapshot.getBlockState(displayNeighbor), destination.getBlockState(nativeNeighbor));
                    assertEquals(snapshot.getFluidState(displayNeighbor), destination.getFluidState(nativeNeighbor));
                    assertEquals(snapshot.getBrightness(LightLayer.BLOCK, displayNeighbor), destination.getBrightness(LightLayer.BLOCK, nativeNeighbor));
                    assertEquals(snapshot.getBrightness(LightLayer.SKY, displayNeighbor), destination.getBrightness(LightLayer.SKY, nativeNeighbor));
                    BlockPos.MutableBlockPos roundTrip = new BlockPos.MutableBlockPos();
                    snapshot.destinationBlock(displayNeighbor.getX(), displayNeighbor.getY(), displayNeighbor.getZ(), roundTrip);
                    assertEquals(nativeNeighbor, roundTrip);
                }
            }
            assertEquals(CardinalLighting.DEFAULT, destination.cardinalLighting());
            assertEquals(PortalEnvironmentTest.environment(transform).dimension().minY(), destination.getMinY());
            assertEquals(PortalEnvironmentTest.environment(transform).dimension().height(), destination.getHeight());
            destination.getBlockTint(center, (biome, x, z) -> {
                assertEquals(center.getX(), x, 0);
                assertEquals(center.getZ(), z, 0);
                return 0x445566;
            });
        }
    }

    @Test
    public void lightSnapshotRetainsDestinationValuesAfterStreamChanges() throws Exception {
        ClientMeshSections store = store();
        byte[] block = new byte[ViewStreamLimits.LIGHT_NIBBLE_BYTES];
        byte[] sky = new byte[ViewStreamLimits.LIGHT_NIBBLE_BYTES];
        Arrays.fill(block, (byte) 0x22);
        Arrays.fill(sky, (byte) 0xFF);
        store.put(new ViewStreamMessage.MeshSection(7, 1, 0, 0, 0, 1, 3, Brick.single(0, 3).withLight(block, sky),
            new SectionBiomes(List.of("minecraft:plains"), new byte[0])));
        ClientMeshWorld snapshot = snapshot(store, 0L, PortalEnvironmentTest.identity(), 0);
        store.drop(7, 1, 0, 0, 0);
        BlockPos position = new BlockPos(8, 8, 8);
        assertEquals(15, snapshot.getBrightness(LightLayer.SKY, position));
        assertEquals(2, snapshot.getBrightness(LightLayer.BLOCK, position));
        assertEquals(11, snapshot.getRawBrightness(position, 4));
        assertEquals(0, snapshot.getBrightness(LightLayer.SKY, new BlockPos(40, 8, 8)));
    }

    @Test
    public void quartBiomesAndTintCoordinatesFollowNegativeDestinationCoordinates() throws Exception {
        ClientMeshSections store = store();
        byte[] indices = new byte[SectionBiomes.INDEX_BYTES];
        indices[SectionBiomes.cell(15, 15, 15) * 2] = 1;
        store.put(new ViewStreamMessage.MeshSection(7, 1, -1, -1, -1, 1, 3, Brick.single(0, 3),
            new SectionBiomes(List.of("minecraft:plains", "minecraft:desert"), indices)));
        OpticTransform transform = OpticTransform.of(AxisPermutation.of(Face.E, Face.U, Face.S), 100, 0, 200);
        ClientMeshWorld snapshot = snapshot(store, SectionPos.asLong(-1, -1, -1), transform, 0);
        assertEquals(0xFF112233, snapshot.getBlockTint(new BlockPos(-16, -16, -16), (biome, x, z) -> biome.getWaterColor()));
        assertEquals(0xFF445566, snapshot.getBlockTint(new BlockPos(-1, -1, -1), (biome, x, z) -> biome.getWaterColor()));
        snapshot.getBlockTint(new BlockPos(-1, -1, -1), (biome, x, z) -> {
            assertEquals(-101, x, 0);
            assertEquals(-201, z, 0);
            return 0xFFFFFF;
        });
    }

    @Test
    public void rotatedBiomeBlendAndFaceShadeUseDestinationAxes() throws Exception {
        ClientMeshSections store = store();
        byte[] indices = new byte[SectionBiomes.INDEX_BYTES];
        for (int y = 4; y < 24; y += 4) {
            for (int z = -8; z < 24; z += 4) {
                for (int x = -8; x < 24; x += 4) {
                    indices[SectionBiomes.cell(x, y, z) * 2] = 1;
                }
            }
        }
        store.put(new ViewStreamMessage.MeshSection(7, 1, 0, 0, 0, 1, 3, Brick.single(0, 3),
            new SectionBiomes(List.of("minecraft:plains", "minecraft:desert"), indices)));
        OpticTransform transform = OpticTransform.of(AxisPermutation.of(Face.U, Face.E, Face.S), 0, 0, 0);
        ClientMeshWorld snapshot = snapshot(store, 0L, transform, 1);
        ColorResolver resolver = (biome, x, z) -> biome.getWaterColor() == 0x112233 ? 0 : 0xFFFFFF;
        assertEquals(0xFF555555, snapshot.getBlockTint(new BlockPos(8, 3, 8), resolver));
        assertEquals(CardinalLighting.DEFAULT.up(), snapshot.cardinalLighting().east(), 0);
        assertEquals(CardinalLighting.DEFAULT.west(), snapshot.cardinalLighting().down(), 0);
    }

    @Test
    public void upperPlantsAtSectionEdgesUseCapturedDestinationHaloWithoutNeighborSections() throws Exception {
        byte[] indices = new byte[SectionBiomes.INDEX_BYTES];
        for (int y = -8; y < 24; y += 4) {
            for (int z = -8; z < 24; z += 4) {
                for (int x = -8; x < 24; x += 4) {
                    if (x < 0 || x >= 16 || y < 0 || y >= 16 || z < 0 || z >= 16) {
                        indices[SectionBiomes.cell(x, y, z) * 2] = 1;
                    }
                }
            }
        }
        ClientMeshSections store = store();
        store.put(new ViewStreamMessage.MeshSection(7, 1, 0, 0, 0, 1, 3, Brick.single(0, 3),
            new SectionBiomes(List.of("minecraft:plains", "minecraft:desert"), indices)));
        BlockColors colors = BlockColors.createDefault();
        for (Face[] axes : new Face[][] {{Face.E, Face.U, Face.S},
            {Face.U, Face.W, Face.S}, {Face.D, Face.E, Face.S},
            {Face.W, Face.D, Face.S}, {Face.E, Face.N, Face.U}}) {
            OpticTransform transform = OpticTransform.of(AxisPermutation.of(axes[0], axes[1], axes[2]), -100.5, 63.5, -200.5);
            ClientMeshWorld snapshot = snapshot(store, 0L, transform, 7);
            int x = axes[1].x() < 0 ? 15 : 0;
            int y = axes[1].y() < 0 ? 15 : 0;
            int z = axes[1].z() < 0 ? 15 : 0;
            BlockPos.MutableBlockPos nativePosition = new BlockPos.MutableBlockPos();
            snapshot.destinationBlock(x, y, z, nativePosition);
            assertEquals(Blocks.AIR.defaultBlockState(), snapshot.destination().getBlockState(nativePosition.below()));
            for (BlockState plant : List.of(Blocks.TALL_GRASS.defaultBlockState(), Blocks.LARGE_FERN.defaultBlockState())) {
                BlockState upper = plant.setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER);
                int tint = colors.getTintSource(upper, 0).colorInWorld(upper, snapshot.destination(), nativePosition);
                assertEquals(0xFF445566, tint);
            }
        }
    }

    @Test
    public void nativeFoliageTintKeepsCutoutQuadAlphaOpaque() throws Exception {
        ClientMeshSections store = store();
        store.put(new ViewStreamMessage.MeshSection(7, 1, 0, 0, 0, 1, 3, Brick.single(0, 3),
            new SectionBiomes(List.of("minecraft:plains"), new byte[0])));
        ClientMeshWorld snapshot = snapshot(store, 0L, PortalEnvironmentTest.identity(), 1);
        BlockColors colors = BlockColors.createDefault();
        BlockPos position = new BlockPos(8, 8, 8);
        for (BlockState state : List.of(Blocks.OAK_LEAVES.defaultBlockState(), Blocks.BIRCH_LEAVES.defaultBlockState(),
            Blocks.ACACIA_LEAVES.defaultBlockState(), Blocks.VINE.defaultBlockState())) {
            int tint = colors.getTintSource(state, 0).colorInWorld(state, snapshot, position);
            QuadInstance quad = new QuadInstance();
            quad.setColor(ARGB.gray(0.6F));
            quad.multiplyColor(tint);
            for (int vertex = 0; vertex < 4; vertex++) {
                assertEquals(state.toString(), 255, ARGB.alpha(quad.getColor(vertex)));
            }
        }
        int oakTint = colors.getTintSource(Blocks.OAK_LEAVES.defaultBlockState(), 0)
            .colorInWorld(Blocks.OAK_LEAVES.defaultBlockState(), snapshot, position);
        assertEquals(0xFF228844, oakTint);
    }

    @SuppressWarnings("unchecked")
    private static ClientMeshWorld snapshot(ClientMeshSections store, long center, OpticTransform transform, int blend) {
        Registry<Biome> registry = mock(Registry.class);
        Biome plains = mock(Biome.class);
        Biome desert = mock(Biome.class);
        when(plains.getWaterColor()).thenReturn(0x112233);
        when(plains.getFoliageColor()).thenReturn(0xFF228844);
        when(plains.getGrassColor(anyDouble(), anyDouble())).thenReturn(0x112233);
        when(desert.getWaterColor()).thenReturn(0x445566);
        when(desert.getGrassColor(anyDouble(), anyDouble())).thenReturn(0x445566);
        when(registry.getOptional(Identifier.parse("minecraft:plains"))).thenReturn(Optional.of(plains));
        when(registry.getOptional(Identifier.parse("minecraft:desert"))).thenReturn(Optional.of(desert));
        RegistryAccess access = mock(RegistryAccess.class);
        when(access.lookupOrThrow(Registries.BIOME)).thenReturn(registry);
        return new ClientMeshWorld(new ClientMeshWorld.Snapshot(store.view(7), center, access,
            PortalEnvironmentTest.environment(transform), blend));
    }

    private static ClientMeshSections store() throws Exception {
        ClientPalette palette = new ClientPalette(BuiltInRegistries.BLOCK);
        palette.apply(new ViewStreamMessage.Palette(List.of(new ViewStreamMessage.PaletteEntry(3, "minecraft:stone"))));
        ClientMeshSections store = new ClientMeshSections(palette, 65536);
        store.begin(7, 1, new BlockBox(-16, -16, -16, 32, 32, 32), 8);
        return store;
    }
}
