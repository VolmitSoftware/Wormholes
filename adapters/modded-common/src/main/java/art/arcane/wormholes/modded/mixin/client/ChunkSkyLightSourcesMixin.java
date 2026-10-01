package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.ClientLightGate;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.ChunkSkyLightSources;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChunkSkyLightSources.class)
public abstract class ChunkSkyLightSourcesMixin {
    @Inject(method = "update", at = @At("HEAD"), cancellable = true)
    private void wormholesKeepRealSources(BlockGetter chunk, int x, int y, int z, CallbackInfoReturnable<Boolean> callback) {
        if (chunk instanceof LevelChunk levelChunk && ClientLightGate.suppressesLevel(levelChunk.getLevel())) {
            callback.setReturnValue(false);
        }
    }
}
