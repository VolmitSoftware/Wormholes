package art.arcane.wormholes.modded.mixin.client;

import art.arcane.optics.crossing.GravityFrame;
import art.arcane.wormholes.modded.gravity.MinecraftGravity;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalDoubleRef;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.CapeLayer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CapeLayer.class)
public abstract class GravityCapeLayerMixin {
    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/player/AbstractClientPlayer;FFFFFF)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;rotLerp(FFF)F", ordinal = 0))
    private void wormholes$gravityCape(PoseStack poseStack, MultiBufferSource buffers, int light, AbstractClientPlayer player, float limbSwing,
                                       float limbSwingAmount, float partialTicks, float age, float headYaw, float headPitch, CallbackInfo callback,
                                       @Local(ordinal = 0) LocalDoubleRef x, @Local(ordinal = 1) LocalDoubleRef y, @Local(ordinal = 2) LocalDoubleRef z) {
        GravityFrame frame = MinecraftGravity.frame(player);
        if (frame.equals(GravityFrame.WORLD)) {
            return;
        }
        Vec3 local = MinecraftGravity.local(frame, new Vec3(x.get(), y.get(), z.get()));
        x.set(local.x);
        y.set(local.y);
        z.set(local.z);
    }
}
