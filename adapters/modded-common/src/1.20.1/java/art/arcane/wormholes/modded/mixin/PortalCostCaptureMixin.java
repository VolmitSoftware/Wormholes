package art.arcane.wormholes.modded.mixin;

import art.arcane.wormholes.modded.menu.MinecraftMenuCaptures;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayer.class)
public abstract class PortalCostCaptureMixin {
    @Inject(method = "drop(Z)Z", at = @At("HEAD"), cancellable = true)
    private void wormholesCaptureTravelItem(boolean all, CallbackInfoReturnable<Boolean> callback) {
        if (MinecraftMenuCaptures.dropped((ServerPlayer) (Object) this)) {
            callback.setReturnValue(false);
        }
    }
}
