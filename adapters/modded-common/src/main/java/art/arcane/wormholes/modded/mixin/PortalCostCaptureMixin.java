package art.arcane.wormholes.modded.mixin;

import art.arcane.wormholes.modded.MinecraftPortalMenus;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
public abstract class PortalCostCaptureMixin {
    @Inject(method = "drop(Z)V", at = @At("HEAD"), cancellable = true)
    private void wormholesCaptureTravelItem(boolean all, CallbackInfo callback) {
        if (MinecraftPortalMenus.captureDroppedItem((ServerPlayer) (Object) this)) {
            callback.cancel();
        }
    }
}
