package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.stencil.VanillaTerrainBackend;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Inside a portal layer, the vanilla terrain backend replaces the occlusion search of {@code setupRender}. */
@Mixin(LevelRenderer.class)
public abstract class VanillaTerrainRendererMixin {
    @WrapOperation(method = "renderLevel(Lcom/mojang/blaze3d/vertex/PoseStack;FJZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;setupRender(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/culling/Frustum;ZZ)V"))
    private void wormholes$layerSections(LevelRenderer renderer, Camera camera, Frustum frustum, boolean captured, boolean spectator,
                                         Operation<Void> original) {
        if (!VanillaTerrainBackend.INSTANCE.setupLayer(renderer, camera)) {
            original.call(renderer, camera, frustum, captured, spectator);
        }
    }
}
