package art.arcane.wormholes.modded;

import art.arcane.wormholes.api.traversal.TraversalKind;
import art.arcane.wormholes.api.traversal.TraversalText;
import art.arcane.wormholes.api.traversal.internal.TraversalCostEngine;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record MinecraftTraversalContext(UUID traversalId, TraversalKind kind, ServerPlayer traveler, UUID portalId,
                                        String portalName, Location origin, Optional<Destination> destination)
    implements TraversalCostEngine.Context<ServerPlayer> {
    public MinecraftTraversalContext {
        Objects.requireNonNull(traversalId);
        Objects.requireNonNull(kind);
        Objects.requireNonNull(traveler);
        Objects.requireNonNull(portalId);
        Objects.requireNonNull(origin);
        portalName = TraversalText.sanitize(portalName);
        destination = Objects.requireNonNull(destination);
    }

    @Override
    public UUID travelerId() {
        return traveler.getUUID();
    }

    public record Location(ServerLevel level, Vec3 position, float yaw, float pitch) {
        public Location {
            Objects.requireNonNull(level);
            Objects.requireNonNull(position);
        }

        public static Location of(ServerPlayer player) {
            return new Location(player.level(), player.position(), player.getYRot(), player.getXRot());
        }
    }

    public record Destination(String server, UUID portalId, Location location) {
        public Destination {
            server = server == null ? "" : server;
        }
    }
}
