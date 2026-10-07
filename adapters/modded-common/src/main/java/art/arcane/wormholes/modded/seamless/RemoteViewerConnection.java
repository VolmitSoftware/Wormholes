package art.arcane.wormholes.modded.seamless;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerPlayerConnection;

import java.util.Objects;

public final class RemoteViewerConnection implements ServerPlayerConnection {
    private final ServerPlayer player;
    private final RemoteRoute route;
    private final RoutedSends sends;

    public RemoteViewerConnection(ServerPlayer player, RemoteRoute route, RoutedSends sends) {
        this.player = Objects.requireNonNull(player, "player");
        this.route = Objects.requireNonNull(route, "route");
        this.sends = Objects.requireNonNull(sends, "sends");
    }

    public RemoteRoute route() {
        return route;
    }

    @Override
    public ServerPlayer getPlayer() {
        return player;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void send(Packet<?> packet) {
        sends.send(route, (Packet<? super ClientGamePacketListener>) packet);
    }
}
