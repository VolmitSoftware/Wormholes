package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.PortalLightmapScope;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public abstract class PortalLightmapMixin {
    @Inject(method = {"lightmap", "levelLightmap"}, at = @At("HEAD"), cancellable = true)
    private void wormholesDestinationLightmap(CallbackInfoReturnable<GpuTextureView> callback) {
        GpuTextureView lightmap = PortalLightmapScope.current();
        if (lightmap != null) {
            callback.setReturnValue(lightmap);
        }
    }
}
