package art.arcane.wormholes.modded.mixin;

import art.arcane.wormholes.modded.seamless.UnsavedChunkHook;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkAccess.class)
public abstract class ViewChunkUnsavedMixin {
    @Inject(method = "setUnsaved", at = @At("TAIL"))
    private void wormholesAppliedReplacement(boolean unsaved, CallbackInfo callback) {
        if (unsaved && (Object) this instanceof UnsavedChunkHook hook) {
            hook.wormholesMarkedUnsaved();
        }
    }
}
