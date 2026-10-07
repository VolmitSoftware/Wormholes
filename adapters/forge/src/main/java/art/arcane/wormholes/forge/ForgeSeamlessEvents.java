package art.arcane.wormholes.forge;

import art.arcane.wormholes.modded.seamless.SeamlessMove;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.ForgeEventFactory;

final class ForgeSeamlessEvents implements SeamlessMove.Events {
    @Override
    public boolean allowLevelChange(ServerPlayer player, ServerLevel destination) {
        return !ForgeEventFactory.onTravelToDimension(player, destination.dimension());
    }

    @Override
    public void levelChanged(ServerPlayer player, ServerLevel origin, ServerLevel destination) {
        ForgeEventFactory.onPlayerChangedDimension(player, origin.dimension(), destination.dimension());
    }
}
