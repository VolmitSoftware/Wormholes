package art.arcane.wormholes.modded.mixin.client;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClientPacketListener.class)
public interface PreparedPacketAccess {
    @Accessor("level")
    void wormholes$level(ClientLevel level);

    @Accessor("levelData")
    void wormholes$data(ClientLevel.ClientLevelData data);

    @Accessor("levelData")
    ClientLevel.ClientLevelData wormholes$data();

    @Accessor("serverChunkRadius")
    int wormholes$chunkRadius();
}
