package art.arcane.wormholes.modded.mixin;

import art.arcane.wormholes.modded.MinecraftPortalConstruction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
public abstract class RuneChunkUnloadMixin {
    @Inject(method = "unload", at = @At("TAIL"))
    private void wormholesRuneChunkUnloaded(LevelChunk chunk, CallbackInfo callback) {
        ServerLevel level = (ServerLevel) (Object) this;
        MinecraftPortalConstruction construction = MinecraftPortalConstruction.forServer(level.getServer());
        if (construction != null) {
            construction.chunkUnloaded(level, chunk.getPos().x(), chunk.getPos().z());
        }
    }
}
