package art.arcane.wormholes.modded.client.render;

import art.arcane.wormholes.network.client.ClientViewEnvironment;
import art.arcane.wormholes.util.Direction;
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
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Matrix4fStack;

import java.nio.ByteBuffer;
import java.util.List;

public final class PortalFeatureRenderer implements AutoCloseable {
    private static final ThreadLocal<StagedVertexBuffer> REFLECTED_BUFFER = new ThreadLocal<>();

    private final SubmitNodeStorage submits = new SubmitNodeStorage();
    private RenderBuffers buffers;
    private FeatureRenderDispatcher dispatcher;
    private FeatureRenderDispatcher.PreparedFrame frame;

    public void prepare(PortalScene scene, CameraRenderState camera) {
        prepare(scene, camera, true, true);
    }

    public void prepare(PortalScene scene, CameraRenderState camera, boolean entities, boolean blockEntities) {
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
            submit(scene, camera, minecraft, entities, blockEntities);
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

    static void blockPose(PoseStack pose, BlockPos position, Vec3 eye, ClientViewEnvironment.Transform transform) {
        positionPose(pose, position.getX(), position.getY(), position.getZ(), eye, transform);
    }

    static void entityPose(PoseStack pose, EntityRenderState state, Vec3 eye, ClientViewEnvironment.Transform transform) {
        positionPose(pose, state.x, state.y, state.z, eye, transform);
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

    public void resourceReload() {
        close();
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

    private void submit(PortalScene scene, CameraRenderState camera, Minecraft minecraft, boolean includeEntities, boolean includeBlockEntities) {
        PoseStack pose = new PoseStack();
        Vec3 eye = camera.pos;
        ClientViewEnvironment environment = scene.environment();
        CameraRenderState featureCamera = environment == null ? camera : ClientPortalRenderer.transformedCamera(camera,
            new Matrix4d(PortalEnvironment.rotation(environment.transform())).setTranslation(environment.transform().translation().x(),
                environment.transform().translation().y(), environment.transform().translation().z()), camera.projectionMatrix);
        EntityRenderDispatcher entities = minecraft.getEntityRenderDispatcher();
        for (EntityRenderState state : includeEntities ? scene.entities() : List.<EntityRenderState>of()) {
            if (environment == null) {
                entities.submit(state, camera, state.x - eye.x, state.y - eye.y, state.z - eye.z, pose, submits);
            } else {
                pose.pushPose();
                entityPose(pose, state, eye, environment.transform());
                entities.submit(state, featureCamera, 0, 0, 0, pose, submits);
                pose.popPose();
            }
        }
        BlockEntityRenderDispatcher blocks = minecraft.getBlockEntityRenderDispatcher();
        for (BlockEntityRenderState state : includeBlockEntities ? scene.blockEntities() : List.<BlockEntityRenderState>of()) {
            BlockPos position = state.blockPos;
            pose.pushPose();
            if (scene.environment() != null) {
                blockPose(pose, position, eye, scene.environment().transform());
            } else {
                pose.translate(position.getX() - eye.x, position.getY() - eye.y, position.getZ() - eye.z);
            }
            blocks.submit(state, pose, submits, featureCamera);
            pose.popPose();
        }
    }

    private static void positionPose(PoseStack pose, double x, double y, double z, Vec3 eye, ClientViewEnvironment.Transform transform) {
        pose.translate(x * transform.xAxis().x() + y * transform.yAxis().x() + z * transform.zAxis().x() + transform.translation().x() - eye.x,
            x * transform.xAxis().y() + y * transform.yAxis().y() + z * transform.zAxis().y() + transform.translation().y() - eye.y,
            x * transform.xAxis().z() + y * transform.yAxis().z() + z * transform.zAxis().z() + transform.translation().z() - eye.z);
        pose.mulPose(PortalEnvironment.rotation(transform));
    }

    static final class WindingScope implements AutoCloseable {
        private final StagedVertexBuffer previous;

        WindingScope(StagedVertexBuffer buffer, ClientViewEnvironment.Transform transform) {
            previous = REFLECTED_BUFFER.get();
            if (transform != null && reflected(transform)) {
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

        private static boolean reflected(ClientViewEnvironment.Transform transform) {
            Direction x = transform.xAxis();
            Direction y = transform.yAxis();
            Direction z = transform.zAxis();
            return x.x() * (y.y() * z.z() - y.z() * z.y())
                - y.x() * (x.y() * z.z() - x.z() * z.y())
                + z.x() * (x.y() * y.z() - x.z() * y.y()) < 0;
        }
    }

}
