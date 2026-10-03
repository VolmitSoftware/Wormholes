package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.WormholesClient;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientChunkCache.class)
public abstract class LocalMeshChunkMixin {
    @Shadow @Final private ClientLevel level;

    @Inject(method = "drop", at = @At("RETURN"))
    private void wormholesLocalUnload(ChunkPos position, CallbackInfo callback) {
        WormholesClient.localChunkChanged(level, position.x(), position.z());
    }

    @Inject(method = "replaceBiomes", at = @At("RETURN"))
    private void wormholesLocalBiomes(int x, int z, FriendlyByteBuf data, CallbackInfo callback) {
        WormholesClient.localChunkChanged(level, x, z);
    }

    @Inject(method = "onLightUpdate", at = @At("RETURN"))
    private void wormholesLocalLight(LightLayer layer, SectionPos position, CallbackInfo callback) {
        WormholesClient.localSectionChanged(level, position.x(), position.y(), position.z());
    }
}
