package art.arcane.wormholes.modded.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.seibel.distanthorizons.api.objects.math.DhApiMat4f;
import com.seibel.distanthorizons.core.util.math.DhMat4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.render.RenderParams", remap = false)
public abstract class DhPortalRenderValidationMixin {
    @WrapOperation(method = "getValidationErrorMessage", at = @At(value = "INVOKE",
        target = "Lcom/seibel/distanthorizons/api/objects/math/DhApiMat4f;equals(Ljava/lang/Object;)Z"))
    private boolean wormholes$validateViewMatrix(DhApiMat4f matrix, Object other, Operation<Boolean> original) {
        return other != DhMat4f.IDENTITY && original.call(matrix, other);
    }
}
