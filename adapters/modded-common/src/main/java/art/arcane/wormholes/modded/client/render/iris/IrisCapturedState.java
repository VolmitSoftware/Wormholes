package art.arcane.wormholes.modded.client.render.iris;

import net.irisshaders.iris.uniforms.CapturedRenderingState;
import org.joml.Matrix4f;
import org.joml.Vector3d;

record IrisCapturedState(Matrix4f view, Matrix4f projection, Vector3d fog, float fogDensity, float tickDelta, float realTickDelta,
                         float darkness, float alphaTest, float cloudTime, int entity, int blockEntity, int item) {
    static IrisCapturedState capture() {
        CapturedRenderingState state = CapturedRenderingState.INSTANCE;
        return new IrisCapturedState(new Matrix4f(state.getGbufferModelView()), new Matrix4f(state.getGbufferProjection()),
            new Vector3d(state.getFogColor()), state.getFogDensity(), state.getTickDelta(), state.getRealTickDelta(),
            state.getDarknessLightFactor(), state.getCurrentAlphaTest(), state.getCloudTime(), state.getCurrentRenderedEntity(),
            state.getCurrentRenderedBlockEntity(), state.getCurrentRenderedItem());
    }

    void restore() {
        CapturedRenderingState state = CapturedRenderingState.INSTANCE;
        state.setGbufferModelView(view);
        state.setGbufferProjection(projection);
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
