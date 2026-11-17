package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.sodium.SodiumClipShaders;
import art.arcane.wormholes.modded.client.render.sodium.SodiumLayers;
import me.jellysquid.mods.sodium.client.gl.shader.uniform.GlUniformFloat4v;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ChunkShaderInterface;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ChunkShaderOptions;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ShaderBindingContext;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(value = ChunkShaderInterface.class, remap = false)
public abstract class SodiumPortalShaderMixin {
    @Unique private final Matrix4f wormholes$projection = new Matrix4f();
    @Unique private GlUniformFloat4v wormholes$clipPlane;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void wormholes$bindClipPlane(ShaderBindingContext context, ChunkShaderOptions options, CallbackInfo callback) {
        wormholes$clipPlane = SodiumClipShaders.bind(context);
    }

    @Inject(method = "setProjectionMatrix", at = @At("TAIL"))
    private void wormholes$keepProjection(Matrix4fc matrix, CallbackInfo callback) {
        wormholes$projection.set(matrix);
    }

    // Sodium sets the projection before the model-view on every pass, so the plane is uploaded here.
    @Inject(method = "setModelViewMatrix", at = @At("TAIL"))
    private void wormholes$uploadClipPlane(Matrix4fc matrix, CallbackInfo callback) {
        if (wormholes$clipPlane != null) {
            wormholes$clipPlane.set(SodiumLayers.clipPlane(wormholes$projection, matrix));
        }
    }
}
