package art.arcane.wormholes.modded.mixin.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClientPacketListener.class)
public interface CrossingPacketAccess {
    @Accessor("level")
    void wormholes$crossingLevel(ClientLevel level);

    @Accessor("levelData")
    void wormholes$crossingData(ClientLevel.ClientLevelData data);

    @Accessor("levelData")
    ClientLevel.ClientLevelData wormholes$crossingData();

    @Accessor("serverChunkRadius")
    int wormholes$crossingChunkRadius();
}
