package art.arcane.wormholes.modded.client.render;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.aperture.AperturePolygon;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.shape.Bounds2;
import art.arcane.optics.shape.PlaneShape;
import art.arcane.optics.shape.ShapeMesh;
import art.arcane.optics.shape.ShapeRaster;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

public final class PortalApertureMesh {
    static final int MAX_SUBDIVIDED_CELLS = 1 << 22;
    static final int SHAPE_VERTEX_BYTES = 20;
    private static final double TOUCH_DISTANCE_CELLS = 0.75D;

    private PortalApertureMesh() {
    }

    public static int subdivisions(int requested, int columns, int rows) {
        long cells = Math.max(1L, (long) columns * rows);
        int budget = (int) Math.floor(Math.sqrt((double) MAX_SUBDIVIDED_CELLS / cells));
        while ((long) budget * budget * cells > MAX_SUBDIVIDED_CELLS) {
            budget--;
        }
        return Math.max(1, Math.min(requested, budget));
    }

    public static long[] renderMask(ApertureDescriptor geometry, PlaneShape plane) {
        long[] mask = geometry.apertureMask();
        ShapeRaster raster = plane.raster(ShapeRaster.DEFAULT_SUBSAMPLES);
        Bounds2 reach = plane.bounds().grown(TOUCH_DISTANCE_CELLS);
        int columns = geometry.apertureWidth();
        for (int row = 0; row < geometry.apertureHeight(); row++) {
            for (int column = 0; column < columns; column++) {
                int bit = row * columns + column;
                if ((mask[bit >>> 6] & (1L << (bit & 63))) != 0L || raster.inside(column, row)
                    || !reach.contains(column + 0.5D, row + 0.5D)
                    || plane.signedDistance(column + 0.5D, row + 0.5D) >= TOUCH_DISTANCE_CELLS) {
                    continue;
                }
                mask[bit >>> 6] |= 1L << (bit & 63);
            }
        }
        return mask;
    }

    public static PortalGpuMesh quads(AperturePolygon aperture, ApertureDescriptor geometry) {
        try (ByteBufferBuilder allocation = new ByteBufferBuilder(1024)) {
            BufferBuilder builder = new BufferBuilder(allocation, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION);
            for (AperturePolygon.Rectangle rectangle : aperture.rectangles()) {
                for (Vec3d point : aperture.vertices(rectangle)) {
                    builder.addVertex((float) (point.x() - geometry.originX()),
                        (float) (point.y() - geometry.originY()), (float) (point.z() - geometry.originZ()));
                }
            }
            try (MeshData mesh = builder.buildOrThrow()) {
                return new PortalGpuMesh(mesh, null);
            }
        }
    }

    public static PortalGpuMesh shaped(ShapeMesh shape, AperturePolygon aperture, ApertureDescriptor geometry) {
        if (shape.isEmpty()) {
            return null;
        }
        float[] positions = shape.positions();
        float[] distances = shape.distances();
        int[] indices = shape.indices();
        ByteBuffer vertexData = MemoryUtil.memAlloc(shape.vertexCount() * SHAPE_VERTEX_BYTES);
        ByteBuffer indexData = MemoryUtil.memAlloc(indices.length * Integer.BYTES);
        try {
            for (int vertex = 0; vertex < shape.vertexCount(); vertex++) {
                Vec3d point = aperture.point(positions[vertex * 2], positions[vertex * 2 + 1]);
                vertexData.putFloat((float) (point.x() - geometry.originX()));
                vertexData.putFloat((float) (point.y() - geometry.originY()));
                vertexData.putFloat((float) (point.z() - geometry.originZ()));
                vertexData.putFloat(distances[vertex]);
                vertexData.putFloat(0.0F);
            }
            for (int index : indices) {
                indexData.putInt(index);
            }
            vertexData.flip();
            indexData.flip();
            return new PortalGpuMesh(vertexData, indexData, IndexType.INT, indices.length);
        } finally {
            MemoryUtil.memFree(vertexData);
            MemoryUtil.memFree(indexData);
        }
    }
}
