package art.arcane.wormholes.modded.mixin;

import art.arcane.wormholes.modded.MinecraftScaleAccess;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
public abstract class ScaleServerPlayerMixin {
    @Unique
    private static final MinecraftScaleAccess wormholes$scale = MinecraftScaleAccess.scaleAttribute();

    @Inject(method = "restoreFrom", at = @At("TAIL"))
    private void wormholes$respawnScale(ServerPlayer previous, boolean alive, CallbackInfo callback) {
        wormholes$scale.scale((ServerPlayer) (Object) this, alive ? wormholes$scale.scale(previous) : 1.0D);
    }
}
