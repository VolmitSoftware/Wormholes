package art.arcane.wormholes.modded.client.render;

import art.arcane.optics.stream.ProjectionEnvironment;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.function.Predicate;

public final class PortalFeatureRenderer implements AutoCloseable {
    private static final ThreadLocal<StagedVertexBuffer> REFLECTED_BUFFER = new ThreadLocal<>();

    private final SubmitNodeStorage submits = new SubmitNodeStorage();
    private RenderBuffers buffers;
    private FeatureRenderDispatcher dispatcher;
    private FeatureRenderDispatcher.PreparedFrame frame;

    public void prepare(PortalScene scene, CameraRenderState camera, Frustum frustum) {
        prepare(scene, camera, true, true, frustum);
    }

    public void prepare(PortalScene scene, CameraRenderState camera, boolean entities, boolean blockEntities) {
        prepare(scene, camera, entities, blockEntities, camera.cullFrustum);
    }

    private void prepare(PortalScene scene, CameraRenderState camera, boolean entities, boolean blockEntities, Frustum frustum) {
        closeFrame();
        if (scene.entities().isEmpty() && scene.blockEntities().isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (dispatcher == null) {
            buffers = new RenderBuffers(0);
            dispatcher = new FeatureRenderDispatcher(buffers, minecraft.getModelManager(), minecraft.getAtlasManager(), minecraft.font,
                minecraft.gameRenderer.gameRenderState());
        }
        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        modelView.set(camera.viewRotationMatrix);
        try {
            submit(scene, camera, minecraft, entities, blockEntities, frustum);
            try (WindingScope scope = new WindingScope(buffers.stagedVertexBuffer(), scene.environment() == null ? null : scene.environment().transform())) {
                frame = dispatcher.prepareFrame(submits);
            }
        } finally {
            modelView.popMatrix();
        }
    }

    public static void correctWinding(StagedVertexBuffer buffer, MeshData mesh) {
        if (mesh == null || REFLECTED_BUFFER.get() != buffer) {
            return;
        }
        PrimitiveTopology topology = mesh.drawState().primitiveTopology();
        int count = mesh.drawState().vertexCount();
        int stride = mesh.drawState().format().getVertexSize();
        ByteBuffer vertices = mesh.vertexBuffer();
        if (topology == PrimitiveTopology.QUADS || topology == PrimitiveTopology.TRIANGLES) {
            int primitive = topology == PrimitiveTopology.QUADS ? 4 : 3;
            for (int vertex = 0; vertex + primitive <= count; vertex += primitive) {
                swapVertices(vertices, (vertex + 1) * stride, (vertex + primitive - 1) * stride, stride);
            }
        } else if (topology == PrimitiveTopology.TRIANGLE_FAN) {
            for (int first = 1, last = count - 1; first < last; first++, last--) {
                swapVertices(vertices, first * stride, last * stride, stride);
            }
        }
    }

    static void blockPose(PoseStack pose, BlockPos position, Vec3 eye, ProjectionEnvironment.Transform transform, Matrix4f rotation) {
        positionPose(pose, position.getX(), position.getY(), position.getZ(), eye, transform, rotation);
    }

    static void entityPose(PoseStack pose, EntityRenderState state, Vec3 eye, ProjectionEnvironment.Transform transform, Matrix4f rotation) {
        positionPose(pose, state.x, state.y, state.z, eye, transform, rotation);
    }

    public void executeSolid(RenderPass pass) {
        if (frame != null) {
            frame.executeSolid(pass);
        }
    }

    public void executeTranslucent(RenderPass pass) {
        if (frame != null) {
            frame.executeTranslucent(pass);
            frame.executeTranslucentAfterTerrain(pass);
            frame.executeSeeThrough(pass);
            frame.executeAlwaysOnTop(pass);
        }
    }

    public void closeFrame() {
        if (frame != null) {
            frame.close();
            frame = null;
        }
    }

    public void endFrame() {
        closeFrame();
        if (buffers != null) {
            buffers.endFrame();
        }
    }

    @Override
    public void close() {
        closeFrame();
        if (dispatcher != null) {
            dispatcher.close();
            dispatcher = null;
        }
        if (buffers != null) {
            buffers.fixedBufferPack().close();
            buffers.close();
            buffers = null;
        }
    }

    private static void swapVertices(ByteBuffer vertices, int first, int second, int stride) {
        for (int offset = 0; offset < stride; offset++) {
            byte value = vertices.get(first + offset);
            vertices.put(first + offset, vertices.get(second + offset));
            vertices.put(second + offset, value);
        }
    }

    private void submit(PortalScene scene, CameraRenderState camera, Minecraft minecraft, boolean includeEntities, boolean includeBlockEntities, Frustum frustum) {
        PoseStack pose = new PoseStack();
        Vec3 eye = camera.pos;
        ProjectionEnvironment environment = scene.environment();
        ProjectionEnvironment.Transform transform = environment == null ? null : environment.transform();
        Matrix4f rotation = transform == null ? null : PortalProjection.rotation(transform);
        CameraRenderState featureCamera = environment == null ? camera : ClientPortalRenderer.transformedCamera(camera,
            PortalProjection.destinationToSource(environment.transform()), camera.projectionMatrix);
        EntityRenderDispatcher entities = minecraft.getEntityRenderDispatcher();
        Predicate<EntityRenderState> visible = scene.entityVisibility(camera, frustum);
        for (EntityRenderState state : includeEntities ? scene.entities() : List.<EntityRenderState>of()) {
            if (!visible.test(state)) {
                continue;
            }
            if (environment == null) {
                entities.submit(state, camera, state.x - eye.x, state.y - eye.y, state.z - eye.z, pose, submits);
            } else {
                pose.pushPose();
                entityPose(pose, state, eye, transform, rotation);
                entities.submit(state, featureCamera, 0, 0, 0, pose, submits);
                pose.popPose();
            }
        }
        BlockEntityRenderDispatcher blocks = minecraft.getBlockEntityRenderDispatcher();
        for (BlockEntityRenderState state : includeBlockEntities ? scene.blockEntities() : List.<BlockEntityRenderState>of()) {
            BlockPos position = state.blockPos;
            pose.pushPose();
            if (transform != null) {
                blockPose(pose, position, eye, transform, rotation);
            } else {
                pose.translate(position.getX() - eye.x, position.getY() - eye.y, position.getZ() - eye.z);
            }
            blocks.submit(state, pose, submits, featureCamera);
            pose.popPose();
        }
    }

    private static void positionPose(PoseStack pose, double x, double y, double z, Vec3 eye, ProjectionEnvironment.Transform transform, Matrix4f rotation) {
        pose.translate(x * transform.xAxis().x() + y * transform.yAxis().x() + z * transform.zAxis().x() + transform.translation().x() - eye.x,
            x * transform.xAxis().y() + y * transform.yAxis().y() + z * transform.zAxis().y() + transform.translation().y() - eye.y,
            x * transform.xAxis().z() + y * transform.yAxis().z() + z * transform.zAxis().z() + transform.translation().z() - eye.z);
        pose.mulPose(rotation);
    }

    static final class WindingScope implements AutoCloseable {
        private final StagedVertexBuffer previous;

        WindingScope(StagedVertexBuffer buffer, ProjectionEnvironment.Transform transform) {
            previous = REFLECTED_BUFFER.get();
            if (transform != null && transform.reflected()) {
                REFLECTED_BUFFER.set(buffer);
            } else {
                REFLECTED_BUFFER.remove();
            }
        }

        @Override
        public void close() {
            if (previous == null) {
                REFLECTED_BUFFER.remove();
            } else {
                REFLECTED_BUFFER.set(previous);
            }
        }
    }
}
