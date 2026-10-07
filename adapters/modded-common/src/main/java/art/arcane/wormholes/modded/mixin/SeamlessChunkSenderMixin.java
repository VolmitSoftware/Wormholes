package art.arcane.wormholes.modded.mixin;

import art.arcane.wormholes.modded.seamless.HeldChunkSender;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
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
    private final LongSet wormholes$held = new LongOpenHashSet();

    @Override
    public LongSet wormholesHeldChunks() {
        return wormholes$held;
    }

    @Inject(method = "dropChunk", at = @At("HEAD"))
    private void wormholesReleaseHeldChunk(ServerPlayer player, ChunkPos pos, CallbackInfo callback) {
        wormholes$held.remove(pos.pack());
    }
}
