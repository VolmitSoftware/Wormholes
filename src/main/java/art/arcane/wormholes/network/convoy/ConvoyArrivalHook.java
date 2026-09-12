package art.arcane.wormholes.network.convoy;

import org.bukkit.entity.Player;

import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.Traversive;

/** Called by the arrival placer once a handed-off player has been placed at their exit portal. */
public interface ConvoyArrivalHook {
    void onPlayerPlaced(Player player, ILocalPortal exit, Traversive traversive);
}
