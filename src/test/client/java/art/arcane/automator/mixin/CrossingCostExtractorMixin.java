package art.arcane.automator.mixin;

import art.arcane.automator.CrossingCosts;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.minecraft.client.renderer.extract.LevelExtractor", remap = false)
public abstract class CrossingCostExtractorMixin {
    @Inject(method = "allChanged", at = @At("HEAD"))
    private void automator$allChangedBegin(CallbackInfo callback) {
        CrossingCosts.begin(CrossingCosts.Phase.ALL_CHANGED);
    }

    @Inject(method = "allChanged", at = @At("RETURN"))
    private void automator$allChangedEnd(CallbackInfo callback) {
        CrossingCosts.end(CrossingCosts.Phase.ALL_CHANGED);
    }
}
