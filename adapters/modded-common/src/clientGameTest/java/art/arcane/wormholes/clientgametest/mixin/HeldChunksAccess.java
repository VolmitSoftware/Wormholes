package art.arcane.wormholes.clientgametest.mixin;

import art.arcane.wormholes.modded.seamless.HeldChunks;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(HeldChunks.class)
public interface HeldChunksAccess {
    @Accessor("heldAt")
    Long2LongOpenHashMap wormholesTest$heldAt();
}
