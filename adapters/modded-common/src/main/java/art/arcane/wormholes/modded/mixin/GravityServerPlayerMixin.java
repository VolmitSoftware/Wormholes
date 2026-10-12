package art.arcane.wormholes.modded.mixin;

import art.arcane.optics.crossing.GravityFrame;
import art.arcane.wormholes.modded.gravity.MinecraftGravity;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
public abstract class GravityServerPlayerMixin {
    @Inject(method = "restoreFrom", at = @At("TAIL"))
    private void wormholes$respawnGravity(ServerPlayer previous, boolean alive, CallbackInfo callback) {
        MinecraftGravity.set((ServerPlayer) (Object) this, alive ? MinecraftGravity.frame(previous) : GravityFrame.WORLD);
    }
}
