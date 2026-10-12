package art.arcane.automator.mixin;

import art.arcane.automator.CrossingCosts;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class CrossingCostGameRendererMixin {
    @Inject(method = "setLevel", at = @At("HEAD"))
    private void automator$setLevelBegin(ClientLevel destination, CallbackInfo callback) {
        CrossingCosts.begin(CrossingCosts.Phase.SET_LEVEL, destination == null ? "disconnected" : destination.dimension().identifier().toString());
    }

    @Inject(method = "setLevel", at = @At("RETURN"))
    private void automator$setLevelEnd(ClientLevel destination, CallbackInfo callback) {
        CrossingCosts.end(CrossingCosts.Phase.SET_LEVEL);
        CrossingCosts.dimension(destination == null ? "disconnected" : destination.dimension().identifier().toString());
    }
}
