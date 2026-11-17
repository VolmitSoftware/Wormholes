package art.arcane.wormholes.modded.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/**
 * A static GPU mesh (aperture or streamed section): its own vertex array, vertex buffer and 32-bit triangle index
 * buffer, the same objects {@code VertexBuffer} owns, built from CPU data that a worker thread may have produced.
 * Translucent sections keep their quad centres so the index buffer can be re-sorted back to front.
 */
public final class PortalGpuMesh implements AutoCloseable {
    private final int vertexArray;
    private final int vertexBuffer;
    private final int indexBuffer;
    private final int indexCount;
    private final long bytes;
    private final float[] quadCenters;
    private float sortX = Float.NaN;
    private float sortY;
    private float sortZ;
    private boolean closed;

    PortalGpuMesh() {
        vertexArray = 0;
        vertexBuffer = 0;
        indexBuffer = 0;
        indexCount = 0;
        bytes = 0L;
        quadCenters = null;
    }

    /**
     * Uploads {@code vertices} in {@code format} with triangle {@code indices} ({@code indexCount} ints). Render thread
     * only. {@code quadCenters} (x, y, z per quad, mesh space) makes the mesh sortable; the indices must then be the
     * plain six-per-quad expansion.
     */
    PortalGpuMesh(ByteBuffer vertices, VertexFormat format, ByteBuffer indices, int indexCount, float[] quadCenters) {
        RenderSystem.assertOnRenderThread();
        this.indexCount = indexCount;
        this.quadCenters = quadCenters;
        bytes = (long) vertices.remaining() + indices.remaining();
        vertexArray = GlStateManager._glGenVertexArrays();
        vertexBuffer = GlStateManager._glGenBuffers();
        indexBuffer = GlStateManager._glGenBuffers();
        BufferUploader.invalidate();
        GlStateManager._glBindVertexArray(vertexArray);
        try {
            GlStateManager._glBindBuffer(GL15C.GL_ARRAY_BUFFER, vertexBuffer);
            RenderSystem.glBufferData(GL15C.GL_ARRAY_BUFFER, vertices, GL15C.GL_STATIC_DRAW);
            format.setupBufferState();
            GlStateManager._glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, indexBuffer);
            RenderSystem.glBufferData(GL15C.GL_ELEMENT_ARRAY_BUFFER, indices,
                quadCenters == null ? GL15C.GL_STATIC_DRAW : GL15C.GL_DYNAMIC_DRAW);
        } finally {
            GlStateManager._glBindVertexArray(0);
        }
    }

    /** Uploads a quad mesh: {@code vertexCount} vertices in groups of four, each split into two triangles. */
    static PortalGpuMesh quads(ByteBuffer vertices, VertexFormat format, int vertexCount, float[] quadCenters) {
        int quads = vertexCount / 4;
        ByteBuffer indices = MemoryUtil.memAlloc(quads * 6 * Integer.BYTES);
        try {
            IntBuffer view = indices.asIntBuffer();
            for (int quad = 0; quad < quads; quad++) {
                putQuad(view, quad);
            }
            return new PortalGpuMesh(vertices, format, indices, quads * 6, quadCenters);
        } finally {
            MemoryUtil.memFree(indices);
        }
    }

    private static void putQuad(IntBuffer indices, int quad) {
        int vertex = quad * 4;
        indices.put(vertex).put(vertex + 1).put(vertex + 2).put(vertex + 2).put(vertex + 3).put(vertex);
    }

    /**
     * Draws the mesh with {@link RenderSystem#getShader()}, the model-view and projection from {@link RenderSystem},
     * the way {@code VertexBuffer.drawWithShader} does. Render thread only.
     */
    public void draw() {
        ShaderInstance shader = RenderSystem.getShader();
        if (shader == null || closed || indexCount == 0) {
            return;
        }
        for (int sampler = 0; sampler < 12; sampler++) {
            shader.setSampler("Sampler" + sampler, RenderSystem.getShaderTexture(sampler));
        }
        if (shader.MODEL_VIEW_MATRIX != null) {
            shader.MODEL_VIEW_MATRIX.set(RenderSystem.getModelViewMatrix());
        }
        if (shader.PROJECTION_MATRIX != null) {
            shader.PROJECTION_MATRIX.set(RenderSystem.getProjectionMatrix());
        }
        if (shader.COLOR_MODULATOR != null) {
            shader.COLOR_MODULATOR.set(RenderSystem.getShaderColor());
        }
        if (shader.FOG_START != null) {
            shader.FOG_START.set(RenderSystem.getShaderFogStart());
        }
        if (shader.FOG_END != null) {
            shader.FOG_END.set(RenderSystem.getShaderFogEnd());
        }
        if (shader.FOG_COLOR != null) {
            shader.FOG_COLOR.set(RenderSystem.getShaderFogColor());
        }
        if (shader.FOG_SHAPE != null) {
            shader.FOG_SHAPE.set(RenderSystem.getShaderFogShape().getIndex());
        }
        shader.apply();
        drawApplied();
        shader.clear();
    }

    /** Draws the mesh with a shader the caller has already applied. Render thread only. */
    void drawApplied() {
        if (closed || indexCount == 0) {
            return;
        }
        BufferUploader.invalidate();
        GlStateManager._glBindVertexArray(vertexArray);
        RenderSystem.drawElements(GL11C.GL_TRIANGLES, indexCount, GL11C.GL_UNSIGNED_INT);
        GlStateManager._glBindVertexArray(0);
    }

    /** Re-orders the quads back to front for an eye at ({@code x}, {@code y}, {@code z}) in mesh space. */
    void sort(float x, float y, float z) {
        if (closed || quadCenters == null || (x == sortX && y == sortY && z == sortZ)) {
            return;
        }
        int quads = quadCenters.length / 3;
        long[] order = new long[quads];
        for (int quad = 0; quad < quads; quad++) {
            float dx = quadCenters[quad * 3] - x;
            float dy = quadCenters[quad * 3 + 1] - y;
            float dz = quadCenters[quad * 3 + 2] - z;
            float distance = dx * dx + dy * dy + dz * dz;
            order[quad] = ((long) Float.floatToIntBits(distance) << 32) | quad;
        }
        java.util.Arrays.sort(order);
        ByteBuffer indices = MemoryUtil.memAlloc(quads * 6 * Integer.BYTES);
        try {
            IntBuffer view = indices.asIntBuffer();
            for (int index = quads - 1; index >= 0; index--) {
                putQuad(view, (int) order[index]);
            }
            BufferUploader.invalidate();
            GlStateManager._glBindVertexArray(vertexArray);
            GlStateManager._glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, indexBuffer);
            GL15C.glBufferSubData(GL15C.GL_ELEMENT_ARRAY_BUFFER, 0L, indices);
            GlStateManager._glBindVertexArray(0);
        } finally {
            MemoryUtil.memFree(indices);
        }
        sortX = x;
        sortY = y;
        sortZ = z;
    }

    /** Approximate GPU bytes held. */
    public long bytes() {
        return bytes;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (vertexArray == 0) {
            return;
        }
        RenderSystem.assertOnRenderThread();
        GlStateManager._glDeleteBuffers(vertexBuffer);
        GlStateManager._glDeleteBuffers(indexBuffer);
        GlStateManager._glDeleteVertexArrays(vertexArray);
    }
}
