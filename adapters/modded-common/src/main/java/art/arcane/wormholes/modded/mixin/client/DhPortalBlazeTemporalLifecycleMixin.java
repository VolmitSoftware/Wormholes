package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.dh.DhBlazeTemporalViews;
import com.seibel.distanthorizons.core.level.IDhClientLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class DhPortalBlazeTemporalLifecycleMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void wormholes$expireTemporalViews(CallbackInfo callback) {
        DhBlazeTemporalViews.tick();
    }

    @Inject(method = "updateLevelInEngines(Lnet/minecraft/client/multiplayer/ClientLevel;Z)V", at = @At("HEAD"))
    private void wormholes$disconnectTemporalViews(ClientLevel level, boolean renderProgress, CallbackInfo callback) {
        if (level == null) {
            DhBlazeTemporalViews.invalidate();
        }
    }

    @Pseudo
    @Mixin(targets = "com.seibel.distanthorizons.core.api.internal.SharedApi", remap = false)
    public abstract static class World {
        @Inject(method = "setDhWorld", at = @At("HEAD"))
        private static void wormholes$closeTemporalWorld(CallbackInfo callback) {
            DhBlazeTemporalViews.invalidate();
        }
    }

    @Pseudo
    @Mixin(targets = {"com.seibel.distanthorizons.core.level.DhClientServerLevel", "com.seibel.distanthorizons.core.level.DhClientLevel"},
        remap = false)
    public abstract static class Level {
        @Inject(method = "close", at = @At("HEAD"))
        private void wormholes$closeTemporalLevel(CallbackInfo callback) {
            DhBlazeTemporalViews.closeLevel((IDhClientLevel) this);
        }
    }
}
