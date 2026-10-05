package art.arcane.wormholes.modded.client.render;

import art.arcane.wormholes.modded.MinecraftTestBase;
import com.mojang.blaze3d.vertex.MeshData;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.block.Blocks;
import org.joml.Vector3f;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class PortalSectionMeshTest {
    @BeforeClass
    public static void bootstrap() {
        MinecraftTestBase.bootstrap();
        Blocks.STONE.defaultBlockState().initCache();
        Blocks.AIR.defaultBlockState().initCache();
    }

    @Test
    public void nativeModelTessellationCullsSharedFacesWithoutWorldChunks() {
        BlockAndTintGetter world = world();
        when(world.getBlockState(any())).thenAnswer(call -> {
            BlockPos position = call.getArgument(0);
            return position.getY() == 0 && position.getZ() == 0 && (position.getX() == -16 || position.getX() == -15)
                ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
        });
        BlockStateModel model = new EastFaceModel();
        BlockStateModelSet models = new BlockStateModelSet(Map.of(Blocks.STONE.defaultBlockState(), model), model);
        try (PortalSectionMesh result = PortalSectionMesh.compile(SectionPos.asLong(-1, 0, 0), world, models,
            new FluidStateModelSet(Map.of(), null), new BlockColors(), true, PortalTerrainMaterials.VANILLA)) {
            MeshData mesh = result.meshes().get(ChunkSectionLayer.SOLID);
            assertEquals(4, mesh.drawState().vertexCount());
            assertEquals(6, mesh.drawState().indexCount());
            assertEquals(2.0f, mesh.vertexBuffer().getFloat(0), 0.0f);
        }
    }

    @Test
    public void shaderTerrainCarriesDestinationMaterialIdentityThroughNativeModelTessellation() {
        BlockAndTintGetter world = world();
        when(world.getBlockState(any())).thenAnswer(call -> BlockPos.ZERO.equals(call.getArgument(0))
            ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
        BlockStateModel model = new EastFaceModel();
        BlockStateModelSet models = new BlockStateModelSet(Map.of(Blocks.STONE.defaultBlockState(), model), model);
        PortalTerrainMaterials materials = new PortalTerrainMaterials(true, Map.of(Blocks.STONE.defaultBlockState(), 31000), 2,
            PortalTerrainMaterials.Lighting.VANILLA);
        try (PortalSectionMesh result = PortalSectionMesh.compile(SectionPos.asLong(0, 0, 0), world, models,
            new FluidStateModelSet(Map.of(), null), new BlockColors(), true, materials)) {
            MeshData mesh = result.meshes().get(ChunkSectionLayer.SOLID);
            assertEquals(materials.format(), mesh.drawState().format());
            assertEquals(4, mesh.drawState().vertexCount());
            for (int vertex = 0; vertex < 4; vertex++) {
                int offset = vertex * mesh.drawState().format().getVertexSize();
                assertEquals(31000, mesh.vertexBuffer().getShort(offset + 32));
                assertEquals(-1, mesh.vertexBuffer().getShort(offset + 34));
                assertEquals(-32, mesh.vertexBuffer().get(offset + 48));
            }
        }
    }

    @Test
    public void emptySnapshotProducesNoGpuGeometry() {
        BlockAndTintGetter world = world();
        when(world.getBlockState(any())).thenReturn(Blocks.AIR.defaultBlockState());
        try (PortalSectionMesh result = PortalSectionMesh.compile(SectionPos.asLong(8, -4, -2), world,
            new BlockStateModelSet(Map.of(), null), new FluidStateModelSet(Map.of(), null), new BlockColors(), true, PortalTerrainMaterials.VANILLA)) {
            assertTrue(result.meshes().isEmpty());
        }
    }

    private static BlockAndTintGetter world() {
        BlockAndTintGetter world = mock(BlockAndTintGetter.class);
        when(world.cardinalLighting()).thenReturn(CardinalLighting.DEFAULT);
        when(world.getRawBrightness(any(), anyInt())).thenReturn(15);
        when(world.getBrightness(any(), any())).thenReturn(15);
        return world;
    }

    private static final class EastFaceModel implements BlockStateModel, BlockStateModelPart {
        private final BakedQuad quad = new BakedQuad(new Vector3f(1, 0, 0), new Vector3f(1, 1, 0),
            new Vector3f(1, 1, 1), new Vector3f(1, 0, 1), 0L, 0L, 0L, 0L, Direction.EAST,
            new BakedQuad.MaterialInfo(null, ChunkSectionLayer.SOLID, null, null, null, -1, Direction.EAST, 0));

        @Override
        public void collectParts(RandomSource random, List<BlockStateModelPart> parts) {
            parts.add(this);
        }

        @Override
        public List<BakedQuad> getQuads(Direction direction) {
            return direction == Direction.EAST ? List.of(quad) : List.of();
        }

        @Override
        public boolean useAmbientOcclusion() {
            return true;
        }

        @Override
        public Material.Baked particleMaterial() {
            return null;
        }

        @Override
        public int materialFlags() {
            return 0;
        }
    }
}
