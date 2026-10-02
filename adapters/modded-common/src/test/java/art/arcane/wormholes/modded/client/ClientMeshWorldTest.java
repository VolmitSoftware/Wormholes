package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.modded.client.render.PortalEnvironmentTest;
import art.arcane.wormholes.network.client.Brick;
import art.arcane.wormholes.network.client.BrickCodec;
import art.arcane.wormholes.network.client.ClientViewEnvironment;
import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.wormholes.network.client.ClientViewProtocol;
import art.arcane.wormholes.network.client.SectionBiomes;
import art.arcane.wormholes.render.plate.PlateBox;
import art.arcane.wormholes.util.Direction;
import com.mojang.blaze3d.vertex.QuadInstance;
import net.minecraft.SharedConstants;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ClientMeshWorldTest {
    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void destinationNeighborsFluidsAndLightRoundTripAcrossRotatedSectionEdges() throws Exception {
        ClientPalette palette = new ClientPalette(BuiltInRegistries.BLOCK);
        palette.apply(new ClientViewMessage.Palette(List.of(new ClientViewMessage.PaletteEntry(3, "minecraft:stone"),
            new ClientViewMessage.PaletteEntry(4, "minecraft:water[level=0]"),
            new ClientViewMessage.PaletteEntry(5, "minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]"))));
        ClientMeshSections store = new ClientMeshSections(palette, 65536);
        store.begin(7, 1, new PlateBox(-16, -16, -16, 32, 32, 32), 8);
        int[] cells = new int[4096];
        byte[] block = new byte[ClientViewProtocol.LIGHT_NIBBLE_BYTES];
        byte[] sky = new byte[ClientViewProtocol.LIGHT_NIBBLE_BYTES];
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
                    store.put(new ClientViewMessage.MeshSection(7, 1, x, y, z, 1, 3,
                        BrickCodec.pack(0, cells).withLight(block, sky), new SectionBiomes(List.of("minecraft:plains"), new byte[0])));
                }
            }
        }
        for (Direction[] axes : new Direction[][] {{Direction.U, Direction.W, Direction.S},
            {Direction.D, Direction.E, Direction.S}, {Direction.W, Direction.U, Direction.S}, {Direction.S, Direction.U, Direction.W}}) {
            ClientViewEnvironment.Transform transform = new ClientViewEnvironment.Transform(axes[0], axes[1], axes[2],
                new GeometryVector(100, -31, 200));
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
        byte[] block = new byte[ClientViewProtocol.LIGHT_NIBBLE_BYTES];
        byte[] sky = new byte[ClientViewProtocol.LIGHT_NIBBLE_BYTES];
        Arrays.fill(block, (byte) 0x22);
        Arrays.fill(sky, (byte) 0xFF);
        store.put(new ClientViewMessage.MeshSection(7, 1, 0, 0, 0, 1, 3, Brick.single(0, 3).withLight(block, sky),
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
        byte[] indices = new byte[64];
        indices[63] = 1;
        store.put(new ClientViewMessage.MeshSection(7, 1, -1, -1, -1, 1, 3, Brick.single(0, 3),
            new SectionBiomes(List.of("minecraft:plains", "minecraft:desert"), indices)));
        ClientViewEnvironment.Transform transform = new ClientViewEnvironment.Transform(Direction.E, Direction.U, Direction.S,
            new GeometryVector(100, 0, 200));
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
        byte[] indices = new byte[64];
        Arrays.fill(indices, 16, 64, (byte) 1);
        store.put(new ClientViewMessage.MeshSection(7, 1, 0, 0, 0, 1, 3, Brick.single(0, 3),
            new SectionBiomes(List.of("minecraft:plains", "minecraft:desert"), indices)));
        ClientViewEnvironment.Transform transform = new ClientViewEnvironment.Transform(Direction.U, Direction.E, Direction.S,
            new GeometryVector(0, 0, 0));
        ClientMeshWorld snapshot = snapshot(store, 0L, transform, 1);
        ColorResolver resolver = (biome, x, z) -> biome.getWaterColor() == 0x112233 ? 0 : 0xFFFFFF;
        assertEquals(0xFF555555, snapshot.getBlockTint(new BlockPos(8, 3, 8), resolver));
        assertEquals(CardinalLighting.DEFAULT.up(), snapshot.cardinalLighting().east(), 0);
        assertEquals(CardinalLighting.DEFAULT.west(), snapshot.cardinalLighting().down(), 0);
    }

    @Test
    public void nativeFoliageTintKeepsCutoutQuadAlphaOpaque() throws Exception {
        ClientMeshSections store = store();
        store.put(new ClientViewMessage.MeshSection(7, 1, 0, 0, 0, 1, 3, Brick.single(0, 3),
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
    private static ClientMeshWorld snapshot(ClientMeshSections store, long center, ClientViewEnvironment.Transform transform, int blend) {
        Registry<Biome> registry = mock(Registry.class);
        Biome plains = mock(Biome.class);
        Biome desert = mock(Biome.class);
        when(plains.getWaterColor()).thenReturn(0x112233);
        when(plains.getFoliageColor()).thenReturn(0xFF228844);
        when(desert.getWaterColor()).thenReturn(0x445566);
        when(registry.getOptional(Identifier.parse("minecraft:plains"))).thenReturn(Optional.of(plains));
        when(registry.getOptional(Identifier.parse("minecraft:desert"))).thenReturn(Optional.of(desert));
        return new ClientMeshWorld(new ClientMeshWorld.Snapshot(store.view(7), center, registry,
            PortalEnvironmentTest.environment(transform), blend));
    }

    private static ClientMeshSections store() throws Exception {
        ClientPalette palette = new ClientPalette(BuiltInRegistries.BLOCK);
        palette.apply(new ClientViewMessage.Palette(List.of(new ClientViewMessage.PaletteEntry(3, "minecraft:stone"))));
        ClientMeshSections store = new ClientMeshSections(palette, 65536);
        store.begin(7, 1, new PlateBox(-16, -16, -16, 32, 32, 32), 8);
        return store;
    }
}
