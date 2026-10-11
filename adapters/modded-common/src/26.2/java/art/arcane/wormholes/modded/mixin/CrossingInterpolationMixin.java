package art.arcane.wormholes.modded.mixin;

import art.arcane.wormholes.modded.seamless.CrossingInterpolation;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

@Mixin(InterpolationHandler.class)
public abstract class CrossingInterpolationMixin implements CrossingInterpolation {
    @Shadow private Vec3 previousTickPosition;
    @Unique private CrossingInterpolationDataAccess wormholes$data;

    @Override
    public void wormholesTranslate(Vec3 delta) {
        wormholes$data.wormholesAddDelta(delta);
        if (previousTickPosition != null) {
            previousTickPosition = previousTickPosition.add(delta);
        }
    }

    @Coerce
    @ModifyExpressionValue(method = "<init>(Lnet/minecraft/world/entity/Entity;ILjava/util/function/Consumer;)V",
        at = @At(value = "NEW", target = "(ILnet/minecraft/world/phys/Vec3;FF)Lnet/minecraft/world/entity/InterpolationHandler$InterpolationData;"))
    private Object wormholesCaptureData(@Coerce Object data) {
        wormholes$data = (CrossingInterpolationDataAccess) data;
        return data;
    }
}
