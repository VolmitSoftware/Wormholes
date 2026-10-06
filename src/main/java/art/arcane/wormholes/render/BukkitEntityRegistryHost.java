package art.arcane.wormholes.render;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerAttachEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityHeadLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMoveAndRotation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRotation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import art.arcane.optics.entity.EntityOutput;
import art.arcane.optics.entity.EntityProfile;
import art.arcane.optics.entity.MapSnapshot;
import art.arcane.optics.entity.SnapshotProjector;
import art.arcane.optics.entity.SpoofRegistry;
import art.arcane.optics.entity.SpoofedEntity;
import art.arcane.optics.occlusion.LocalOcclusionArbiter;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.network.view.BukkitProjectedMapData;
import art.arcane.wormholes.render.view.ProjectionEntityView;

public final class BukkitEntityRegistryHost implements EntityOutput<Player, Vector3d, EntityType, ProjectionEntityView, Entity> {
    public static final Controller PLUGIN_VISIBILITY = new PluginVisibility();

    private final EntityRenderPacketChannel channel;
    private final Controller visibility;
    private final EntityRenderPlayerIdentity identity;
    private final EntityRenderMetadataBridge metadataBridge;

    BukkitEntityRegistryHost(EntityRenderPacketChannel channel, Controller visibility) {
        this.channel = channel;
        this.visibility = visibility;
        this.identity = new EntityRenderPlayerIdentity(channel, this);
        this.metadataBridge = new EntityRenderMetadataBridge(channel, this);
    }

    public static LocalOcclusionArbiter<Player, Entity> occlusion(Controller visibility) {
        return new LocalOcclusionArbiter<>(BukkitEntityVisualHost.FEED,
            new BukkitEntityRegistryHost(new EntityRenderPacketChannel(), visibility));
    }

    EntityRenderPacketChannel channel() {
        return channel;
    }

    EntityRenderPlayerIdentity identity() {
        return identity;
    }

    EntityRenderMetadataBridge metadataBridge() {
        return metadataBridge;
    }

    @Override
    public EntityType type(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        return EntityTypes.getByName(key);
    }

    @Override
    public boolean isItemFrame(EntityType type) {
        return type == EntityTypes.ITEM_FRAME || type == EntityTypes.GLOW_ITEM_FRAME;
    }

    @Override
    public boolean isHanging(EntityType type) {
        return isItemFrame(type) || type == EntityTypes.PAINTING;
    }

    @Override
    public boolean isLiving(EntityType type) {
        return livingType(type.getName().toString());
    }

    @Override
    public Vector3d position(double x, double y, double z) {
        return new Vector3d(x, y, z);
    }

    @Override
    public double x(Vector3d position) {
        return position.getX();
    }

    @Override
    public double y(Vector3d position) {
        return position.getY();
    }

    @Override
    public double z(Vector3d position) {
        return position.getZ();
    }

    @Override
    public void spawn(Player observer, SpoofedEntity state, SnapshotProjector.Spawn<Vector3d, EntityType> spawn) {
        channel.send(observer, new WrapperPlayServerSpawnEntity(state.fakeId, Optional.of(state.fakeUuid), spawn.type(),
            spawn.position(), spawn.pitch(), spawn.yaw(), spawn.yaw(), spawn.data(), Optional.of(spawn.velocity())));
    }

    @Override
    public void entityState(Player observer, SpoofedEntity state, SnapshotProjector.State<ProjectionEntityView> update) {
        metadataBridge.sendRemoteEntityState(observer, update.view(), update.visual(), state, update.metadataTransform(), update.initial());
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
    public void velocity(Player observer, int entityId, Vector3d velocity) {
        channel.send(observer, new WrapperPlayServerEntityVelocity(entityId, velocity));
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
    public void playerInfo(Player observer, SpoofedEntity state, EntityProfile profile) {
        identity.sendRemotePlayerInfo(observer, profile, state, state.upsideDown);
    }

    @Override
    public void removePlayerInfo(Player observer, List<UUID> ids) {
        channel.send(observer, new WrapperPlayServerPlayerInfoRemove(ids));
    }

    @Override
    public void label(Player observer, SpoofedEntity state, SnapshotProjector.Label<Vector3d> label, boolean initial) {
        if (initial) {
            identity.spawnPlayerLabel(observer, state, label.position(), label.height());
            return;
        }
        identity.updatePlayerLabelPosition(observer, state, label.position(), label.height());
        identity.updatePlayerLabelText(observer, state, label.profile());
    }

    @Override
    public void releaseName(Player observer, SpoofedEntity state) {
        identity.releaseVanillaNametag(observer, state);
    }

    @Override
    public void team(Player observer, TeamOp op, String team, String member) {
        channel.send(observer, switch (op) {
            case CREATE -> new WrapperPlayServerTeams(team, WrapperPlayServerTeams.TeamMode.CREATE, hiddenNameTeam(), List.of());
            case ADD -> new WrapperPlayServerTeams(team, WrapperPlayServerTeams.TeamMode.ADD_ENTITIES,
                (WrapperPlayServerTeams.ScoreBoardTeamInfo) null, member);
            case REMOVE -> new WrapperPlayServerTeams(team, WrapperPlayServerTeams.TeamMode.REMOVE_ENTITIES,
                (WrapperPlayServerTeams.ScoreBoardTeamInfo) null, member);
            case REMOVE_TEAM -> new WrapperPlayServerTeams(team, WrapperPlayServerTeams.TeamMode.REMOVE,
                (WrapperPlayServerTeams.ScoreBoardTeamInfo) null, List.of());
        });
    }

    @Override
    public void map(Player observer, MapSnapshot map, int virtualMapId) {
        channel.send(observer, BukkitProjectedMapData.toPacket(map, virtualMapId));
    }

    @Override
    public void hideLocal(Player observer, Entity entity) {
        visibility.hide(observer, entity);
    }

    @Override
    public void showLocal(Player observer, Entity entity) {
        visibility.show(observer, entity);
    }

    @Override
    public boolean online(Player observer) {
        return observer != null && observer.isOnline();
    }

    @Override
    public UUID id(Player observer) {
        return observer.getUniqueId();
    }

    @Override
    public boolean schedule(Player observer, Runnable task) {
        Wormholes plugin = Wormholes.instance;
        return plugin != null && FoliaScheduler.runEntity(plugin, observer, task, 1L);
    }

    @Override
    public void warning(Player observer, String context, RuntimeException error) {
        Wormholes plugin = Wormholes.instance;
        Logger logger = plugin == null ? Logger.getLogger("Wormholes") : plugin.getLogger();
        logger.log(Level.WARNING, "[spoof] " + context + " for " + (observer == null ? "unknown" : observer.getName()), error);
    }

    private static WrapperPlayServerTeams.ScoreBoardTeamInfo hiddenNameTeam() {
        return new WrapperPlayServerTeams.ScoreBoardTeamInfo(Component.empty(), Component.empty(), Component.empty(),
            WrapperPlayServerTeams.NameTagVisibility.NEVER, WrapperPlayServerTeams.CollisionRule.NEVER, NamedTextColor.WHITE,
            WrapperPlayServerTeams.OptionData.NONE);
    }

    private static boolean livingType(String typeKey) {
        if (typeKey == null || typeKey.isBlank()) {
            return false;
        }
        try {
            NamespacedKey key = NamespacedKey.fromString(typeKey.toLowerCase(Locale.ROOT));
            if (key == null) {
                return false;
            }
            Class<? extends Entity> entityClass = Optional.ofNullable(Registry.ENTITY_TYPE.get(key))
                .map(type -> type.getEntityClass()).orElse(null);
            return entityClass != null && LivingEntity.class.isAssignableFrom(entityClass);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public interface Controller {
        void hide(Player observer, Entity entity);

        void show(Player observer, Entity entity);
    }

    private static final class PluginVisibility implements Controller {
        @Override
        public void hide(Player observer, Entity entity) {
            observer.hideEntity(Wormholes.instance, entity);
        }

        @Override
        public void show(Player observer, Entity entity) {
            if (entity instanceof Item && !entity.isVisibleByDefault()) {
                observer.hideEntity(Wormholes.instance, entity);
            } else {
                observer.showEntity(Wormholes.instance, entity);
            }
        }
    }
}
