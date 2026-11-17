package art.arcane.wormholes.modded.mixin;

import art.arcane.optics.crossing.GravityFrame;
import art.arcane.wormholes.modded.gravity.MinecraftGravity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
public abstract class GravityServerPlayerMixin {
    @Unique
    private Vec3 wormholes$fallMovement = Vec3.ZERO;

    @Inject(method = "restoreFrom", at = @At("TAIL"))
    private void wormholes$respawnGravity(ServerPlayer previous, boolean alive, CallbackInfo callback) {
        MinecraftGravity.set((ServerPlayer) (Object) this, alive ? MinecraftGravity.frame(previous) : GravityFrame.WORLD);
    }

    @Inject(method = "doCheckFallDamage", at = @At("HEAD"))
    private void wormholes$gravityFallMovement(double x, double y, double z, boolean grounded, CallbackInfo callback) {
        wormholes$fallMovement = new Vec3(x, y, z);
    }

    @ModifyArg(method = "doCheckFallDamage", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/player/Player;checkFallDamage(DZLnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)V"), index = 0)
    private double wormholes$gravityServerFall(double amount) {
        GravityFrame frame = MinecraftGravity.frame((ServerPlayer) (Object) this);
        return frame.equals(GravityFrame.WORLD) ? amount : MinecraftGravity.local(frame, wormholes$fallMovement).y;
    }
}
