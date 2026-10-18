package art.arcane.wormholes.modded.mixin.client;

import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Deque;

@Mixin(ClientLevel.class)
public interface ArrivalLevelAccess {
    @Mutable
    @Accessor("chunkSource")
    void wormholes$arrivalChunks(ClientChunkCache chunks);

    @Accessor("lightUpdateQueue")
    Deque<Runnable> wormholes$pendingLight();
}
