package art.arcane.wormholes.modded.clientview;

import art.arcane.wormholes.modded.MinecraftProjectorPortalAccess;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.network.client.ClientViewMessage;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Objects;
import java.util.UUID;

public final class MinecraftClientViewPeer {
    private final UUID id;
    private final String name;
    private final Connection connection;
    private volatile ServerPlayer player;
    private ServerLevel world;
    private MinecraftProjectorPortalAccess portals;
    private volatile boolean offered;

    public MinecraftClientViewPeer(UUID id, String name, Connection connection) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = name == null ? id.toString() : name;
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public Connection connection() {
        return connection;
    }

    public ServerPlayer player() {
        return player;
    }

    public ServerLevel world() {
        return world;
    }

    public MinecraftProjectorPortalAccess portals() {
        return portals;
    }

    public boolean offered() {
        return offered;
    }

    void markOffered() {
        offered = true;
    }

    ClientViewMessage.ResetReason follow(ServerPlayer next, WormholesModRuntime runtime) {
        ServerPlayer previous = player;
        ServerLevel departed = world;
        if (previous == next && departed == next.level()) {
            return null;
        }
        attach(next, previous == next ? portals : new MinecraftProjectorPortalAccess(runtime));
        if (previous == null) {
            return null;
        }
        return departed == world ? ClientViewMessage.ResetReason.RESPAWN : ClientViewMessage.ResetReason.DIMENSION;
    }

    void attach(ServerPlayer next, MinecraftProjectorPortalAccess access) {
        player = Objects.requireNonNull(next, "next");
        world = next.level();
        portals = Objects.requireNonNull(access, "access");
        access.observer(next);
    }

    boolean connected() {
        return connection.isConnected() || connection.isConnecting();
    }
}
