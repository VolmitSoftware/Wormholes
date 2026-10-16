package art.arcane.wormholes.menu.host;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.entity.Player;

import art.arcane.wormholes.platform.WormholesPlatform;

final class BukkitPlayerNames {
    private final Server server;

    BukkitPlayerNames(Server server) {
        this.server = Objects.requireNonNull(server, "server");
    }

    Optional<String> name(UUID playerId) {
        Player online = server.getPlayer(playerId);
        if (online != null) {
            return Optional.of(online.getName());
        }
        return Optional.ofNullable(server.getOfflinePlayer(playerId).getName());
    }

    CompletableFuture<Optional<UUID>> resolve(String name) {
        Player online = server.getPlayerExact(name);
        if (online != null) {
            return CompletableFuture.completedFuture(Optional.of(online.getUniqueId()));
        }
        OfflinePlayer cached = WormholesPlatform.offlinePlayerIfCached(name);
        return CompletableFuture.completedFuture(cached == null ? Optional.empty() : Optional.of(cached.getUniqueId()));
    }
}
