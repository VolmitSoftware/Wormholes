package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.PortalIrisHistory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.gl.program.ProgramUniforms", remap = false)
public abstract class IrisPortalUniformHistoryMixin implements PortalIrisHistory.State {
    @Shadow long lastTick;
    @Shadow int lastFrame;
    @Inject(method = "<init>", at = @At("RETURN"))
    private void wormholes$capture(CallbackInfo callback) {
        PortalIrisHistory.register(this);
    }

    @Override
    public void wormholes$resetHistory() {
        lastTick = Long.MIN_VALUE;
        lastFrame = Integer.MIN_VALUE;
    }
}
