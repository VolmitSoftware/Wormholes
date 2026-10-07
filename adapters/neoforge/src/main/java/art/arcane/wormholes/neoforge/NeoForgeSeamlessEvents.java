package art.arcane.wormholes.neoforge;

import art.arcane.wormholes.modded.seamless.SeamlessMove;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.event.EventHooks;

final class NeoForgeSeamlessEvents implements SeamlessMove.Events {
    @Override
    public boolean allowLevelChange(ServerPlayer player, ServerLevel destination) {
        return CommonHooks.onTravelToDimension(player, destination.dimension());
    }

    @Override
    public void levelChanged(ServerPlayer player, ServerLevel origin, ServerLevel destination) {
        EventHooks.firePlayerChangedDimensionEvent(player, origin.dimension(), destination.dimension());
    }
}
