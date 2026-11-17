package art.arcane.wormholes.modded.client.render;

import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.math.Face;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.core.BlockPos;

import java.nio.ByteBuffer;

/** Places a destination block's vertices at its source-space cell, rotating them by the portal's axis permutation. */
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

    void inputOrigin(int x, int y, int z) {
        inputX = x + 0.5F;
        inputY = y + 0.5F;
        inputZ = z + 0.5F;
    }

    PortalVertexTransform target(VertexConsumer target) {
        this.target = target;
        return this;
    }

    /** Restores counter-clockwise quads after a reflecting permutation reversed them. */
    void winding(ByteBuffer vertices, int vertexCount, int stride) {
        if (!reflected) {
            return;
        }
        for (int quad = 0; quad + 4 <= vertexCount; quad += 4) {
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
    public VertexConsumer vertex(double x, double y, double z) {
        float dx = (float) x - inputX;
        float dy = (float) y - inputY;
        float dz = (float) z - inputZ;
        target.vertex(this.x + dx * xAxis.x() + dy * yAxis.x() + dz * zAxis.x(),
            this.y + dx * xAxis.y() + dy * yAxis.y() + dz * zAxis.y(),
            this.z + dx * xAxis.z() + dy * yAxis.z() + dz * zAxis.z());
        return this;
    }

    @Override
    public VertexConsumer normal(float x, float y, float z) {
        target.normal(x * xAxis.x() + y * yAxis.x() + z * zAxis.x(),
            x * xAxis.y() + y * yAxis.y() + z * zAxis.y(), x * xAxis.z() + y * yAxis.z() + z * zAxis.z());
        return this;
    }

    @Override
    public VertexConsumer color(int red, int green, int blue, int alpha) {
        target.color(red, green, blue, alpha);
        return this;
    }

    @Override
    public VertexConsumer uv(float u, float v) {
        target.uv(u, v);
        return this;
    }

    @Override
    public VertexConsumer overlayCoords(int u, int v) {
        target.overlayCoords(u, v);
        return this;
    }

    @Override
    public VertexConsumer uv2(int u, int v) {
        target.uv2(u, v);
        return this;
    }

    @Override
    public void endVertex() {
        target.endVertex();
    }

    @Override
    public void defaultColor(int red, int green, int blue, int alpha) {
        target.defaultColor(red, green, blue, alpha);
    }

    @Override
    public void unsetDefaultColor() {
        target.unsetDefaultColor();
    }
}
