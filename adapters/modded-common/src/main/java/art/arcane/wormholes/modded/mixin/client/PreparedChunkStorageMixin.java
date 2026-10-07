package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.PreparedChunkColumns;
import art.arcane.wormholes.modded.client.PreparedChunkStorage;
import art.arcane.wormholes.modded.client.WormholesClient;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.atomic.AtomicReferenceArray;

import static net.minecraft.world.level.chunk.status.ChunkStatus.FULL;

@Mixin(targets = "net.minecraft.client.multiplayer.ClientChunkCache$Storage")
public abstract class PreparedChunkStorageMixin implements PreparedChunkStorage {
    @Shadow
    @Final
    private AtomicReferenceArray<LevelChunk> chunks;

    @Shadow
    protected abstract void onChunkAdded(LevelChunk chunk);

    @Override
    public void wormholes$announce() {
        for (int index = 0; index < chunks.length(); index++) {
            LevelChunk chunk = chunks.get(index);
            if (chunk != null) {
                onChunkAdded(chunk);
            }
        }
    }

    @WrapOperation(method = {"replace", "drop"}, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/multiplayer/ClientLevel;unload(Lnet/minecraft/world/level/chunk/LevelChunk;)V"))
    private void wormholes$unloaded(ClientLevel level, LevelChunk chunk, Operation<Void> original) {
        original.call(level, chunk);
        int x = chunk.getPos().x();
        int z = chunk.getPos().z();
        if (level.getChunkSource().getChunk(x, z, FULL, false) != chunk) {
            WormholesClient.localChunkUnloaded(level, x, z);
        }
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void wormholes$storage(ClientChunkCache cache, int radius, CallbackInfo callback) {
        ((PreparedChunkColumns) cache).wormholes$storage(this, chunks, radius);
    }
}
