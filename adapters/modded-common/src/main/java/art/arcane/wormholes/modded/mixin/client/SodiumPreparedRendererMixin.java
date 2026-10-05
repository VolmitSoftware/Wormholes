package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.ClientSodiumTerrain;
import art.arcane.wormholes.modded.client.render.PortalSodiumRendererAccess;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = LevelRenderer.class, priority = 900)
public abstract class SodiumPreparedRendererMixin implements PortalSodiumRendererAccess {
    @Override
    @Dynamic
    @Accessor(value = "renderer", remap = false)
    public abstract void wormholes$terrainRenderer(SodiumWorldRenderer renderer);

    @Inject(method = "endFrame", at = @At("RETURN"))
    private void wormholes$endPreparedFrame(CallbackInfo callback) {
        ClientSodiumTerrain.endFrame();
    }
}
