package art.arcane.wormholes.modded.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientChunkCache.class)
public abstract class PreparedChunkCacheMixin {
    @Shadow
    @Final
    private ClientLevel level;

    @Inject(method = "onLightUpdate", at = @At("HEAD"), cancellable = true)
    private void wormholesPreparedLight(LightLayer layer, SectionPos position, CallbackInfo callback) {
        if (Minecraft.getInstance().level != level) {
            callback.cancel();
        }
    }
}
