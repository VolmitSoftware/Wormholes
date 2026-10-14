package art.arcane.wormholes.clientgametest.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(targets = "net.fabricmc.fabric.impl.client.gametest.context.ClientGameTestContextImpl", remap = false)
public abstract class ScreenshotFrameUpdateMixin {
    @WrapOperation(method = "lambda$doTakeScreenshot$1", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/GameRenderer;extract(Lnet/minecraft/client/DeltaTracker;Z)V"))
    private static void wormholesTest$updateBeforeExtract(GameRenderer renderer, DeltaTracker tracker, boolean renderLevel,
                                                         Operation<Void> original) {
        renderer.update(tracker, renderLevel);
        original.call(renderer, tracker, renderLevel);
    }
}
