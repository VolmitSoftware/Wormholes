package art.arcane.wormholes.modded.mixin;

import art.arcane.wormholes.modded.seamless.UnsentChunkChanges;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChunkHolder.class)
public abstract class SeamlessChunkHolderMixin implements UnsentChunkChanges {
    @Unique
    private boolean wormholes$unsentChange;
    @Unique
    private long wormholes$unsentChangeAt;

    @Shadow
    public abstract LevelChunk getTickingChunk();

    @Override
    public boolean wormholesUnsentChange() {
        return wormholes$unsentChange;
    }

    @Override
    public long wormholesUnsentChangeAt() {
        return wormholes$unsentChangeAt;
    }

    @Inject(method = {"blockChanged", "sectionLightChanged"}, at = @At("HEAD"))
    private void wormholesRecordUnsentChange(CallbackInfoReturnable<Boolean> callback) {
        if (getTickingChunk() == null) {
            wormholes$unsentChange = true;
            wormholes$unsentChangeAt = System.nanoTime();
        }
    }
}
