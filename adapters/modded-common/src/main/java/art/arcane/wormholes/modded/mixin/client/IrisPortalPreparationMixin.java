package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.iris.IrisViewPipelines;
import art.arcane.wormholes.modded.client.render.iris.IrisShaderStages;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class IrisPortalPreparationMixin {
    @Inject(method = "close", at = @At("HEAD"))
    private void wormholes$releaseCompiledStages(CallbackInfo callback) {
        IrisShaderStages.clear();
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void wormholes$preparePortalPipelines(CallbackInfo callback) {
        IrisViewPipelines.preparationStep();
    }

    @Inject(method = "clearClientLevel", at = @At("HEAD"))
    private void wormholes$clearPortalPipelines(CallbackInfo callback) {
        IrisViewPipelines.clear();
    }

    @Inject(method = "updateLevelInEngines(Lnet/minecraft/client/multiplayer/ClientLevel;Z)V", at = @At("HEAD"))
    private void wormholes$disconnectPortalPipelines(ClientLevel level, boolean renderProgress, CallbackInfo callback) {
        if (level == null) {
            IrisViewPipelines.clear();
        }
    }
}
