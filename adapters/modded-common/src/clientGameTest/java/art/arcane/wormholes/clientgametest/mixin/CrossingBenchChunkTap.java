package art.arcane.wormholes.clientgametest.mixin;

import art.arcane.wormholes.clientgametest.CrossingBenchTap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerChunkCache.class)
public abstract class CrossingBenchChunkTap {
    @Unique
    private long wormholesBench$blockStarted;

    @Inject(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerChunkCache$MainThreadExecutor;managedBlock(Ljava/util/function/BooleanSupplier;)V"))
    private void wormholesBench$blockStarted(int x, int z, ChunkStatus status, boolean load, CallbackInfoReturnable<ChunkAccess> callback) {
        wormholesBench$blockStarted = CrossingBenchTap.active() ? System.nanoTime() : 0L;
    }

    @Inject(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerChunkCache$MainThreadExecutor;managedBlock(Ljava/util/function/BooleanSupplier;)V",
            shift = At.Shift.AFTER))
    private void wormholesBench$blockEnded(int x, int z, ChunkStatus status, boolean load, CallbackInfoReturnable<ChunkAccess> callback) {
        if (wormholesBench$blockStarted != 0L) {
            CrossingBenchTap.chunkWait(System.nanoTime() - wormholesBench$blockStarted);
            wormholesBench$blockStarted = 0L;
        }
    }
}
