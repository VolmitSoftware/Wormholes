package art.arcane.wormholes.modded.mixin.client;

import art.arcane.optics.crossing.GravityFrame;
import art.arcane.wormholes.modded.client.GravityAutoJump;
import art.arcane.wormholes.modded.gravity.MinecraftGravity;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LocalPlayer.class)
public abstract class GravityLocalPlayerMixin {
    @Shadow
    private int autoJumpTime;
    @Shadow
    private boolean canAutoJump() {
        throw new AssertionError();
    }

    @Unique
    private Vec3 wormholes$beforeMove;

    @Inject(method = "move", at = @At("HEAD"))
    private void wormholes$beforeMove(MoverType type, Vec3 movement, CallbackInfo callback) {
        LocalPlayer player = (LocalPlayer) (Object) this;
        wormholes$beforeMove = MinecraftGravity.frame(player).equals(GravityFrame.WORLD) ? null : player.position();
    }

    @WrapOperation(method = "move", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;updateAutoJump(FF)V"))
    private void wormholes$gravityAutoJumpMovement(LocalPlayer player, float x, float z, Operation<Void> original) {
        if (wormholes$beforeMove == null) {
            original.call(player, x, z);
        } else {
            Vec3 movement = MinecraftGravity.local(MinecraftGravity.frame(player), player.position().subtract(wormholes$beforeMove));
            original.call(player, (float) movement.x, (float) movement.z);
        }
    }

    @WrapOperation(method = "move", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;addWalkedDistance(F)V"))
    private void wormholes$gravityWalkedDistance(LocalPlayer player, float amount, Operation<Void> original) {
        if (wormholes$beforeMove == null) {
            original.call(player, amount);
        } else {
            Vec3 movement = MinecraftGravity.local(MinecraftGravity.frame(player), player.position().subtract(wormholes$beforeMove));
            original.call(player, (float) movement.horizontalDistance() * 0.6F);
        }
    }

    @Inject(method = "updateAutoJump", at = @At("HEAD"), cancellable = true)
    private void wormholes$gravityAutoJump(float x, float z, CallbackInfo callback) {
        LocalPlayer player = (LocalPlayer) (Object) this;
        if (!MinecraftGravity.frame(player).equals(GravityFrame.WORLD)) {
            if (canAutoJump() && GravityAutoJump.shouldJump(player, x, z)) {
                autoJumpTime = 1;
            }
            callback.cancel();
        }
    }

    @ModifyVariable(method = "isHorizontalCollisionMinor", at = @At("HEAD"), argsOnly = true)
    private Vec3 wormholes$localCollisionMovement(Vec3 movement) {
        GravityFrame frame = MinecraftGravity.frame((LocalPlayer) (Object) this);
        return frame.equals(GravityFrame.WORLD) ? movement : MinecraftGravity.local(frame, movement);
    }
}
