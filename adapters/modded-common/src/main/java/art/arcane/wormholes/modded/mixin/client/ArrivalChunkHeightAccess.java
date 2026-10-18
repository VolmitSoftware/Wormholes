package art.arcane.wormholes.modded.mixin.client;

import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ChunkAccess.class)
public interface ArrivalChunkHeightAccess {
    @Mutable
    @Accessor("levelHeightAccessor")
    void wormholes$arrivalHeight(LevelHeightAccessor level);
}
