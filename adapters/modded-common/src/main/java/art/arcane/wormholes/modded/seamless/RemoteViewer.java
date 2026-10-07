package art.arcane.wormholes.modded.seamless;

import art.arcane.wormholes.modded.mixin.RemoteTrackedEntityAccess;
import art.arcane.optics.math.Vec3d;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

public final class RemoteViewer {
    private RemoteViewer() {
    }

    public static boolean visible(Sight sight) {
        if (sight.self() || sight.vanillaPaired() || !sight.broadcast() || !sight.delivered()) {
            return false;
        }
        double range = Math.min(sight.effectiveRange(), sight.windowRadius() * 16.0D);
        return sight.dx() * sight.dx() + sight.dz() * sight.dz() <= range * range;
    }

    public static void update(RemoteRoute route, RemoteTrackedEntityAccess tracked, ServerPlayer player, RoutedSends sends) {
        RemoteViewerConnection viewer = route.viewer();
        Entity entity = tracked.wormholesTrackedEntity();
        Set<ServerPlayerConnection> seenBy = tracked.wormholesSeenBy();
        if (viewer == null) {
            return;
        }
        boolean vanilla = seenBy.contains(player.connection);
        ChunkPos chunk = entity.chunkPosition();
        Vec3d anchor = route.anchor();
        boolean visible = route.window().contains(chunk.x(), chunk.z()) && visible(new Sight(entity == player, vanilla,
            entity.broadcastToPlayer(player), route.stream().delivered(chunk.pack()), entity.getX() - anchor.x(), entity.getZ() - anchor.z(),
            tracked.wormholesEffectiveRange(), route.window().radius()));
        if (visible) {
            pair(route, tracked, player, sends);
        } else if (seenBy.contains(viewer)) {
            unpair(route, tracked, player, sends, true);
        }
    }

    public static void pair(RemoteRoute route, RemoteTrackedEntityAccess tracked, ServerPlayer player, RoutedSends sends) {
        if (!tracked.wormholesSeenBy().add(route.viewer())) {
            return;
        }
        Entity entity = tracked.wormholesTrackedEntity();
        List<Packet<? super ClientGamePacketListener>> packets = new ArrayList<>();
        tracked.wormholesServerEntity().sendPairingData(player, packets::add);
        sends.send(route, new ClientboundBundlePacket(packets));
        entity.startSeenByPlayer(player);
        route.paired().add(entity.getId());
    }

    public static void unpair(RemoteRoute route, RemoteTrackedEntityAccess tracked, ServerPlayer player, RoutedSends sends, boolean notify) {
        if (!tracked.wormholesSeenBy().remove(route.viewer())) {
            return;
        }
        Entity entity = tracked.wormholesTrackedEntity();
        entity.stopSeenByPlayer(player);
        route.paired().remove(entity.getId());
        if (notify) {
            sends.send(route, new ClientboundRemoveEntitiesPacket(entity.getId()));
        }
    }

    public static void removed(RemoteTrackedEntityAccess tracked) {
        Set<ServerPlayerConnection> seenBy = tracked.wormholesSeenBy();
        if (seenBy.isEmpty()) {
            return;
        }
        Iterator<ServerPlayerConnection> iterator = seenBy.iterator();
        while (iterator.hasNext()) {
            if (iterator.next() instanceof RemoteViewerConnection viewer) {
                iterator.remove();
                Entity entity = tracked.wormholesTrackedEntity();
                entity.stopSeenByPlayer(viewer.getPlayer());
                viewer.route().paired().remove(entity.getId());
                viewer.send(new ClientboundRemoveEntitiesPacket(entity.getId()));
            }
        }
    }

    public record Sight(boolean self, boolean vanillaPaired, boolean broadcast, boolean delivered, double dx, double dz,
                        int effectiveRange, int windowRadius) {
    }
}
