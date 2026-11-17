package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.stencil.PortalFrameHooks;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Captures the frame's projection, including view bobbing and nausea, as it is handed to the level renderer. */
@Mixin(GameRenderer.class)
public abstract class PortalProjectionMixin {
    @ModifyArg(method = "renderLevel(FJLcom/mojang/blaze3d/vertex/PoseStack;)V", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lcom/mojang/blaze3d/vertex/PoseStack;FJZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;)V"),
        index = 7)
    private Matrix4f wormholes$captureProjection(Matrix4f projection) {
        PortalFrameHooks.captureProjection(projection);
        return projection;
    }
}
