package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.ClientLightGate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelLightEngine.class)
public abstract class LevelLightEngineMixin {
    @Inject(method = "checkBlock", at = @At("HEAD"), cancellable = true)
    private void wormholesSkipProjectedChecks(BlockPos position, CallbackInfo callback) {
        if (ClientLightGate.suppresses(this)) {
            callback.cancel();
        }
    }
}
