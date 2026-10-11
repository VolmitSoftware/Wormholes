package art.arcane.wormholes.modded.client.render.dh;

import art.arcane.wormholes.modded.mixin.client.DhPortalClientApiAccess;
import com.seibel.distanthorizons.core.api.internal.ClientApi;
import com.seibel.distanthorizons.core.api.internal.rendering.DhRenderState;
import com.seibel.distanthorizons.core.render.CameraZoom;
import com.seibel.distanthorizons.core.render.DhApiRenderProxy;
import com.seibel.distanthorizons.core.util.math.DhMat4f;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;

final class DhRenderSnapshot {
    private static final boolean IRIS = DhRenderSnapshot.class.getClassLoader()
        .getResource("net/irisshaders/iris/compat/dh/LodRendererEvents.class") != null;

    private final DhMat4f modelView = ClientApi.RENDER_STATE.mcModelViewMatrix;
    private final DhMat4f projection = ClientApi.RENDER_STATE.mcProjectionMatrix;
    private final IClientLevelWrapper level = ClientApi.RENDER_STATE.clientLevelWrapper;
    private final float partialTicks = ClientApi.RENDER_STATE.partialTickTime;
    private final boolean fog = ClientApi.RENDER_STATE.vanillaFogEnabled;
    private final DhRenderParamsSnapshot parameters = new DhRenderParamsSnapshot(DhPortalClientApiAccess.wormholes$renderParams());
    private final IrisDhRenderSnapshot iris = IRIS ? new IrisDhRenderSnapshot() : null;
    private final boolean deferred = DhApiRenderProxy.INSTANCE.getDeferTransparentRendering();
    private final double magnification = CameraZoom.INSTANCE.magnification;
    private final double coneAngle = CameraZoom.INSTANCE.coneTanHalfAngle;
    private final double lookX = CameraZoom.INSTANCE.lookDirectionX;
    private final double lookZ = CameraZoom.INSTANCE.lookDirectionZ;

    void enterPortal() {
        if (iris != null) {
            iris.enter();
        }
    }

    void restore() {
        try {
            if (iris != null) {
                iris.restore();
            }
        } finally {
            try {
                parameters.restore();
            } finally {
                DhApiRenderProxy.INSTANCE.setDeferTransparentRendering(deferred);
                CameraZoom.INSTANCE.magnification = magnification;
                CameraZoom.INSTANCE.coneTanHalfAngle = coneAngle;
                CameraZoom.INSTANCE.lookDirectionX = lookX;
                CameraZoom.INSTANCE.lookDirectionZ = lookZ;
                DhRenderState state = ClientApi.RENDER_STATE;
                state.mcModelViewMatrix = modelView;
                state.mcProjectionMatrix = projection;
                state.clientLevelWrapper = level;
                state.partialTickTime = partialTicks;
                state.vanillaFogEnabled = fog;
            }
        }
    }
}
