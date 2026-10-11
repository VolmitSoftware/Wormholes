package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.dh.DhClipPrograms;
import com.llamalad7.mixinextras.sugar.Local;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.common.render.openGl.generic.GlGenericObjectShaderProgram", remap = false)
public abstract class DhPortalGenericClipMixin {
    @Shadow
    public abstract int getId();

    @Unique
    private final Matrix4f wormholes$inverseProjection = new Matrix4f();

    @Unique
    private DhClipPrograms.Binding wormholes$clipping;

    @Inject(method = {"bind(Lcom/seibel/distanthorizons/api/methods/events/sharedParameterObjects/DhApiRenderParam;)V",
        "fillIndirectUniformData", "fillSharedDirectUniformData", "fillDirectUniformData"}, at = @At("RETURN"))
    private void wormholes$clip(CallbackInfo callback, @Local(argsOnly = true) DhApiRenderParam parameters) {
        if (wormholes$clipping == null) {
            wormholes$clipping = DhClipPrograms.bind(getId());
        }
        DhClipPrograms.inverseProjection(parameters, wormholes$inverseProjection);
        DhClipPrograms.apply(wormholes$clipping, wormholes$inverseProjection);
    }

    @Inject(method = "unbind", at = @At("RETURN"))
    private void wormholes$unclip(CallbackInfo callback) {
        DhClipPrograms.release(wormholes$clipping);
    }
}
