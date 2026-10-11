package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.stencil.PortalStencilRenderer;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SkyRenderer.class)
public abstract class PortalSkyRendererMixin {
    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void wormholes$destinationHorizon(ClientLevel level, float partialTicks, Camera camera, SkyRenderState state, CallbackInfo callback) {
        if (!PortalStencilRenderer.instance().nested() || state.skybox != DimensionType.Skybox.OVERWORLD) {
            return;
        }
        state.shouldRenderDarkDisc = camera.position().y < level.getLevelData().getHorizonHeight(level)
            && camera.getFluidInCamera() != FogType.WATER;
    }
}
