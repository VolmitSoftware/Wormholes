package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.WormholesClient;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.LightLayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Re-captures local mirror sections when their biomes or light change. */
@Mixin(ClientChunkCache.class)
public abstract class LocalMeshChunkMixin {
    @Shadow @Final ClientLevel level;

    @Inject(method = "replaceBiomes(IILnet/minecraft/network/FriendlyByteBuf;)V", at = @At("RETURN"))
    private void wormholesLocalBiomes(int x, int z, FriendlyByteBuf data, CallbackInfo callback) {
        WormholesClient.localChunkChanged(level, x, z);
    }

    @Inject(method = "onLightUpdate(Lnet/minecraft/world/level/LightLayer;Lnet/minecraft/core/SectionPos;)V", at = @At("RETURN"))
    private void wormholesLocalLight(LightLayer layer, SectionPos position, CallbackInfo callback) {
        WormholesClient.localLightChanged(level, position);
    }
}
