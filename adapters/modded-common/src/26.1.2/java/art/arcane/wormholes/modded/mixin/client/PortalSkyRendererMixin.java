package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.SkyRenderTarget;
import art.arcane.wormholes.modded.client.render.stencil.PortalStencilRenderer;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SkyRenderer.class)
public abstract class PortalSkyRendererMixin implements SkyRenderTarget {
    @Unique
    private RenderTarget wormholes$target;

    @Override
    public RenderTarget wormholes$renderTarget() {
        return wormholes$target;
    }

    @Override
    public void wormholes$renderTarget(RenderTarget target) {
        wormholes$target = target;
    }

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void wormholes$destinationHorizon(ClientLevel level, float partialTicks, Camera camera, SkyRenderState state, CallbackInfo callback) {
        if (!PortalStencilRenderer.instance().nested() || state.skybox != DimensionType.Skybox.OVERWORLD) {
            return;
        }
        state.shouldRenderDarkDisc = camera.position().y < level.getLevelData().getHorizonHeight(level)
            && camera.getFluidInCamera() != FogType.WATER;
    }

    @WrapOperation(method = {"renderSkyDisc", "renderDarkDisc", "renderSun", "renderMoon", "renderStars", "renderSunriseAndSunset",
        "renderEndSky", "renderEndFlash"}, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/Minecraft;getMainRenderTarget()Lcom/mojang/blaze3d/pipeline/RenderTarget;"))
    private RenderTarget wormholes$skyTarget(Minecraft minecraft, Operation<RenderTarget> original) {
        return wormholes$target == null ? original.call(minecraft) : wormholes$target;
    }
}
