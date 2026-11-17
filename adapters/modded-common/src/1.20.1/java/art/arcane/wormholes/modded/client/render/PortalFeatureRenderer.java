package art.arcane.wormholes.modded.client.render;

import art.arcane.optics.frame.OpticTransform;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11C;

/**
 * Draws a streamed scene's entities and block entities into the view being rendered. Positions arrive in the
 * destination's coordinates and are carried into the view's source space by the environment transform; the view's
 * rotation sits in the pose so the game's renderers draw as they would in the world.
 */
public final class PortalFeatureRenderer implements AutoCloseable {
    private final MultiBufferSource.BufferSource buffers = MultiBufferSource.immediate(new BufferBuilder(256));
    private final PoseStack pose = new PoseStack();
    private PortalViewCamera camera;
    private Frustum frustum;
    private OpticTransform transform;
    private Matrix4f rotation;

    void render(PortalScene scene, PortalViewCamera camera, Frustum frustum) {
        this.camera = camera;
        this.frustum = frustum;
        transform = scene.environment() == null ? null : scene.environment().transform();
        rotation = transform == null ? null : PortalProjection.rotation(transform);
        boolean reflected = (camera.viewRotation().determinant3x3() < 0.0F) != (transform != null && transform.reflects());
        PoseStack modelView = RenderSystem.getModelViewStack();
        modelView.pushPose();
        modelView.setIdentity();
        RenderSystem.applyModelViewMatrix();
        Lighting.setupLevel(camera.viewRotation());
        if (reflected) {
            GL11C.glFrontFace(GL11C.GL_CW);
        }
        try {
            pose.setIdentity();
            pose.last().pose().set(camera.viewRotation());
            pose.last().normal().set(new Matrix4f(camera.viewRotation()).normal(new Matrix3f()));
            scene.renderFeatures(this);
            buffers.endBatch();
        } finally {
            if (reflected) {
                GL11C.glFrontFace(GL11C.GL_CCW);
            }
            modelView.popPose();
            RenderSystem.applyModelViewMatrix();
            this.camera = null;
        }
    }

    /** The view's culling frustum, in the view's source space. */
    public Frustum frustum() {
        return frustum;
    }

    /** The view camera's eye in the view's source space. */
    public Vec3 eye() {
        return camera.pos();
    }

    /** The map from destination to view source coordinates, or {@code null} when the scene has no environment. */
    public OpticTransform transform() {
        return transform;
    }

    public float partialTick() {
        return camera.partialTicks();
    }

    /** Draws {@code entity} at its interpolated destination position. */
    public void entity(Entity entity, float partialTick) {
        double x = Mth.lerp(partialTick, entity.xOld, entity.getX());
        double y = Mth.lerp(partialTick, entity.yOld, entity.getY());
        double z = Mth.lerp(partialTick, entity.zOld, entity.getZ());
        float yaw = Mth.lerp(partialTick, entity.yRotO, entity.getYRot());
        pose.pushPose();
        try {
            position(x, y, z);
            Minecraft.getInstance().getEntityRenderDispatcher().render(entity, 0.0D, 0.0D, 0.0D, yaw, partialTick, pose, buffers,
                Minecraft.getInstance().getEntityRenderDispatcher().getPackedLightCoords(entity, partialTick));
        } finally {
            pose.popPose();
        }
    }

    /** Draws {@code blockEntity} at its destination cell. */
    public void blockEntity(BlockEntity blockEntity, float partialTick) {
        pose.pushPose();
        try {
            position(blockEntity.getBlockPos().getX(), blockEntity.getBlockPos().getY(), blockEntity.getBlockPos().getZ());
            Minecraft.getInstance().getBlockEntityRenderDispatcher().render(blockEntity, partialTick, pose, buffers);
        } finally {
            pose.popPose();
        }
    }

    private void position(double x, double y, double z) {
        Vec3 eye = camera.pos();
        if (transform == null) {
            pose.translate(x - eye.x, y - eye.y, z - eye.z);
            return;
        }
        pose.translate(x * transform.permutation().x().x() + y * transform.permutation().y().x() + z * transform.permutation().z().x()
                + transform.translationX() - eye.x,
            x * transform.permutation().x().y() + y * transform.permutation().y().y() + z * transform.permutation().z().y()
                + transform.translationY() - eye.y,
            x * transform.permutation().x().z() + y * transform.permutation().y().z() + z * transform.permutation().z().z()
                + transform.translationZ() - eye.z);
        pose.last().pose().mul(rotation);
        pose.last().normal().mul(new Matrix3f().set(rotation));
    }

    @Override
    public void close() {
    }
}
