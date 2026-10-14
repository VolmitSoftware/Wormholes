package art.arcane.wormholes.modded.client.render.iris;

import art.arcane.wormholes.modded.mixin.client.IrisPortalCapturedStateAccess;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3d;

record IrisCapturedState(Matrix4f view, Matrix4f projection, Vector3d fog, float fogDensity, float tickDelta, float realTickDelta,
                         float darkness, float alphaTest, float cloudTime, int entity, int blockEntity, int item) {
    static IrisCapturedState capture() {
        return capture(CapturedRenderingState.INSTANCE);
    }

    static IrisCapturedState capture(CapturedRenderingState state) {
        Matrix4fc view = state.getGbufferModelView();
        Matrix4fc projection = state.getGbufferProjection();
        Vector3d fog = ((IrisPortalCapturedStateAccess) state).wormholes$fog();
        return new IrisCapturedState(view == null ? null : new Matrix4f(view), projection == null ? null : new Matrix4f(projection),
            fog == null ? null : new Vector3d(fog), state.getFogDensity(), state.getTickDelta(), state.getRealTickDelta(),
            state.getDarknessLightFactor(), state.getCurrentAlphaTest(), state.getCloudTime(), state.getCurrentRenderedEntity(),
            state.getCurrentRenderedBlockEntity(), state.getCurrentRenderedItem());
    }

    void restore() {
        restore(CapturedRenderingState.INSTANCE);
    }

    void restore(CapturedRenderingState state) {
        state.setGbufferModelView(view);
        if (projection == null) {
            ((IrisPortalCapturedStateAccess) state).wormholes$projection(null);
        } else {
            state.setGbufferProjection(projection);
        }
        ((IrisPortalCapturedStateAccess) state).wormholes$fog(fog);
        state.setFogDensity(fogDensity);
        state.setTickDelta(tickDelta);
        state.setRealTickDelta(realTickDelta);
        state.setDarknessLightFactor(darkness);
        state.setCurrentAlphaTest(alphaTest);
        state.setCloudTime(cloudTime);
        state.setCurrentEntity(entity);
        state.setCurrentBlockEntity(blockEntity);
        state.setCurrentRenderedItem(item);
    }
}
