package art.arcane.wormholes.modded.mixin.client;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LevelChunk.class)
public interface ArrivalChunkAccess {
    @Mutable
    @Accessor("level")
    void wormholes$arrivalLevel(Level level);
}
