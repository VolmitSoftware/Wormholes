package art.arcane.wormholes.modded.mixin;

import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerGamePacketListenerImpl.class)
public interface SeamlessListenerAccess {
    @Accessor("awaitingPositionFromClient")
    Vec3 wormholesAwaitingPosition();

    @Accessor("clientIsFloating")
    void wormholesClientFloating(boolean floating);
}
