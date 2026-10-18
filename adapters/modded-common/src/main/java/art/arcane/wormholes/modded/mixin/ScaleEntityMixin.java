package art.arcane.wormholes.modded.mixin;

import art.arcane.wormholes.modded.MinecraftScaleAccess;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class ScaleEntityMixin {
    @Unique
    private static final MinecraftScaleAccess wormholes$scale = MinecraftScaleAccess.scaleAttribute();

    @Inject(method = "saveWithoutId", at = @At("TAIL"))
    private void wormholes$saveScale(ValueOutput output, CallbackInfo callback) {
        double factor = wormholes$scale.scale((Entity) (Object) this);
        if (factor != 1.0D) {
            output.putDouble("wormholesScale", factor);
        }
    }

    @Inject(method = "load", at = @At("TAIL"))
    private void wormholes$loadScale(ValueInput input, CallbackInfo callback) {
        wormholes$scale.scale((Entity) (Object) this, input.getDoubleOr("wormholesScale", 1.0D));
    }
}
