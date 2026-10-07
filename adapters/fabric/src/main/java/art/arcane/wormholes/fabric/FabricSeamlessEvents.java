package art.arcane.wormholes.fabric;

import art.arcane.wormholes.modded.seamless.SeamlessMove;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

final class FabricSeamlessEvents implements SeamlessMove.Events {
    @Override
    public boolean allowLevelChange(ServerPlayer player, ServerLevel destination) {
        return true;
    }

    @Override
    public void levelChanged(ServerPlayer player, ServerLevel origin, ServerLevel destination) {
        ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.invoker().afterChangeLevel(player, origin, destination);
    }
}
