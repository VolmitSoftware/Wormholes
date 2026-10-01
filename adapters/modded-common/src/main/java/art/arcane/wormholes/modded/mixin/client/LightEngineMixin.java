package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.ClientLightPatches;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LightEngine.class)
public abstract class LightEngineMixin {
    @Inject(method = "getLightValue", at = @At("HEAD"), cancellable = true)
    private void wormholesPatchedLight(BlockPos position, CallbackInfoReturnable<Integer> callback) {
        int patched = ClientLightPatches.patched(this, position.getX(), position.getY(), position.getZ());
        if (patched >= 0) {
            callback.setReturnValue(patched);
        }
    }

    @Inject(method = "getState", at = @At("RETURN"), cancellable = true)
    private void wormholesRealState(BlockPos position, CallbackInfoReturnable<BlockState> callback) {
        BlockState real = ClientLightPatches.realState(this, position.getX(), position.getY(), position.getZ());
        if (real != null) {
            callback.setReturnValue(real);
        }
    }
}
