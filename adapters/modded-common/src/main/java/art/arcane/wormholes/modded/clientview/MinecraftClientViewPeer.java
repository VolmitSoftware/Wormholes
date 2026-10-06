package art.arcane.wormholes.modded.clientview;

import art.arcane.wormholes.modded.MinecraftProjectorPortalAccess;
import art.arcane.wormholes.modded.MinecraftDoorProjectionViews;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.stream.ProjectionEnvironment;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Objects;
import java.util.List;
import java.util.UUID;
import java.util.HashMap;
import java.util.Map;

public final class MinecraftClientViewPeer {
    private final UUID id;
    private final String name;
    private final Connection connection;
    private volatile ServerPlayer player;
    private ServerLevel world;
    private MinecraftProjectorPortalAccess portals;
    private MinecraftDoorProjectionViews doors;
    private volatile boolean offered;
    private int meshDepth;
    private final Map<UUID, NestedContext> nestedContexts = new HashMap<>();

    public MinecraftClientViewPeer(UUID id, String name, Connection connection) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = name == null ? id.toString() : name;
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    NestedContext nestedContext(UUID context) {
        return nestedContexts.get(context);
    }

    void nestedContext(UUID context, NestedContext value) {
        if (value == null) {
            nestedContexts.remove(context);
        } else {
            nestedContexts.put(context, value);
        }
    }

    int meshDepth() {
        return meshDepth;
    }

    void meshDepth(int depth) {
        meshDepth = depth;
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

    List<MinecraftPortal> updateDoors(WormholesModRuntime runtime) {
        if (doors == null) {
            doors = new MinecraftDoorProjectionViews(runtime);
            portals.setDoorViews(doors);
        }
        return doors.update(player, runtime.projections().projectableDoors(), meshDepth > 0);
    }

    MinecraftPortal door(UUID id) {
        return doors == null ? null : doors.source(id);
    }

    List<MinecraftPortal> doors() {
        return doors == null ? List.of() : doors.sources();
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
        doors = null;
        nestedContexts.clear();
        player = Objects.requireNonNull(next, "next");
        world = next.level();
        portals = Objects.requireNonNull(access, "access");
        access.setDoorViews(null);
        access.observer(next);
    }

    record NestedContext(UUID portal, Vec3d sourceEye, Vec3d destinationEye, ServerLevel destinationWorld,
                         ProjectionEnvironment.Transform transform) {
    }

    boolean connected() {
        return connection.isConnected() || connection.isConnecting();
    }
}
