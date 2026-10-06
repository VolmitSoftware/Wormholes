package art.arcane.wormholes.modded.client.render;

import art.arcane.optics.math.Face;
import art.arcane.optics.frame.OpticTransform;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.core.BlockPos;

import java.nio.ByteBuffer;

final class PortalVertexTransform implements VertexConsumer {
    private final Face xAxis;
    private final Face yAxis;
    private final Face zAxis;
    private final boolean reflected;
    private final OpticTransform transform;
    private VertexConsumer target;
    private float x;
    private float y;
    private float z;
    private float inputX;
    private float inputY;
    private float inputZ;

    PortalVertexTransform(OpticTransform transform) {
        this.transform = transform;
        xAxis = transform.permutation().x();
        yAxis = transform.permutation().y();
        zAxis = transform.permutation().z();
        reflected = transform.reflects();
    }

    void destinationBlock(BlockPos position, int sectionX, int sectionY, int sectionZ) {
        double sx = position.getX() + 0.5D;
        double sy = position.getY() + 0.5D;
        double sz = position.getZ() + 0.5D;
        x = (float) (sx * xAxis.x() + sy * yAxis.x() + sz * zAxis.x() + transform.translationX() - sectionX);
        y = (float) (sx * xAxis.y() + sy * yAxis.y() + sz * zAxis.y() + transform.translationY() - sectionY);
        z = (float) (sx * xAxis.z() + sy * yAxis.z() + sz * zAxis.z() + transform.translationZ() - sectionZ);
    }

    float centerX() {
        return x;
    }

    float centerY() {
        return y;
    }

    float centerZ() {
        return z;
    }

    void inputOrigin(int x, int y, int z) {
        inputX = x + 0.5F;
        inputY = y + 0.5F;
        inputZ = z + 0.5F;
    }

    PortalVertexTransform target(VertexConsumer target) {
        this.target = target;
        return this;
    }

    void winding(MeshData mesh) {
        if (!reflected) {
            return;
        }
        ByteBuffer vertices = mesh.vertexBuffer();
        int stride = mesh.drawState().format().getVertexSize();
        for (int quad = 0; quad < mesh.drawState().vertexCount(); quad += 4) {
            int first = (quad + 1) * stride;
            int second = (quad + 3) * stride;
            for (int offset = 0; offset < stride; offset++) {
                byte value = vertices.get(first + offset);
                vertices.put(first + offset, vertices.get(second + offset));
                vertices.put(second + offset, value);
            }
        }
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        float dx = x - inputX;
        float dy = y - inputY;
        float dz = z - inputZ;
        target.addVertex(this.x + dx * xAxis.x() + dy * yAxis.x() + dz * zAxis.x(),
            this.y + dx * xAxis.y() + dy * yAxis.y() + dz * zAxis.y(),
            this.z + dx * xAxis.z() + dy * yAxis.z() + dz * zAxis.z());
        return this;
    }

    @Override
    public VertexConsumer setNormal(float x, float y, float z) {
        target.setNormal(x * xAxis.x() + y * yAxis.x() + z * zAxis.x(),
            x * xAxis.y() + y * yAxis.y() + z * zAxis.y(), x * xAxis.z() + y * yAxis.z() + z * zAxis.z());
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
}
