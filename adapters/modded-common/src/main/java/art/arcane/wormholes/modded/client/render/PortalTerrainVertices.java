package art.arcane.wormholes.modded.client.render;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.api.vertex.VertexFormatElement;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.Objects;

final class PortalTerrainVertices implements VertexConsumer {
    static final VertexFormat FORMAT = VertexFormat.builder(0)
        .addAttribute("Position", GpuFormat.RGB32_FLOAT)
        .addAttribute("Color", GpuFormat.RGBA8_UNORM)
        .addAttribute("UV0", GpuFormat.RG32_FLOAT)
        .addAttribute("UV2", GpuFormat.RG16_SINT)
        .addAttribute("Normal", GpuFormat.RGBA8_SNORM)
        .addAttribute("mc_Entity", GpuFormat.RG16_SINT)
        .addAttribute("mc_midTexCoord", GpuFormat.RG32_FLOAT)
        .addAttribute("at_tangent", GpuFormat.RGBA8_SNORM)
        .addAttribute("at_midBlock", GpuFormat.RGBA8_SNORM)
        .build();
    private static final String[] BASE_ATTRIBUTES = {"Position", "Color", "UV0", "UV2", "Normal"};

    private final VertexConsumer target;
    private final LongArrayList materials = new LongArrayList();
    private final FloatArrayList centers = new FloatArrayList();
    private long material;
    private float centerX;
    private float centerY;
    private float centerZ;
    private int vertices;

    PortalTerrainVertices(VertexConsumer target) {
        this.target = Objects.requireNonNull(target, "target");
    }

    void block(int id, boolean fluid, int emission, float x, float y, float z) {
        material = (id & 0xFFFFL) | ((fluid ? 1L : 0xFFFFL) << 16) | ((emission & 0xFFL) << 32);
        centerX = x;
        centerY = y;
        centerZ = z;
    }

    MeshData expand(MeshData mesh, ByteBufferBuilder allocation) {
        MeshData.DrawState state = mesh.drawState();
        if (state.vertexCount() != vertices || vertices % 4 != 0) {
            throw new IllegalStateException("Terrain material data does not match quad vertices");
        }
        ByteBuffer source = mesh.vertexBuffer();
        int bytes = Math.multiplyExact(vertices, FORMAT.getVertexSize());
        ByteBuffer output = MemoryUtil.memByteBuffer(allocation.reserve(bytes), bytes);
        copyBase(source, output, state.format());
        for (int quad = 0; quad < materials.size(); quad++) {
            if (state.format().getElement("Normal") == null) {
                normal(output, quad * 4 * FORMAT.getVertexSize());
            }
            extendQuad(output, quad);
        }
        return new MeshData(Objects.requireNonNull(allocation.build()), new MeshData.DrawState(FORMAT, vertices,
            state.indexCount(), state.primitiveTopology(), state.indexType()));
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        if (vertices++ % 4 == 0) {
            materials.add(material);
            centers.add(centerX);
            centers.add(centerY);
            centers.add(centerZ);
        }
        target.addVertex(x, y, z);
        return this;
    }

    @Override
    public VertexConsumer setNormal(float x, float y, float z) {
        target.setNormal(x, y, z);
        return this;
    }

    @Override
    public VertexConsumer setColor(int red, int green, int blue, int alpha) {
        target.setColor(red, green, blue, alpha);
        return this;
    }

    @Override
    public VertexConsumer setColor(int color) {
        target.setColor(color);
        return this;
    }

    @Override
    public VertexConsumer setUv(float u, float v) {
        target.setUv(u, v);
        return this;
    }

    @Override
    public VertexConsumer setUv1(int u, int v) {
        target.setUv1(u, v);
        return this;
    }

    @Override
    public VertexConsumer setUv2(int u, int v) {
        target.setUv2(u, v);
        return this;
    }

    @Override
    public VertexConsumer setUv3(float u, float v) {
        target.setUv3(u, v);
        return this;
    }

    @Override
    public VertexConsumer setLineWidth(float width) {
        target.setLineWidth(width);
        return this;
    }

    private void copyBase(ByteBuffer source, ByteBuffer output, VertexFormat sourceFormat) {
        for (String name : BASE_ATTRIBUTES) {
            VertexFormatElement from = sourceFormat.getElement(name);
            if (from == null && name.equals("Normal")) {
                continue;
            }
            Objects.requireNonNull(from, name);
            VertexFormatElement to = FORMAT.getElement(name);
            int bytes = to.format().blockSize();
            if (from.format().blockSize() != bytes) {
                throw new IllegalStateException("Unsupported terrain attribute " + name);
            }
            for (int vertex = 0; vertex < vertices; vertex++) {
                int sourceOffset = vertex * sourceFormat.getVertexSize() + from.offset();
                int targetOffset = vertex * FORMAT.getVertexSize() + to.offset();
                output.put(targetOffset, source, sourceOffset, bytes);
            }
        }
    }

    private static void normal(ByteBuffer vertices, int base) {
        int stride = FORMAT.getVertexSize();
        float ax = vertices.getFloat(base + stride) - vertices.getFloat(base);
        float ay = vertices.getFloat(base + stride + 4) - vertices.getFloat(base + 4);
        float az = vertices.getFloat(base + stride + 8) - vertices.getFloat(base + 8);
        float bx = vertices.getFloat(base + 2 * stride) - vertices.getFloat(base);
        float by = vertices.getFloat(base + 2 * stride + 4) - vertices.getFloat(base + 4);
        float bz = vertices.getFloat(base + 2 * stride + 8) - vertices.getFloat(base + 8);
        float nx = ay * bz - az * by;
        float ny = az * bx - ax * bz;
        float nz = ax * by - ay * bx;
        float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (length < 1.0E-10F) {
            ax = vertices.getFloat(base + 3 * stride) - vertices.getFloat(base);
            ay = vertices.getFloat(base + 3 * stride + 4) - vertices.getFloat(base + 4);
            az = vertices.getFloat(base + 3 * stride + 8) - vertices.getFloat(base + 8);
            nx = by * az - bz * ay;
            ny = bz * ax - bx * az;
            nz = bx * ay - by * ax;
            length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        }
        if (length < 1.0E-10F) {
            ny = 1;
            length = 1;
        }
        int normal = FORMAT.getElement("Normal").offset();
        for (int vertex = 0; vertex < 4; vertex++) {
            int offset = base + vertex * stride + normal;
            vertices.put(offset, (byte) Math.round(nx / length * 127));
            vertices.put(offset + 1, (byte) Math.round(ny / length * 127));
            vertices.put(offset + 2, (byte) Math.round(nz / length * 127));
        }
    }

    private void extendQuad(ByteBuffer output, int quad) {
        int stride = FORMAT.getVertexSize();
        int base = quad * 4 * stride;
        int uv = FORMAT.getElement("UV0").offset();
        int id = FORMAT.getElement("mc_Entity").offset();
        int midUv = FORMAT.getElement("mc_midTexCoord").offset();
        int midBlock = FORMAT.getElement("at_midBlock").offset();
        long material = materials.getLong(quad);
        float u = 0;
        float v = 0;
        for (int vertex = 0; vertex < 4; vertex++) {
            u += output.getFloat(base + vertex * stride + uv);
            v += output.getFloat(base + vertex * stride + uv + 4);
        }
        for (int vertex = 0; vertex < 4; vertex++) {
            int offset = base + vertex * stride;
            output.put(offset + FORMAT.getElement("Normal").offset() + 3, (byte) 0);
            output.putShort(offset + id, (short) material);
            output.putShort(offset + id + 2, (short) (material >>> 16));
            output.putFloat(offset + midUv, u * 0.25F);
            output.putFloat(offset + midUv + 4, v * 0.25F);
            output.put(offset + midBlock, (byte) ((centers.getFloat(quad * 3) - output.getFloat(offset)) * 64));
            output.put(offset + midBlock + 1, (byte) ((centers.getFloat(quad * 3 + 1) - output.getFloat(offset + 4)) * 64));
            output.put(offset + midBlock + 2, (byte) ((centers.getFloat(quad * 3 + 2) - output.getFloat(offset + 8)) * 64));
            output.put(offset + midBlock + 3, (byte) (material >>> 32));
        }
        tangent(output, base);
    }

    private static void tangent(ByteBuffer vertices, int base) {
        int stride = FORMAT.getVertexSize();
        int uv = FORMAT.getElement("UV0").offset();
        float ax = vertices.getFloat(base + stride) - vertices.getFloat(base);
        float ay = vertices.getFloat(base + stride + 4) - vertices.getFloat(base + 4);
        float az = vertices.getFloat(base + stride + 8) - vertices.getFloat(base + 8);
        float bx = vertices.getFloat(base + 2 * stride) - vertices.getFloat(base);
        float by = vertices.getFloat(base + 2 * stride + 4) - vertices.getFloat(base + 4);
        float bz = vertices.getFloat(base + 2 * stride + 8) - vertices.getFloat(base + 8);
        float au = vertices.getFloat(base + stride + uv) - vertices.getFloat(base + uv);
        float av = vertices.getFloat(base + stride + uv + 4) - vertices.getFloat(base + uv + 4);
        float bu = vertices.getFloat(base + 2 * stride + uv) - vertices.getFloat(base + uv);
        float bv = vertices.getFloat(base + 2 * stride + uv + 4) - vertices.getFloat(base + uv + 4);
        float determinant = au * bv - bu * av;
        float inverse = Math.abs(determinant) > 1.0E-10F ? 1 / determinant : 1;
        float tx = inverse * (bv * ax - av * bx);
        float ty = inverse * (bv * ay - av * by);
        float tz = inverse * (bv * az - av * bz);
        float ux = inverse * (au * bx - bu * ax);
        float uy = inverse * (au * by - bu * ay);
        float uz = inverse * (au * bz - bu * az);
        for (int vertex = 0; vertex < 4; vertex++) {
            packTangent(vertices, base + vertex * stride, tx, ty, tz, ux, uy, uz);
        }
    }

    private static void packTangent(ByteBuffer vertices, int offset, float tx, float ty, float tz, float bx, float by, float bz) {
        int normal = FORMAT.getElement("Normal").offset();
        int tangent = FORMAT.getElement("at_tangent").offset();
        float nx = vertices.get(offset + normal) / 127F;
        float ny = vertices.get(offset + normal + 1) / 127F;
        float nz = vertices.get(offset + normal + 2) / 127F;
        float dot = tx * nx + ty * ny + tz * nz;
        tx -= nx * dot;
        ty -= ny * dot;
        tz -= nz * dot;
        float length = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
        if (length < 1.0E-10F) {
            tx = Math.abs(ny) < 0.9F ? nz : -ny;
            ty = Math.abs(ny) < 0.9F ? 0 : nx;
            tz = Math.abs(ny) < 0.9F ? -nx : 0;
            length = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
        }
        if (length < 1.0E-10F) {
            tx = 1;
            ty = 0;
            tz = 0;
            length = 1;
        }
        tx /= length;
        ty /= length;
        tz /= length;
        float handedness = (ty * nz - tz * ny) * bx + (tz * nx - tx * nz) * by + (tx * ny - ty * nx) * bz;
        vertices.put(offset + tangent, (byte) Math.round(tx * 127));
        vertices.put(offset + tangent + 1, (byte) Math.round(ty * 127));
        vertices.put(offset + tangent + 2, (byte) Math.round(tz * 127));
        vertices.put(offset + tangent + 3, (byte) (handedness < 0 ? -127 : 127));
    }
}
