package art.arcane.wormholes.modded.client.render;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.aperture.AperturePolygon;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.shape.PlaneShape;
import art.arcane.optics.shape.ShapeMesh;
import art.arcane.optics.shape.ShapeRaster;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * Builds aperture meshes for stencil marking and compositing, in aperture-origin-relative coordinates. Vertices carry
 * opaque white {@code Color} so the stencil path can draw them with {@code position_color}, which carries the portal
 * clip plane; the composite shaders read only {@code Position} and {@code UV0}.
 */
public final class PortalApertureMesh {
    static final int MAX_SUBDIVIDED_CELLS = 1 << 22;
    static final int SHAPE_VERTEX_BYTES = 24;
    private static final int POSITION_VERTEX_BYTES = 16;
    private static final int WHITE = 0xFFFFFFFF;

    private PortalApertureMesh() {
    }

    /** Subdivision count for a shaped aperture of the given size. */
    public static int subdivisions(int requested, int columns, int rows) {
        long cells = Math.max(1L, (long) columns * rows);
        int budget = (int) Math.floor(Math.sqrt((double) MAX_SUBDIVIDED_CELLS / cells));
        while ((long) budget * budget * cells > MAX_SUBDIVIDED_CELLS) {
            budget--;
        }
        return Math.max(1, Math.min(requested, budget));
    }

    /** Bitmask of plane cells inside the rendered opening. */
    public static long[] renderMask(ApertureDescriptor geometry, PlaneShape plane) {
        long[] mask = geometry.apertureMask();
        ShapeRaster raster = plane.raster(ShapeRaster.DEFAULT_SUBSAMPLES);
        int columns = geometry.apertureWidth();
        for (int row = 0; row < geometry.apertureHeight(); row++) {
            for (int column = 0; column < columns; column++) {
                int bit = row * columns + column;
                if ((mask[bit >>> 6] & (1L << (bit & 63))) != 0L || raster.inside(column, row)
                    || raster.coverage(column, row) != ShapeRaster.Coverage.PARTIAL) {
                    continue;
                }
                mask[bit >>> 6] |= 1L << (bit & 63);
            }
        }
        return mask;
    }

    /** Mesh of the aperture's rectangular quads ({@code POSITION_COLOR}). */
    public static PortalGpuMesh quads(AperturePolygon aperture, ApertureDescriptor geometry) {
        List<AperturePolygon.Rectangle> rectangles = aperture.rectangles();
        int vertexCount = 0;
        for (AperturePolygon.Rectangle rectangle : rectangles) {
            vertexCount += aperture.vertices(rectangle).size();
        }
        ByteBuffer vertices = MemoryUtil.memAlloc(Math.max(1, vertexCount) * POSITION_VERTEX_BYTES);
        try {
            for (AperturePolygon.Rectangle rectangle : rectangles) {
                for (Vec3d point : aperture.vertices(rectangle)) {
                    vertices.putFloat((float) (point.x() - geometry.originX()));
                    vertices.putFloat((float) (point.y() - geometry.originY()));
                    vertices.putFloat((float) (point.z() - geometry.originZ()));
                    vertices.putInt(WHITE);
                }
            }
            vertices.flip();
            return PortalGpuMesh.quads(vertices, DefaultVertexFormat.POSITION_COLOR, vertexCount, null);
        } finally {
            MemoryUtil.memFree(vertices);
        }
    }

    /** Mesh of a shaped aperture ({@code POSITION_COLOR_TEX}, U carries the signed edge distance), or {@code null} when empty. */
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
                vertexData.putInt(WHITE);
                vertexData.putFloat(distances[vertex]);
                vertexData.putFloat(0.0F);
            }
            for (int index : indices) {
                indexData.putInt(index);
            }
            vertexData.flip();
            indexData.flip();
            return new PortalGpuMesh(vertexData, DefaultVertexFormat.POSITION_COLOR_TEX, indexData, indices.length, null);
        } finally {
            MemoryUtil.memFree(vertexData);
            MemoryUtil.memFree(indexData);
        }
    }
}
