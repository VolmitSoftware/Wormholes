package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.world.ClientWorldLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class ClientWorldMinecraftMixin {
    @Inject(method = "updateLevelInEngines(Lnet/minecraft/client/multiplayer/ClientLevel;Z)V", at = @At("HEAD"))
    private void wormholes$cleanUpClientWorlds(ClientLevel level, boolean stopSound, CallbackInfo callback) {
        ClientWorldLoader.cleanUp();
    }

    @Inject(method = "renderFrame", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;endFrame()V", shift = At.Shift.AFTER))
    private void wormholes$endClientWorldFrames(boolean advanceGameTime, CallbackInfo callback) {
        ClientWorldLoader.endFrame();
    }
}
