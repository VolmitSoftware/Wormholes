package art.arcane.wormholes.modded.mixin;

import art.arcane.wormholes.modded.menu.MinecraftMenuCaptures;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
public abstract class PortalCostCaptureMixin {
    @Inject(method = "drop(Z)V", at = @At("HEAD"), cancellable = true)
    private void wormholesCaptureTravelItem(boolean all, CallbackInfo callback) {
        if (MinecraftMenuCaptures.dropped((ServerPlayer) (Object) this)) {
            callback.cancel();
        }
    }
}
