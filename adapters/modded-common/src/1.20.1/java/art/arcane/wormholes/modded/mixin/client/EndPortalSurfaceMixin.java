package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.ClientPortalRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.TheEndPortalRenderer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.TheEndPortalBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hides the end portal surface where a Wormholes view already shows the destination. */
@Mixin(TheEndPortalRenderer.class)
public abstract class EndPortalSurfaceMixin {
    @Inject(method = "render(Lnet/minecraft/world/level/block/entity/TheEndPortalBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
        at = @At("HEAD"), cancellable = true)
    private void wormholes$managedSurface(TheEndPortalBlockEntity blockEntity, float partialTicks, PoseStack poseStack, MultiBufferSource buffers,
                                         int light, int overlay, CallbackInfo callback) {
        if (blockEntity.getLevel() == Minecraft.getInstance().level && blockEntity.getBlockState().is(Blocks.END_PORTAL)
            && ClientPortalRenderer.instance().coversEndPortalSurface(blockEntity.getBlockPos())) {
            callback.cancel();
        }
    }
}
