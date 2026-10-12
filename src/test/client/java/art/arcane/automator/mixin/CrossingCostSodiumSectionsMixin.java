package art.arcane.automator.mixin;

import art.arcane.automator.CrossingCosts;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager", remap = false)
public abstract class CrossingCostSodiumSectionsMixin {
    @Inject(method = "consumeCullTaskResults", at = @At("HEAD"))
    private void automator$cullBegin(boolean waitForCompletion, CallbackInfo callback) {
        if (waitForCompletion) {
            CrossingCosts.begin(CrossingCosts.Phase.CULL_WAIT);
        }
    }

    @Inject(method = "consumeCullTaskResults", at = @At("RETURN"))
    private void automator$cullEnd(boolean waitForCompletion, CallbackInfo callback) {
        if (waitForCompletion) {
            CrossingCosts.end(CrossingCosts.Phase.CULL_WAIT);
        }
    }

    @Inject(method = "updateChunks", at = @At("HEAD"))
    private void automator$updateBegin(@Coerce Object viewport, boolean immediate, CallbackInfo callback) {
        CrossingCosts.begin(immediate ? CrossingCosts.Phase.UPDATE_CHUNKS_IMMEDIATE : CrossingCosts.Phase.UPDATE_CHUNKS);
    }

    @Inject(method = "updateChunks", at = @At("RETURN"))
    private void automator$updateEnd(@Coerce Object viewport, boolean immediate, CallbackInfo callback) {
        CrossingCosts.end(immediate ? CrossingCosts.Phase.UPDATE_CHUNKS_IMMEDIATE : CrossingCosts.Phase.UPDATE_CHUNKS);
    }

    @Inject(method = "processChunkBuilds", at = @At("HEAD"))
    private void automator$buildBegin(CallbackInfo callback) {
        CrossingCosts.begin(CrossingCosts.Phase.PROCESS_BUILDS);
    }

    @Inject(method = "processChunkBuilds", at = @At("RETURN"))
    private void automator$buildEnd(CallbackInfo callback) {
        CrossingCosts.end(CrossingCosts.Phase.PROCESS_BUILDS);
    }
}
