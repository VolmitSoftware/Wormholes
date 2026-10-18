package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.world.ClientWorldLoader;
import art.arcane.wormholes.modded.client.WormholesClient;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Unique;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class ClientWorldMinecraftMixin {
    @Unique private boolean wormholes$arrivalInstalled;
    @Inject(method = "updateLevelInEngines(Lnet/minecraft/client/multiplayer/ClientLevel;Z)V", at = @At("HEAD"))
    private void wormholes$cleanUpClientWorlds(ClientLevel level, boolean stopSound, CallbackInfo callback) {
        WormholesClient client = WormholesClient.instance();
        wormholes$arrivalInstalled = client != null && client.arrivals().installLevel(level);
        if (!wormholes$arrivalInstalled) {
            ClientWorldLoader.cleanUp();
        }
    }

    @WrapOperation(method = "updateLevelInEngines(Lnet/minecraft/client/multiplayer/ClientLevel;Z)V", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/extract/LevelExtractor;setLevel(Lnet/minecraft/client/multiplayer/ClientLevel;)V"))
    private void wormholes$retainArrivalMeshes(LevelExtractor extractor, ClientLevel level, Operation<Void> original) {
        if (!wormholes$arrivalInstalled) {
            original.call(extractor, level);
        }
        wormholes$arrivalInstalled = false;
    }

    @Inject(method = "renderFrame", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;endFrame()V", shift = At.Shift.AFTER))
    private void wormholes$endClientWorldFrames(boolean advanceGameTime, CallbackInfo callback) {
        ClientWorldLoader.endFrame();
    }
}
