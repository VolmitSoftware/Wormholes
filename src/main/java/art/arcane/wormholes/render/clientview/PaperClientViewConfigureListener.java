package art.arcane.wormholes.render.clientview;

import java.util.UUID;
import java.util.function.Function;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import com.github.retrooper.packetevents.protocol.player.User;

import io.papermc.paper.event.connection.configuration.AsyncPlayerConnectionConfigureEvent;

final class PaperClientViewConfigureListener implements Listener {
    private final BukkitClientViewNegotiator negotiator;
    private final Function<UUID, User> users;

    PaperClientViewConfigureListener(BukkitClientViewNegotiator negotiator, Function<UUID, User> users) {
        this.negotiator = negotiator;
        this.users = users;
    }

    @EventHandler
    public void on(AsyncPlayerConnectionConfigureEvent event) {
        UUID playerId = event.getConnection().getProfile().getId();
        if (playerId != null) {
            negotiator.configure(playerId, users.apply(playerId));
        }
    }
}
