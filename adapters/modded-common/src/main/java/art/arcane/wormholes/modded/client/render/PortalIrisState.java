package art.arcane.wormholes.modded.client.render;

import art.arcane.wormholes.modded.mixin.client.IrisPortalHardcodedAccess;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3d;

record PortalIrisState(Matrix4fc modelView, Matrix4fc projection, Vector3d fogColor, float fogDensity,
                      float tickDelta, float realTickDelta, float alphaTest, float darkness, float cloudTime,
                      int blockEntity, int entity, int item, Holder<Biome> biome) {
    static PortalIrisState capture() {
        CapturedRenderingState state = CapturedRenderingState.INSTANCE;
        return new PortalIrisState(copy(state.getGbufferModelView()), copy(state.getGbufferProjection()),
            new Vector3d(state.getFogColor()), state.getFogDensity(), state.getTickDelta(), state.getRealTickDelta(),
            state.getCurrentAlphaTest(), state.getDarknessLightFactor(), state.getCloudTime(), state.getCurrentRenderedBlockEntity(),
            state.getCurrentRenderedEntity(), state.getCurrentRenderedItem(), IrisPortalHardcodedAccess.wormholes$biome());
    }

    void apply() {
        CapturedRenderingState state = CapturedRenderingState.INSTANCE;
        state.setGbufferModelView(modelView);
        state.setGbufferProjection(new Matrix4f(projection));
        state.setFogColor((float) fogColor.x, (float) fogColor.y, (float) fogColor.z);
        state.setFogDensity(fogDensity);
        state.setTickDelta(tickDelta);
        state.setRealTickDelta(realTickDelta);
        state.setCurrentAlphaTest(alphaTest);
        state.setDarknessLightFactor(darkness);
        state.setCloudTime(cloudTime);
        state.setCurrentBlockEntity(blockEntity);
        state.setCurrentEntity(entity);
        state.setCurrentRenderedItem(item);
        IrisPortalHardcodedAccess.wormholes$biome(biome);
    }

    private static Matrix4fc copy(Matrix4fc value) {
        return value == null ? new Matrix4f() : new Matrix4f(value);
    }
}
