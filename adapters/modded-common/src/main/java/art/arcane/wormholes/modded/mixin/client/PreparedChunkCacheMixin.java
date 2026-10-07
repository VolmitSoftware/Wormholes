package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.PreparedChunkColumns;
import art.arcane.wormholes.modded.client.WormholesClient;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.atomic.AtomicReferenceArray;

@Mixin(ClientChunkCache.class)
public abstract class PreparedChunkCacheMixin implements PreparedChunkColumns {
    @Shadow
    @Final
    private ClientLevel level;

    @Unique
    private AtomicReferenceArray<LevelChunk> wormholes$columns;

    @Unique
    private int wormholes$radius;

    @Override
    public AtomicReferenceArray<LevelChunk> wormholes$columns() {
        return wormholes$columns;
    }

    @Override
    public int wormholes$radius() {
        return wormholes$radius;
    }

    @Override
    public void wormholes$storage(AtomicReferenceArray<LevelChunk> columns, int radius) {
        wormholes$columns = columns;
        wormholes$radius = radius;
    }

    @Inject(method = "onLightUpdate", at = @At("HEAD"), cancellable = true)
    private void wormholesPreparedLight(LightLayer layer, SectionPos position, CallbackInfo callback) {
        if (!WormholesClient.activeLevel(level)) {
            callback.cancel();
        }
    }
}
