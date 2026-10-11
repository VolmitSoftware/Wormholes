package art.arcane.wormholes.modded.client.render.dh;

import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.client.render.stencil.PortalStencil;
import art.arcane.wormholes.modded.client.render.stencil.PortalStencilRenderer;
import art.arcane.wormholes.modded.client.render.stencil.PortalView;
import art.arcane.wormholes.modded.mixin.client.DhPortalClientApiAccess;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.seibel.distanthorizons.common.wrappers.world.ClientLevelWrapper;
import com.seibel.distanthorizons.core.api.internal.ClientApi;
import com.seibel.distanthorizons.core.api.internal.rendering.DhRenderState;
import com.seibel.distanthorizons.core.render.RenderParams;
import com.seibel.distanthorizons.core.util.math.DhMat4f;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.joml.Matrix4fc;
import org.joml.Vector4dc;

public final class DhPortalRenderContext implements DhPortalRendering.Scope {
    private static DhPortalRenderContext active;

    private final DhPortalRenderContext parent = active;
    private final DhRenderSnapshot snapshot = new DhRenderSnapshot();
    private IClientLevelWrapper portalLevel;
    private DhMat4f portalModelView;
    private DhMat4f portalProjection;
    private DhPortalLodViews.PortalPath path;
    private Vec3d camera;
    private DhPortalLodViews.Selection selection;
    private DhClipPrograms.Scope clipping;
    private boolean closed;

    private DhPortalRenderContext() {
    }

    static DhPortalRenderContext open(PortalView view, ClientLevel destination, Matrix4fc modelView, Matrix4fc projection, float partialTicks,
                                     Vector4dc worldPlane, Vec3d camera) {
        DhPortalRenderContext scope = new DhPortalRenderContext();
        try {
            scope.path = DhPortalLodViews.path(scope.parent == null ? null : scope.parent.path, view);
            scope.camera = camera;
            scope.clipping = DhClipPrograms.open(worldPlane, camera);
            scope.portalLevel = ClientLevelWrapper.getWrapper(destination);
            DhRenderState state = ClientApi.RENDER_STATE;
            state.clientLevelWrapper = scope.portalLevel;
            scope.portalModelView = new DhMat4f(modelView);
            scope.portalProjection = new DhMat4f(projection);
            state.mcModelViewMatrix = scope.portalModelView;
            state.mcProjectionMatrix = scope.portalProjection;
            state.partialTickTime = partialTicks;
            active = scope;
            scope.snapshot.enterPortal();
            return scope;
        } catch (RuntimeException | Error failure) {
            try {
                scope.close();
            } catch (RuntimeException | Error restoreFailure) {
                failure.addSuppressed(restoreFailure);
            }
            throw failure;
        }
    }

    static void prepareLods() {
        if (active != null) {
            ClientApi.RENDER_STATE.clientLevelWrapper = active.portalLevel;
            ClientApi.RENDER_STATE.mcModelViewMatrix = active.portalModelView;
            ClientApi.RENDER_STATE.mcProjectionMatrix = active.portalProjection;
        } else {
            DhMainRenderContext.prepareLods();
        }
    }

    static void prepareRenderParams() {
        if (active == null) {
            DhMainRenderContext.prepareRenderParams();
            return;
        }
        RenderParams target = DhPortalClientApiAccess.wormholes$renderParams();
        if (target.dhClientWorld != null && target.dhClientLevel != null) {
            if (active.selection == null) {
                active.selection = DhPortalLodViews.acquire(active.path, target, active.camera);
            }
            target.renderBufferHandler = active.selection.buffers();
        }
    }

    public static GpuTextureView copyDepth(GpuTextureView color, GpuTextureView depth) {
        PortalStencilRenderer renderer = PortalStencilRenderer.instance();
        if (!renderer.nested() || renderer.deferredActive()) {
            return depth;
        }
        RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        return color.texture() == main.getColorTexture() && PortalStencil.available(main) ? main.getDepthTextureView() : depth;
    }

    static DhPortalLodViews.Path temporalPath(RenderParams parameters) {
        return active == null ? DhPortalLodViews.mainPath((ClientLevel) parameters.clientLevelWrapper.getWrappedMcObject()) : active.path;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (active == this) {
            active = parent;
        }
        try {
            snapshot.restore();
        } finally {
            try {
                if (selection != null) {
                    selection.release();
                }
            } finally {
                if (clipping != null) {
                    clipping.close();
                }
            }
        }
    }
}
