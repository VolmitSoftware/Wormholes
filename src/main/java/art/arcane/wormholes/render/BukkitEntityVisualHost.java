package art.arcane.wormholes.render;

import org.bukkit.World;
import art.arcane.wormholes.geometry.GeometryVector;
import java.util.Locale;
import java.util.UUID;
import java.util.List;
import art.arcane.wormholes.network.view.RemoteViewCache.RemoteProfile;
import java.util.Optional;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.network.view.EntityVisual;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.render.view.ProjectionEntityView;
import art.arcane.wormholes.util.Direction;

final class BukkitEntityVisualHost implements EntityRenderVisualProjector.Host<Player, Vector3d, EntityType, ProjectionEntityView> {
    private final EntityRenderPacketChannel channel;
    private final EntityRenderPlayerIdentity identity;
    private final EntityRenderMetadataBridge metadataBridge;

    BukkitEntityVisualHost(EntityRenderPacketChannel channel, Options options) {
        this.channel = channel;
        this.identity = options.identity();
        this.metadataBridge = options.metadataBridge();
    }

    public record Options(EntityRenderPlayerIdentity identity, EntityRenderMetadataBridge metadataBridge) {
    }

    @Override
    public List<EntityVisual> entities(ProjectionEntityView view, EntityRenderVisualProjector.EntityRange range) {
        return view.getEntities(range.x(), range.y(), range.z(), range.range());
    }

    @Override
    public boolean visible(Player observer, ProjectionEntityView view, UUID entityId) {
        return view.isVisibleTo(observer, entityId);
    }

    @Override
    public boolean isItemFrame(EntityType type) { return BukkitItemFrameMetadata.isItemFrame(type); }

    @Override
    public boolean isHanging(EntityType type) { return BukkitItemFrameMetadata.isHanging(type); }

    @Override
    public boolean isLiving(EntityType type) { return isLivingType(type.getName().toString()); }

    @Override
    public Vector3d position(double x, double y, double z) { return new Vector3d(x, y, z); }

    @Override
    public double x(Vector3d position) { return position.getX(); }

    @Override
    public double y(Vector3d position) { return position.getY(); }

    @Override
    public double z(Vector3d position) { return position.getZ(); }

    @Override
    public RemoteProfile profile(ProjectionEntityView view, UUID entityId) { return view.getProfile(entityId); }

    @Override
    public int stateVersion(ProjectionEntityView view, UUID entityId) { return view.getStateVersion(entityId); }

    @Override
    public boolean hasMap(ProjectionEntityView view, UUID entityId) { return view.getMapView(entityId) != null; }

    @Override
    public void playerInfo(Player observer, EntityRenderSpoofedEntity state, RemoteProfile profile) {
        identity.sendRemotePlayerInfo(observer, profile, state, state.upsideDown);
    }

    @Override
    public void spawn(Player observer, EntityRenderSpoofedEntity state, EntityRenderVisualProjector.Spawn<Vector3d, EntityType> spawn) {
        channel.send(observer, new WrapperPlayServerSpawnEntity(state.fakeId, Optional.of(state.fakeUuid), spawn.type(),
            spawn.position(), spawn.pitch(), spawn.yaw(), spawn.yaw(), spawn.data(), Optional.of(spawn.velocity())));
    }

    @Override
    public void spawnLabel(Player observer, EntityRenderSpoofedEntity state, EntityRenderVisualProjector.Label<Vector3d> label) {
        identity.spawnPlayerLabel(observer, state, label.position(), label.height());
    }

    @Override
    public void updateLabel(Player observer, EntityRenderSpoofedEntity state, EntityRenderVisualProjector.Label<Vector3d> label) {
        identity.updatePlayerLabelPosition(observer, state, label.position(), label.height());
        identity.updatePlayerLabelText(observer, state, label.profile());
    }

    @Override
    public void entityState(Player observer, EntityRenderSpoofedEntity state, EntityRenderVisualProjector.State<ProjectionEntityView> update) {
        metadataBridge.sendRemoteEntityState(observer, update.view(), update.visual(), state, update.metadataTransform(), update.initial());
    }

    @Override
    public void velocity(Player observer, int entityId, Vector3d velocity) {
        channel.send(observer, new WrapperPlayServerEntityVelocity(entityId, velocity));
    }

    public EntityType packetType(String typeKey) {
        if (typeKey == null || typeKey.isBlank()) {
            return null;
        }
        return EntityTypes.getByName(typeKey);
    }

    private static boolean isLivingType(String typeKey) {
        if (typeKey == null || typeKey.isBlank()) {
            return false;
        }
        try {
            NamespacedKey key = NamespacedKey.fromString(typeKey.toLowerCase(Locale.ROOT));
            if (key == null) {
                return false;
            }
            org.bukkit.entity.EntityType bukkitType = org.bukkit.Registry.ENTITY_TYPE.get(key);
            if (bukkitType == null) {
                return false;
            }
            Class<? extends Entity> entityClass = bukkitType.getEntityClass();
            return entityClass != null && LivingEntity.class.isAssignableFrom(entityClass);
        } catch (Throwable ignored) {
            return false;
        }
    }
}
