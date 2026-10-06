package art.arcane.wormholes.render;

import java.util.List;
import java.util.UUID;

import org.bukkit.entity.Player;

import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerAttachEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityHeadLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMoveAndRotation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRotation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.optics.entity.SpoofRegistry;
import art.arcane.optics.entity.SpoofedEntity;

final class BukkitEntityRegistryHost implements SpoofRegistry.Host<Player, Vector3d> {
    private final EntityRenderPacketChannel channel;
    private final EntityRenderPlayerIdentity identity;

    BukkitEntityRegistryHost(EntityRenderPacketChannel channel, EntityRenderPlayerIdentity identity) {
        this.channel = channel;
        this.identity = identity;
    }

    @Override
    public void motion(Player observer, SpoofRegistry.Motion<Vector3d> motion) {
        switch (motion.kind()) {
            case RELATIVE -> channel.send(observer, new WrapperPlayServerEntityRelativeMove(
                motion.entityId(), motion.deltaX(), motion.deltaY(), motion.deltaZ(), motion.onGround()));
            case RELATIVE_ROTATION -> channel.send(observer, new WrapperPlayServerEntityRelativeMoveAndRotation(
                motion.entityId(), motion.deltaX(), motion.deltaY(), motion.deltaZ(), motion.yaw(), motion.pitch(), motion.onGround()));
            case TELEPORT -> channel.send(observer, new WrapperPlayServerEntityTeleport(
                motion.entityId(), motion.position(), motion.yaw(), motion.pitch(), motion.onGround()));
            case ROTATION -> channel.send(observer, new WrapperPlayServerEntityRotation(
                motion.entityId(), motion.yaw(), motion.pitch(), motion.onGround()));
        }
    }

    @Override
    public void headLook(Player observer, int entityId, float yaw) {
        channel.send(observer, new WrapperPlayServerEntityHeadLook(entityId, yaw));
    }

    @Override
    public void passengers(Player observer, int entityId, int[] passengers) {
        channel.send(observer, new WrapperPlayServerSetPassengers(entityId, passengers));
    }

    @Override
    public void leash(Player observer, int entityId, int holderId) {
        channel.send(observer, new WrapperPlayServerAttachEntity(entityId, holderId, true));
    }

    @Override
    public void destroy(Player observer, int[] entityIds) {
        channel.send(observer, new WrapperPlayServerDestroyEntities(entityIds));
    }

    @Override
    public void removePlayerInfo(Player observer, List<UUID> playerIds) {
        channel.send(observer, new WrapperPlayServerPlayerInfoRemove(playerIds));
    }

    @Override
    public void releaseName(Player observer, SpoofedEntity state) {
        identity.releaseVanillaNametag(observer, state);
    }

    @Override
    public void culled(Player observer, UUID sourceId, SpoofedEntity state) {
        if (Settings.DEBUG) {
            Wormholes.v("[spoof] CULL " + (state.playerEntry ? "player" : "entity") + " src=" + sourceId + " fakeId=" + state.fakeId + " -> " + observer.getName() + " (no longer in view)");
        }
    }
}
