package art.arcane.wormholes.modded.mixin;

import art.arcane.wormholes.modded.seamless.HeldChunkSender;
import art.arcane.wormholes.modded.seamless.HeldChunks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerChunkSender.class)
public abstract class SeamlessChunkSenderMixin implements HeldChunkSender {
    @Unique
    private final HeldChunks wormholes$held = new HeldChunks();

    @Override
    public HeldChunks wormholesHeldChunks() {
        return wormholes$held;
    }

    @Inject(method = "dropChunk", at = @At("HEAD"))
    private void wormholesReleaseHeldChunk(ServerPlayer player, ChunkPos pos, CallbackInfo callback) {
        wormholes$held.release(pos.pack());
    }
}
