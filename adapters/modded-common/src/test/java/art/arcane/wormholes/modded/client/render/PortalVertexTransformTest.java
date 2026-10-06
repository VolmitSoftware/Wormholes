package art.arcane.wormholes.modded.client.render;

import art.arcane.optics.stream.ProjectionEnvironment;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.DirectionMapping;
import art.arcane.optics.math.Face;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.junit.Test;

import java.nio.ByteBuffer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PortalVertexTransformTest {
    @Test
    public void everyMirrorPlaneAndQuarterTurnMovesSourceFacesAndNormalsExactlyOnce() {
        for (Face normal : Face.values()) {
            for (int quarterTurns = 0; quarterTurns < 4; quarterTurns++) {
                DirectionMapping mapping = DirectionMapping.mirror(Frame.canonical(normal), quarterTurns, new double[3]);
                Face xAxis = mapping.map(Face.E);
                Face yAxis = mapping.map(Face.U);
                Face zAxis = mapping.map(Face.S);
                PortalVertexTransform transform = transform(xAxis, yAxis, zAxis);
                for (Face face : Face.values()) {
                    Face reflected = mapping.map(face);
                    try (ByteBufferBuilder allocation = new ByteBufferBuilder(512)) {
                        BufferBuilder builder = builder(allocation);
                        transform.target(builder).destinationBlock(BlockPos.ZERO, 0, 0, 0);
                        transform.inputOrigin(0, 0, 0);
                        for (int index = 0; index < 4; index++) {
                            vertex(transform, 0.5F + face.x() * 0.5F, 0.5F + face.y() * 0.5F, 0.5F + face.z() * 0.5F,
                                index, face.x(), face.y(), face.z());
                        }
                        try (MeshData mesh = builder.buildOrThrow()) {
                            transform.winding(mesh);
                            ByteBuffer buffer = mesh.vertexBuffer();
                            int stride = mesh.drawState().format().getVertexSize();
                            int[] order = {0, 3, 2, 1};
                            for (int index = 0; index < 4; index++) {
                                int offset = index * stride;
                                assertEquals((xAxis.x() + yAxis.x() + zAxis.x() + reflected.x()) * 0.5F, buffer.getFloat(offset), 0);
                                assertEquals((xAxis.y() + yAxis.y() + zAxis.y() + reflected.y()) * 0.5F, buffer.getFloat(offset + 4), 0);
                                assertEquals((xAxis.z() + yAxis.z() + zAxis.z() + reflected.z()) * 0.5F, buffer.getFloat(offset + 8), 0);
                                assertEquals(reflected.x() * 127, buffer.get(offset + 32));
                                assertEquals(reflected.y() * 127, buffer.get(offset + 33));
                                assertEquals(reflected.z() * 127, buffer.get(offset + 34));
                                assertEquals(order[index] / 4F, buffer.getFloat(offset + 16), 0);
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    public void oppositeQuarterTurnsProduceOppositeVerticalSlabHalvesAndNormals() {
        for (boolean clockwise : new boolean[] {false, true}) {
            Face sourceX = clockwise ? Face.D : Face.U;
            Face sourceY = clockwise ? Face.E : Face.W;
            PortalVertexTransform transform = transform(sourceX, sourceY, Face.S);
            try (ByteBufferBuilder allocation = new ByteBufferBuilder(512)) {
                BufferBuilder builder = builder(allocation);
                transform.target(builder).destinationBlock(clockwise ? new BlockPos(-4, 2, 4) : new BlockPos(3, -3, 4), 0, 0, 0);
                transform.inputOrigin(0, 0, 0);
                for (float height : new float[] {0, 0.5F}) {
                    vertex(transform, 0, height, 0, 0, 0, 1, 0);
                    vertex(transform, 0, height, 1, 1, 0, 1, 0);
                    vertex(transform, 1, height, 1, 2, 0, 1, 0);
                    vertex(transform, 1, height, 0, 3, 0, 1, 0);
                }
                try (MeshData mesh = builder.buildOrThrow()) {
                    transform.winding(mesh);
                    ByteBuffer buffer = mesh.vertexBuffer();
                    int stride = mesh.drawState().format().getVertexSize();
                    assertEquals(clockwise ? 2 : 3, buffer.getFloat(0), 0);
                    assertEquals(clockwise ? 4 : 3, buffer.getFloat(4), 0);
                    for (int index = 0; index < 8; index++) {
                        int offset = index * stride;
                        float x = buffer.getFloat(offset);
                        assertTrue(x >= (clockwise ? 2 : 2.5F) && x <= (clockwise ? 2.5F : 3));
                        assertEquals(sourceY.x() * 127, buffer.get(offset + 32));
                        assertEquals(0, buffer.get(offset + 33));
                        assertEquals(0, buffer.get(offset + 34));
                    }
                }
            }
        }
    }

    @Test
    public void fluidSectionOffsetsAndModelLocalOffsetsProduceIdenticalVertices() {
        PortalVertexTransform transform = transform(Face.U, Face.W, Face.S);
        try (ByteBufferBuilder allocation = new ByteBufferBuilder(512)) {
            BufferBuilder builder = builder(allocation);
            transform.target(builder);
            for (int input : new int[] {0, 1}) {
                int x = input * 15;
                int y = input * 14;
                int z = input * 3;
                transform.destinationBlock(new BlockPos(2, -7, 10), 0, 0, 0);
                transform.inputOrigin(x, y, z);
                vertex(transform, x, y + 0.875F, z, 0, 0, 1, 0);
                vertex(transform, x, y + 0.875F, z + 1, 1, 0, 1, 0);
                vertex(transform, x + 1, y + 0.875F, z + 1, 2, 0, 1, 0);
                vertex(transform, x + 1, y + 0.875F, z, 3, 0, 1, 0);
            }
            try (MeshData mesh = builder.buildOrThrow()) {
                ByteBuffer buffer = mesh.vertexBuffer();
                int bytes = mesh.drawState().format().getVertexSize() * 4;
                for (int offset = 0; offset < bytes; offset++) {
                    if (offset % mesh.drawState().format().getVertexSize() == 35) {
                        continue;
                    }
                    assertEquals("vertex attribute byte " + offset, buffer.get(offset), buffer.get(offset + bytes));
                }
                assertEquals(6.125F, buffer.getFloat(0), 0);
                assertEquals(2, buffer.getFloat(4), 0);
                assertEquals(10, buffer.getFloat(8), 0);
            }
        }
    }

    @Test
    public void reflectedQuadsKeepWindingUvsColorAndLightAttachedToEachVertex() {
        PortalVertexTransform transform = transform(Face.W, Face.U, Face.S);
        try (ByteBufferBuilder allocation = new ByteBufferBuilder(512)) {
            BufferBuilder builder = builder(allocation);
            transform.target(builder).destinationBlock(new BlockPos(-1, 0, 0), 0, 0, 0);
            transform.inputOrigin(0, 0, 0);
            for (int z = 0; z < 2; z++) {
                vertex(transform, 0, 0, z, 0, 0, 0, 1);
                vertex(transform, 1, 0, z, 1, 0, 0, 1);
                vertex(transform, 1, 1, z, 2, 0, 0, 1);
                vertex(transform, 0, 1, z, 3, 0, 0, 1);
            }
            try (MeshData mesh = builder.buildOrThrow()) {
                transform.winding(mesh);
                ByteBuffer buffer = mesh.vertexBuffer();
                int stride = mesh.drawState().format().getVertexSize();
                int[] order = {0, 3, 2, 1};
                for (int index = 0; index < 8; index++) {
                    int original = order[index % 4];
                    int offset = index * stride;
                    assertEquals(original < 2 ? 0 : 1, buffer.getFloat(offset + 4), 0);
                    assertEquals(original == 0 || original == 3 ? 1 : 0, buffer.getFloat(offset), 0);
                    assertEquals(original / 4F, buffer.getFloat(offset + 16), 0);
                    assertEquals((3 - original) / 4F, buffer.getFloat(offset + 20), 0);
                    assertEquals(40 + original, Byte.toUnsignedInt(buffer.get(offset + 12)));
                    assertEquals(80 + original, Short.toUnsignedInt(buffer.getShort(offset + 28)));
                    assertEquals(160 + original, Short.toUnsignedInt(buffer.getShort(offset + 30)));
                    assertEquals(127, buffer.get(offset + 34));
                }
                float ax = buffer.getFloat(stride) - buffer.getFloat(0);
                float ay = buffer.getFloat(stride + 4) - buffer.getFloat(4);
                float bx = buffer.getFloat(2 * stride) - buffer.getFloat(0);
                float by = buffer.getFloat(2 * stride + 4) - buffer.getFloat(4);
                assertTrue(ax * by - ay * bx > 0);
            }
        }
    }

    @Test
    public void fractionalTranslationAtNegativeNativeCoordinatesAlignsTerrainFluidsAndBlockEntities() {
        ProjectionEnvironment.Transform mapping = new ProjectionEnvironment.Transform(Face.D, Face.E, Face.S,
            new art.arcane.optics.math.Vec3(100.5D, -31.25D, 200.75D));
        BlockPos source = new BlockPos(-17, -33, -49);
        Vec3 sectionOrigin = new Vec3(64, -16, 144);
        PoseStack pose = new PoseStack();
        PortalFeatureRenderer.blockPose(pose, source, sectionOrigin, mapping, PortalProjection.rotation(mapping));
        Vector3f[] model = {new Vector3f(0, 0, 0), new Vector3f(0.125F, 0.25F, 0.5F),
            new Vector3f(1, 0.25F, 1), new Vector3f(1, 0, 0)};
        PortalVertexTransform transform = new PortalVertexTransform(mapping);
        try (ByteBufferBuilder allocation = new ByteBufferBuilder(512)) {
            BufferBuilder builder = builder(allocation);
            transform.target(builder).destinationBlock(source, 64, -16, 144);
            for (int origin : new int[] {0, 15}) {
                transform.inputOrigin(origin, origin, origin);
                for (int index = 0; index < model.length; index++) {
                    Vector3f point = model[index];
                    vertex(transform, point.x + origin, point.y + origin, point.z + origin, index, 0, 1, 0);
                }
            }
            try (MeshData mesh = builder.buildOrThrow()) {
                ByteBuffer buffer = mesh.vertexBuffer();
                int stride = mesh.drawState().format().getVertexSize();
                assertEquals(3.5F, buffer.getFloat(0), 0);
                assertEquals(1.75F, buffer.getFloat(4), 0);
                assertEquals(7.75F, buffer.getFloat(8), 0);
                for (int vertex = 0; vertex < 8; vertex++) {
                    Vector3f expected = pose.last().pose().transformPosition(new Vector3f(model[vertex % 4]));
                    assertEquals(expected.x, buffer.getFloat(vertex * stride), 0);
                    assertEquals(expected.y, buffer.getFloat(vertex * stride + 4), 0);
                    assertEquals(expected.z, buffer.getFloat(vertex * stride + 8), 0);
                }
            }
        }
    }

    private static PortalVertexTransform transform(Face x, Face y, Face z) {
        return new PortalVertexTransform(new ProjectionEnvironment.Transform(x, y, z, new art.arcane.optics.math.Vec3(0, 0, 0)));
    }

    private static BufferBuilder builder(ByteBufferBuilder allocation) {
        return new BufferBuilder(allocation, PrimitiveTopology.QUADS, DefaultVertexFormat.ENTITY);
    }

    private static void vertex(PortalVertexTransform transform, float x, float y, float z, int index, float nx, float ny, float nz) {
        transform.addVertex(x, y, z).setColor(40 + index, 60 + index, 80 + index, 255)
            .setUv(index / 4F, (3 - index) / 4F).setUv1(index, index + 1).setUv2(80 + index, 160 + index).setNormal(nx, ny, nz);
    }
}
