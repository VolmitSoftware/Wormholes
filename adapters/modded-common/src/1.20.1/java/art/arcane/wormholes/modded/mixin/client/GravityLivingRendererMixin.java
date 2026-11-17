package art.arcane.wormholes.modded.mixin.client;

import art.arcane.optics.crossing.GravityFrame;
import art.arcane.wormholes.modded.client.GravityRenderTransform;
import art.arcane.wormholes.modded.gravity.MinecraftGravity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntityRenderer.class)
public abstract class GravityLivingRendererMixin {
    @Inject(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;pushPose()V", ordinal = 0, shift = At.Shift.AFTER))
    private void wormholes$gravityModel(LivingEntity entity, float yaw, float partialTicks, PoseStack poseStack, MultiBufferSource buffers, int light,
                                        CallbackInfo callback) {
        GravityFrame frame = MinecraftGravity.frame(entity);
        if (!frame.equals(GravityFrame.WORLD)) {
            poseStack.mulPose(GravityRenderTransform.orientation(frame));
        }
    }
}
