package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.stencil.VanillaTerrainBackend;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelExtractor.class)
public abstract class VanillaTerrainExtractorMixin {
    @Shadow @Final private LevelRenderer levelRenderer;

    @Inject(method = "extract", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/LevelRenderer;visibleSections()Lit/unimi/dsi/fastutil/objects/ObjectArrayList;"))
    private void wormholes$discoverInitializedTerrain(DeltaTracker tracker, Camera camera, float partialTicks, CallbackInfo callback) {
        VanillaTerrainBackend.INSTANCE.discoverSections(levelRenderer);
    }
}
