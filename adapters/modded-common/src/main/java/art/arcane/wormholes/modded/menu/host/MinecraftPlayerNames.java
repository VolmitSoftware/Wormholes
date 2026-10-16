package art.arcane.wormholes.modded.menu.host;

import art.arcane.wormholes.modded.WormholesModRuntime;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.UserNameToIdResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;

final class MinecraftPlayerNames {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");

    private final WormholesModRuntime runtime;

    MinecraftPlayerNames(WormholesModRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    Optional<String> name(UUID playerId) {
        MinecraftServer server = runtime.server();
        ServerPlayer online = server.getPlayerList().getPlayer(playerId);
        if (online != null) {
            return Optional.of(online.getGameProfile().name());
        }
        return server.services().nameToIdCache().get(playerId).map(NameAndId::name);
    }

    CompletableFuture<Optional<UUID>> resolve(String name) {
        MinecraftServer server = runtime.server();
        ServerPlayer online = server.getPlayerList().getPlayerByName(name);
        if (online != null) {
            return CompletableFuture.completedFuture(Optional.of(online.getUUID()));
        }
        UserNameToIdResolver cache = server.services().nameToIdCache();
        CompletableFuture<Optional<UUID>> resolved = new CompletableFuture<>();
        CompletableFuture.supplyAsync(() -> cache.get(name).map(NameAndId::id), task -> Thread.ofVirtual().name("Wormholes-player-lookup").start(task))
            .whenComplete((identity, failure) -> complete(server, resolved, name, identity, failure));
        return resolved;
    }

    private static void complete(MinecraftServer server, CompletableFuture<Optional<UUID>> resolved, String name, Optional<UUID> identity,
                                 Throwable failure) {
        try {
            server.execute(() -> {
                if (failure != null) {
                    LOGGER.warn("Could not resolve player {}", name, failure);
                    resolved.complete(Optional.empty());
                    return;
                }
                resolved.complete(identity);
            });
        } catch (RejectedExecutionException stopping) {
            resolved.complete(Optional.empty());
        }
    }
}
