package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.iris.IrisFullscreenScope;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.FinalPassRenderer", remap = false)
public abstract class IrisPortalFullscreenFinalMixin {
    @WrapMethod(method = "renderFinalPass")
    private void wormholes$fullscreen(Operation<Void> original) {
        try (IrisFullscreenScope scope = IrisFullscreenScope.open()) {
            original.call();
        }
    }
}
