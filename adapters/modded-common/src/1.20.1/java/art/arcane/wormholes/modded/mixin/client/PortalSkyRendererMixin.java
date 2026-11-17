package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.stencil.PortalStencilRenderer;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Camera;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Inside a layer the dark horizon disc follows the layer camera's height rather than the player's eye. */
@Mixin(LevelRenderer.class)
public abstract class PortalSkyRendererMixin {
    @WrapOperation(method = "renderSky(Lcom/mojang/blaze3d/vertex/PoseStack;Lorg/joml/Matrix4f;FLnet/minecraft/client/Camera;ZLjava/lang/Runnable;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getEyePosition(F)Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 wormholes$layerHorizon(LocalPlayer player, float partialTicks, Operation<Vec3> original) {
        Camera layer = PortalStencilRenderer.instance().camera();
        return layer != null ? layer.getPosition() : original.call(player, partialTicks);
    }
}
