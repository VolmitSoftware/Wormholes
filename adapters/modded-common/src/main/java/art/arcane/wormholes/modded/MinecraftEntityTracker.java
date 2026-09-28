package art.arcane.wormholes.modded;

import net.minecraft.server.level.ServerPlayer;

public interface MinecraftEntityTracker {
    void wormholesHide(ServerPlayer player);
    void wormholesShow(ServerPlayer player);
}
