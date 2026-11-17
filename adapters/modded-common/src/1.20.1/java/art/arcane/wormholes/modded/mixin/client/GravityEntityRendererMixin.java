package art.arcane.wormholes.modded.mixin.client;

import art.arcane.optics.crossing.GravityFrame;
import art.arcane.wormholes.modded.gravity.MinecraftGravity;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(EntityRenderer.class)
public abstract class GravityEntityRendererMixin {
    @WrapOperation(method = "renderNameTag", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V"))
    private void wormholes$gravityNameTag(PoseStack poseStack, float x, float y, float z, Operation<Void> original,
                                          @Local(argsOnly = true) Entity entity) {
        GravityFrame frame = MinecraftGravity.frame(entity);
        if (frame.equals(GravityFrame.WORLD)) {
            original.call(poseStack, x, y, z);
            return;
        }
        Vec3 attachment = MinecraftGravity.world(frame, new Vec3(x, y, z));
        original.call(poseStack, (float) attachment.x, (float) attachment.y, (float) attachment.z);
    }
}
