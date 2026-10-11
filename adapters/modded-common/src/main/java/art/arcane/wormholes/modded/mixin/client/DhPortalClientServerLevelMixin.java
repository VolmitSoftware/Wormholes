package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.dh.DhPortalLevelWrappers;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.seibel.distanthorizons.core.level.DhClientServerLevel;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IServerLevelWrapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.level.DhClientServerLevel", remap = false)
public abstract class DhPortalClientServerLevelMixin implements DhPortalLevelWrappers.Binding {
    @Unique
    private volatile IClientLevelWrapper wormholes$clientLevel;

    @Override
    public void wormholes$clientLevelWrapper(IClientLevelWrapper wrapper) {
        IServerLevelWrapper server = ((DhClientServerLevel) (Object) this).getServerLevelWrapper();
        if (!server.getDimensionName().equals(wrapper.getDimensionName()) || server.getMinHeight() != wrapper.getMinHeight()
            || server.getMaxHeight() != wrapper.getMaxHeight()) {
            throw new IllegalArgumentException("Distant Horizons client wrapper does not match its integrated destination level");
        }
        wormholes$clientLevel = wrapper;
    }

    @Inject(method = "getClientLevelWrapper", at = @At("HEAD"), cancellable = true)
    private void wormholes$destinationLevel(CallbackInfoReturnable<IClientLevelWrapper> callback) {
        IClientLevelWrapper wrapper = wormholes$clientLevel;
        if (wrapper != null) {
            callback.setReturnValue(wrapper);
        }
    }

    @WrapMethod(method = "close")
    private void wormholes$closeDestinationLevel(Operation<Void> original) {
        try {
            original.call();
        } finally {
            wormholes$clientLevel = null;
        }
    }
}
