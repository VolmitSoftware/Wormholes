package art.arcane.wormholes.clientgametest.mixin;

import art.arcane.wormholes.clientgametest.DistantHorizonsTap;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.seibel.distanthorizons.common.render.blaze.wrappers.RenderPassWrapper;
import com.seibel.distanthorizons.common.render.blaze.wrappers.texture.IDhBlazeTexture;
import com.llamalad7.mixinextras.sugar.Local;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.common.render.blaze.apply.BlazeDhCopyRenderer", remap = false)
public abstract class DistantHorizonsBlazeCopyTap {
    @WrapOperation(method = "render", at = @At(value = "INVOKE",
        target = "Lcom/seibel/distanthorizons/common/render/blaze/wrappers/RenderPassWrapper;draw(I)V"))
    private void wormholesTest$copy(RenderPassWrapper pass, int count, Operation<Void> original,
                                    @Local(argsOnly = true, ordinal = 0) IDhBlazeTexture input,
                                    @Local(argsOnly = true, ordinal = 1) IDhBlazeTexture destination) {
        int query = DistantHorizonsTap.beginCopy(input.getTextureView(), destination.getTextureView(), count);
        boolean completed = false;
        try {
            original.call(pass, count);
            completed = true;
        } finally {
            DistantHorizonsTap.endCopy(query, completed);
        }
    }
}
