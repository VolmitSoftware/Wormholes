package art.arcane.wormholes.modded.client.render.iris;

import net.irisshaders.iris.uniforms.CapturedRenderingState;
import org.joml.Matrix4f;
import org.joml.Vector3d;

/** Iris's per-frame captured matrices and fog, which a layer's {@code renderLevel} overwrites with its own. */
record IrisCapturedState(Matrix4f view, Matrix4f projection, Vector3d fog, float fogDensity, float tickDelta, float realTickDelta,
                         float darkness, float alphaTest, float cloudTime, int entity, int blockEntity, int item) {
    static IrisCapturedState capture() {
        CapturedRenderingState state = CapturedRenderingState.INSTANCE;
        Matrix4f view = state.getGbufferModelView();
        Matrix4f projection = state.getGbufferProjection();
        return new IrisCapturedState(view == null ? null : new Matrix4f(view), projection == null ? null : new Matrix4f(projection),
            new Vector3d(state.getFogColor()), state.getFogDensity(), state.getTickDelta(), state.getRealTickDelta(),
            state.getDarknessLightFactor(), state.getCurrentAlphaTest(), state.getCloudTime(), state.getCurrentRenderedEntity(),
            state.getCurrentRenderedBlockEntity(), state.getCurrentRenderedItem());
    }

    void restore() {
        CapturedRenderingState state = CapturedRenderingState.INSTANCE;
        if (view != null) {
            state.setGbufferModelView(view);
        }
        if (projection != null) {
            state.setGbufferProjection(projection);
        }
        state.setFogColor((float) fog.x, (float) fog.y, (float) fog.z);
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
