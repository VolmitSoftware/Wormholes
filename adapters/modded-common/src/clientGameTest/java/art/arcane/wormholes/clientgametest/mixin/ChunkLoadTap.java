package art.arcane.wormholes.clientgametest.mixin;

import art.arcane.wormholes.clientgametest.LoadedChunks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelChunk.class)
public abstract class ChunkLoadTap {
    @Inject(method = "setLoaded", at = @At("HEAD"))
    private void wormholesTest$loaded(boolean loaded, CallbackInfo callback) {
        LevelChunk chunk = (LevelChunk) (Object) this;
        if (loaded && chunk.getLevel() instanceof ServerLevel level) {
            LoadedChunks.loaded(level, chunk.getPos());
        }
    }
}
