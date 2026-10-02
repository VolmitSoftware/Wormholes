package art.arcane.wormholes.modded.client.render;

import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.network.client.ClientViewEnvironment;
import art.arcane.wormholes.util.Direction;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.BeforeClass;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class PortalTerrainVerticesTest {
    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void materialSnapshotsAreImmutableAndVanillaKeepsItsNativeFormat() {
        Map<BlockState, Integer> source = new HashMap<>();
        source.put(Blocks.STONE.defaultBlockState(), 31000);
        PortalTerrainMaterials materials = new PortalTerrainMaterials(true, source, 5, PortalTerrainMaterials.Lighting.VANILLA);
        source.put(Blocks.STONE.defaultBlockState(), 7);
        assertEquals(31000, materials.blockId(Blocks.STONE.defaultBlockState()));
        assertEquals(-1, materials.blockId(Blocks.DIRT.defaultBlockState()));
        assertEquals(5, materials.revision());
        assertSame(DefaultVertexFormat.BLOCK, PortalTerrainMaterials.VANILLA.format());
        assertEquals(52, materials.format().getVertexSize());
        assertEquals(32, materials.format().getElement("mc_Entity").offset());
        assertEquals(44, materials.format().getElement("at_tangent").offset());
        assertEquals(48, materials.format().getElement("at_midBlock").offset());
    }

    @Test
    public void materialsUvsLightNormalsAndMidblockSurviveRotationAndReflection() {
        for (Direction[] axes : new Direction[][] {{Direction.E, Direction.U, Direction.S}, {Direction.W, Direction.U, Direction.S},
            {Direction.U, Direction.W, Direction.S}}) {
            ClientViewEnvironment.Transform mapping = new ClientViewEnvironment.Transform(axes[0], axes[1], axes[2],
                new GeometryVector(100.25, -31.5, 203.75));
            PortalVertexTransform transform = new PortalVertexTransform(mapping);
            try (ByteBufferBuilder base = new ByteBufferBuilder(512); ByteBufferBuilder extended = new ByteBufferBuilder(512)) {
                BufferBuilder builder = new BufferBuilder(base, PrimitiveTopology.QUADS, DefaultVertexFormat.BLOCK);
                PortalTerrainVertices attributes = new PortalTerrainVertices(builder);
                transform.target(attributes);
                transform.destinationBlock(new BlockPos(17, 8, -4), 96, -32, 192);
                transform.inputOrigin(0, 0, 0);
                float cx = (float) (17.5 * axes[0].x() + 8.5 * axes[1].x() - 3.5 * axes[2].x() + 4.25);
                float cy = (float) (17.5 * axes[0].y() + 8.5 * axes[1].y() - 3.5 * axes[2].y() + 0.5);
                float cz = (float) (17.5 * axes[0].z() + 8.5 * axes[1].z() - 3.5 * axes[2].z() + 11.75);
                attributes.block(31000, false, 15, cx, cy, cz);
                quad(transform, false);
                attributes.block(42, true, 3, cx, cy, cz);
                quad(transform, false);
                try (MeshData nativeMesh = builder.buildOrThrow()) {
                    transform.winding(nativeMesh);
                    try (MeshData mesh = attributes.expand(nativeMesh, extended)) {
                        ByteBuffer vertices = mesh.vertexBuffer();
                        int stride = mesh.drawState().format().getVertexSize();
                        boolean reflected = axes[0] == Direction.W;
                        for (int index = 0; index < 8; index++) {
                            int offset = index * stride;
                            assertEquals(index < 4 ? 31000 : 42, vertices.getShort(offset + 32));
                            assertEquals(index < 4 ? 0 : 1, vertices.getShort(offset + 34));
                            assertEquals(0.5F, vertices.getFloat(offset + 36), 0);
                            assertEquals(0.5F, vertices.getFloat(offset + 40), 0);
                            assertEquals(axes[0].x() * 127, vertices.get(offset + 44));
                            assertEquals(axes[0].y() * 127, vertices.get(offset + 45));
                            assertEquals(axes[0].z() * 127, vertices.get(offset + 46));
                            assertEquals(reflected ? 127 : -127, vertices.get(offset + 47));
                            assertEquals((byte) ((cx - vertices.getFloat(offset)) * 64), vertices.get(offset + 48));
                            assertEquals((byte) ((cy - vertices.getFloat(offset + 4)) * 64), vertices.get(offset + 49));
                            assertEquals((byte) ((cz - vertices.getFloat(offset + 8)) * 64), vertices.get(offset + 50));
                            assertEquals(index < 4 ? 15 : 3, vertices.get(offset + 51));
                            assertEquals(80, vertices.getShort(offset + 24));
                            assertEquals(160, vertices.getShort(offset + 26));
                            assertEquals(127, vertices.get(offset + 30));
                            assertEquals(255, Byte.toUnsignedInt(vertices.get(offset + 12)));
                        }
                    }
                }
            }
        }
    }

    @Test
    public void degenerateUvsStillProduceAUnitTangentWithoutInvalidAttributes() {
        try (ByteBufferBuilder base = new ByteBufferBuilder(512); ByteBufferBuilder extended = new ByteBufferBuilder(512)) {
            BufferBuilder builder = new BufferBuilder(base, PrimitiveTopology.QUADS, DefaultVertexFormat.BLOCK);
            PortalTerrainVertices attributes = new PortalTerrainVertices(builder);
            attributes.block(-1, false, 0, 0.5F, 0.5F, 0.5F);
            quad(attributes, true);
            try (MeshData nativeMesh = builder.buildOrThrow(); MeshData mesh = attributes.expand(nativeMesh, extended)) {
                ByteBuffer vertices = mesh.vertexBuffer();
                int x = vertices.get(44);
                int y = vertices.get(45);
                int z = vertices.get(46);
                assertTrue(x * x + y * y + z * z >= 126 * 126);
                assertEquals(0, z);
                assertEquals(-1, vertices.getShort(32));
            }
        }
    }

    @Test
    public void terrainWithoutNormalAttributesDerivesSlopedFacesFromGeometry() {
        try (ByteBufferBuilder base = new ByteBufferBuilder(512); ByteBufferBuilder extended = new ByteBufferBuilder(512)) {
            BufferBuilder builder = new BufferBuilder(base, PrimitiveTopology.QUADS, DefaultVertexFormat.BLOCK);
            PortalTerrainVertices attributes = new PortalTerrainVertices(builder);
            attributes.block(7, false, 0, 0.5F, 0.5F, 0.5F);
            attributes.addVertex(0, 0, 0).setColor(-1).setUv(0, 0).setUv2(0, 0);
            attributes.addVertex(1, 0, 0).setColor(-1).setUv(1, 0).setUv2(0, 0);
            attributes.addVertex(1, 1, 1).setColor(-1).setUv(1, 1).setUv2(0, 0);
            attributes.addVertex(0, 1, 1).setColor(-1).setUv(0, 1).setUv2(0, 0);
            try (MeshData nativeMesh = builder.buildOrThrow(); MeshData mesh = attributes.expand(nativeMesh, extended)) {
                ByteBuffer vertices = mesh.vertexBuffer();
                for (int vertex = 0; vertex < 4; vertex++) {
                    int offset = vertex * 52;
                    assertEquals(0, vertices.get(offset + 28));
                    assertEquals(-90, vertices.get(offset + 29));
                    assertEquals(90, vertices.get(offset + 30));
                    assertEquals(127, vertices.get(offset + 44));
                    assertEquals(0, vertices.get(offset + 45));
                    assertEquals(0, vertices.get(offset + 46));
                }
            }
        }
    }

    private static void quad(VertexConsumer vertices, boolean degenerate) {
        vertex(vertices, 0, 0, 0, 0);
        vertex(vertices, 1, 0, degenerate ? 0 : 1, 0);
        vertex(vertices, 1, 1, degenerate ? 0 : 1, degenerate ? 0 : 1);
        vertex(vertices, 0, 1, 0, degenerate ? 0 : 1);
    }

    private static void vertex(VertexConsumer vertices, float x, float y, float u, float v) {
        vertices.addVertex(x, y, 0).setColor(255, 128, 64, 255).setUv(u, v).setUv1(0, 0).setUv2(80, 160).setNormal(0, 0, 1);
    }
}
