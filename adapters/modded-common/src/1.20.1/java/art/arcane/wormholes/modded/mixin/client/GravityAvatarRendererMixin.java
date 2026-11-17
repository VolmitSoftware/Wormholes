package art.arcane.wormholes.modded.mixin.client;

import art.arcane.optics.crossing.GravityFrame;
import art.arcane.optics.math.Face;
import art.arcane.wormholes.modded.gravity.MinecraftGravity;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(PlayerRenderer.class)
public abstract class GravityAvatarRendererMixin {
    @Unique private static final double CROUCH_OFFSET = -0.125D;

    @ModifyReturnValue(method = "getRenderOffset(Lnet/minecraft/client/player/AbstractClientPlayer;F)Lnet/minecraft/world/phys/Vec3;", at = @At("RETURN"))
    private Vec3 wormholes$gravityCrouching(Vec3 offset, @Local(argsOnly = true) AbstractClientPlayer player) {
        GravityFrame frame = MinecraftGravity.frame(player);
        if (frame.equals(GravityFrame.WORLD) || !player.isCrouching()) {
            return offset;
        }
        Face up = frame.up();
        return offset.add(CROUCH_OFFSET * up.x(), CROUCH_OFFSET * (up.y() - 1), CROUCH_OFFSET * up.z());
    }

    @ModifyExpressionValue(method = "setupRotations(Lnet/minecraft/client/player/AbstractClientPlayer;Lcom/mojang/blaze3d/vertex/PoseStack;FFF)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/AbstractClientPlayer;getViewVector(F)Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 wormholes$gravityFlightLook(Vec3 look, @Local(argsOnly = true) AbstractClientPlayer player) {
        GravityFrame frame = MinecraftGravity.frame(player);
        return frame.equals(GravityFrame.WORLD) ? look : MinecraftGravity.local(frame, look);
    }

    @ModifyExpressionValue(method = "setupRotations(Lnet/minecraft/client/player/AbstractClientPlayer;Lcom/mojang/blaze3d/vertex/PoseStack;FFF)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/AbstractClientPlayer;getDeltaMovementLerped(F)Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 wormholes$gravityFlightMovement(Vec3 movement, @Local(argsOnly = true) AbstractClientPlayer player) {
        GravityFrame frame = MinecraftGravity.frame(player);
        return frame.equals(GravityFrame.WORLD) ? movement : MinecraftGravity.local(frame, movement);
    }
}
