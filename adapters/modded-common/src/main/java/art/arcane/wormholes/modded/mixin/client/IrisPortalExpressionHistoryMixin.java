package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.PortalIrisHistory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.parsing.SmoothFloat", remap = false)
public abstract class IrisPortalExpressionHistoryMixin implements PortalIrisHistory.State {
    @Shadow private float accumulator;
    @Shadow private boolean hasInitialValue;
    @Inject(method = "<init>", at = @At("RETURN"))
    private void wormholes$capture(CallbackInfo callback) {
        PortalIrisHistory.register(this);
    }

    @Override
    public void wormholes$resetHistory() {
        accumulator = 0;
        hasInitialValue = false;
    }
}
