package art.arcane.wormholes.modded.client.render;

import art.arcane.wormholes.modded.mixin.client.IrisPortalShadowRendererAccess;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.compat.dh.DHCompat;
import net.minecraft.util.Mth;
import net.irisshaders.iris.shaderpack.properties.PackShadowDirectives;
import net.irisshaders.iris.shadows.ShadowMatrices;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.irisshaders.iris.shadows.ShadowRenderTargets;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.joml.Matrix4f;

import java.util.List;

final class PortalIrisShadowFrame implements PortalShaderRenderer.ShadowFrame {
    private final Globals globals = Globals.capture();
    private final PortalFramebufferScope framebuffer = PortalFramebufferScope.capture();
    private final GpuBufferSlice projection = RenderSystem.getProjectionMatrixBuffer();
    private final ProjectionType projectionType = RenderSystem.getProjectionType();
    private final ShadowRenderTargets targets;
    private final ShadowRenderer renderer;
    private final PackShadowDirectives directives;
    private final CameraRenderState camera;

    PortalIrisShadowFrame(Request request) {
        targets = request.targets();
        renderer = request.renderer();
        directives = request.directives();
        Planes planes = planes(directives, DHCompat.getRenderDistance());
        float near = planes.near();
        float far = planes.far();
        PoseStack modelView = ShadowRenderer.createShadowModelView(request.pipeline().getSunPathRotation(),
            directives.getIntervalSize(), near, far);
        Matrix4f shadowView = new Matrix4f(modelView.last().pose());
        Matrix4f shadowProjection = directives.getFov() == null ? ShadowMatrices.createOrthoMatrix(directives.getDistance(), near, far)
            : ShadowMatrices.createPerspectiveMatrix(directives.getFov());
        camera = copy(request.display(), shadowProjection);
        camera.viewRotationMatrix = new Matrix4f(shadowView)
            .mul(PortalEnvironment.rotation(PortalShaderContext.current().environment().transform()).invert());
        camera.cullFrustum = new Frustum(camera.viewRotationMatrix, shadowProjection);
        camera.cullFrustum.prepare(camera.pos.x, camera.pos.y, camera.pos.z);
        boolean pushed = false;
        try {
            ShadowRenderer.ACTIVE = true;
            ShadowRenderer.RESOLUTION = directives.getResolution();
            ShadowRenderer.MODELVIEW = shadowView;
            ShadowRenderer.PROJECTION = shadowProjection;
            ShadowRenderer.FRUSTUM = new Frustum(shadowView, shadowProjection);
            ShadowRenderer.visibleBlockEntities = List.of();
            GlStateManager._viewport(0, 0, directives.getResolution(), directives.getResolution());
            RenderSystem.getModelViewStack().pushMatrix().set(camera.viewRotationMatrix);
            pushed = true;
            RenderSystem.setProjectionMatrix(request.projection().getBuffer(shadowProjection), ProjectionType.ORTHOGRAPHIC);
        } catch (RuntimeException | Error failure) {
            try {
                if (pushed) {
                    RenderSystem.getModelViewStack().popMatrix();
                }
                RenderSystem.setProjectionMatrix(projection, projectionType);
            } finally {
                try {
                    globals.apply();
                } finally {
                    if (framebuffer != null) {
                        framebuffer.close();
                    }
                }
            }
            throw failure;
        }
    }

    static Planes planes(PackShadowDirectives directives, int renderDistance) {
        return new Planes(Mth.equal(directives.getNearPlane(), -1F) ? -renderDistance * 16F : directives.getNearPlane(),
            Mth.equal(directives.getFarPlane(), -1F) ? renderDistance * 16F : directives.getFarPlane());
    }

    private static CameraRenderState copy(CameraRenderState display, Matrix4f projection) {
        CameraRenderState result = new CameraRenderState();
        result.pos = display.pos;
        result.blockPos = display.blockPos;
        result.orientation = display.orientation;
        result.projectionMatrix = projection;
        result.cameraEntityPartialTicks = display.cameraEntityPartialTicks;
        result.fogData = display.fogData;
        result.fogType = display.fogType;
        result.entityRenderState = display.entityRenderState;
        result.depthFar = display.depthFar;
        result.initialized = true;
        return result;
    }

    @Override
    public CameraRenderState camera() {
        return camera;
    }

    @Override
    public boolean terrain() {
        return directives.shouldRenderTerrain();
    }

    @Override
    public boolean translucent() {
        return directives.shouldRenderTranslucent();
    }

    @Override
    public boolean entities() {
        return directives.shouldRenderEntities();
    }

    @Override
    public boolean blockEntities() {
        return directives.shouldRenderBlockEntities();
    }

    @Override
    public void translucentDepth() {
        targets.copyPreTranslucentDepth();
    }

    @Override
    public void close() {
        try {
            ((IrisPortalShadowRendererAccess) renderer).wormholes$generateMipmaps();
            ((IrisPortalShadowRendererAccess) renderer).wormholes$composite().renderAll();
        } finally {
            try {
                RenderSystem.setProjectionMatrix(projection, projectionType);
                RenderSystem.getModelViewStack().popMatrix();
            } finally {
                try {
                    globals.apply();
                } finally {
                    if (framebuffer != null) {
                        framebuffer.close();
                    }
                }
            }
        }
    }

    record Planes(float near, float far) {
    }

    record Request(IrisRenderingPipeline pipeline, ShadowRenderer renderer, ShadowRenderTargets targets,
                   PackShadowDirectives directives, ProjectionMatrixBuffer projection, CameraRenderState display) {
    }

    private record Globals(boolean active, int resolution, Matrix4f modelView, Matrix4f projection,
                           Frustum frustum, List<BlockEntity> blockEntities) {
        private static Globals capture() {
            return new Globals(ShadowRenderer.ACTIVE, ShadowRenderer.RESOLUTION, ShadowRenderer.MODELVIEW,
                ShadowRenderer.PROJECTION, ShadowRenderer.FRUSTUM, ShadowRenderer.visibleBlockEntities);
        }

        private void apply() {
            ShadowRenderer.ACTIVE = active;
            ShadowRenderer.RESOLUTION = resolution;
            ShadowRenderer.MODELVIEW = modelView;
            ShadowRenderer.PROJECTION = projection;
            ShadowRenderer.FRUSTUM = frustum;
            ShadowRenderer.visibleBlockEntities = blockEntities;
        }
    }
}
