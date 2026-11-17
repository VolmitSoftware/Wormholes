package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.PreparedChunkColumns;
import art.arcane.wormholes.modded.client.PreparedChunkStorage;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.atomic.AtomicReferenceArray;

@Mixin(targets = "net.minecraft.client.multiplayer.ClientChunkCache$Storage")
public abstract class PreparedChunkStorageMixin implements PreparedChunkStorage {
    @Shadow
    @Final
    private AtomicReferenceArray<LevelChunk> chunks;

    @Shadow
    protected abstract boolean inRange(int x, int z);

    @Shadow
    protected abstract int getIndex(int x, int z);

    @Shadow
    protected abstract void replace(int index, LevelChunk chunk);

    @Override
    public boolean wormholes$retain(LevelChunk chunk) {
        int x = chunk.getPos().x;
        int z = chunk.getPos().z;
        if (!inRange(x, z)) {
            return false;
        }
        int index = getIndex(x, z);
        if (chunks.get(index) != null) {
            return false;
        }
        replace(index, chunk);
        return true;
    }

    /** The 1.20.1 renderer looks chunks up in the level when it compiles, so stored columns need no announcement. */
    @Override
    public void wormholes$announce() {
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void wormholes$storage(ClientChunkCache cache, int radius, CallbackInfo callback) {
        ((PreparedChunkColumns) cache).wormholes$storage(this, chunks, radius);
    }
}
