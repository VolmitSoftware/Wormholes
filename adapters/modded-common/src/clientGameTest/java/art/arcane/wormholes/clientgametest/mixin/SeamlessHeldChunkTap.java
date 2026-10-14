package art.arcane.wormholes.clientgametest.mixin;

import art.arcane.wormholes.clientgametest.SeamlessHeldChunk;
import art.arcane.wormholes.modded.seamless.MinecraftSeamlessMove;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MinecraftSeamlessMove.class)
public abstract class SeamlessHeldChunkTap {
    @Inject(method = "run", at = @At("RETURN"))
    private static void wormholesTest$editHeldChunk(MinecraftSeamlessMove.Context context, CallbackInfoReturnable<Boolean> callback) {
        if (Boolean.TRUE.equals(callback.getReturnValue())) {
            SeamlessHeldChunk.moved(context.player());
        }
    }
}
