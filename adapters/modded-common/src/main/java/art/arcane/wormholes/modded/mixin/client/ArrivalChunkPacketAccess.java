package art.arcane.wormholes.modded.mixin.client;

import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClientboundLevelChunkWithLightPacket.class)
public interface ArrivalChunkPacketAccess {
    @Accessor("x")
    int wormholes$arrivalX();

    @Accessor("z")
    int wormholes$arrivalZ();
}
