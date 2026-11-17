package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.PreparedChunkColumns;
import art.arcane.wormholes.modded.client.PreparedChunkStorage;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

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

    @Unique
    private PreparedChunkStorage wormholes$storage;

    @Override
    public AtomicReferenceArray<LevelChunk> wormholes$columns() {
        return wormholes$columns;
    }

    @Override
    public int wormholes$radius() {
        return wormholes$radius;
    }

    @Override
    public void wormholes$storage(PreparedChunkStorage storage, AtomicReferenceArray<LevelChunk> columns, int radius) {
        wormholes$storage = storage;
        wormholes$columns = columns;
        wormholes$radius = radius;
    }

    @Override
    public void wormholes$announceColumns() {
        wormholes$storage.wormholes$announce();
    }

    @Override
    public boolean wormholes$retainColumn(LevelChunk chunk) {
        return wormholes$storage.wormholes$retain(chunk);
    }

    /** Vanilla dirties {@code minecraft.levelRenderer}; light of a resident level belongs to that level's own renderer. */
    @WrapOperation(method = "onLightUpdate", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/LevelRenderer;setSectionDirty(III)V"))
    private void wormholes$levelRenderer(LevelRenderer renderer, int x, int y, int z, Operation<Void> original) {
        original.call(((PreparedLevelAccess) level).wormholes$extractor(), x, y, z);
    }
}
