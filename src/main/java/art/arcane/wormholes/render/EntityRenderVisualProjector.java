package art.arcane.wormholes.render;

import java.util.Locale;
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

final class EntityRenderVisualProjector {
    private final EntityRenderPacketChannel channel;
    private final EntityRenderSpoofRegistry registry;
    private final EntityRenderPlayerIdentity identity;
    private final EntityRenderMetadataBridge metadataBridge;
    private final double[] scratchVisiblePoint;
    private final double[] scratchDirection;

    EntityRenderVisualProjector(EntityRenderPacketChannel channel,
                                EntityRenderSpoofRegistry registry,
                                EntityRenderPlayerIdentity identity,
                                EntityRenderMetadataBridge metadataBridge) {
        this.channel = channel;
        this.registry = registry;
        this.identity = identity;
        this.metadataBridge = metadataBridge;
        this.scratchVisiblePoint = new double[3];
        this.scratchDirection = new double[3];
    }

    boolean projectRemoteVisual(Player observer,
                                ILocalPortal localPortal,
                                double remoteOriginX,
                                double remoteOriginY,
                                double remoteOriginZ,
                                PortalFrame localViewFrame,
                                PortalFrame remoteViewFrame,
                                Frustum4D frustum,
                                ProjectionEntityView remoteView,
                                EntityVisual visual,
                                boolean upsideDown) {
        EntityType packetType = packetEntityTypeByKey(visual.typeKey());
        if (packetType == null) {
            return false;
        }

        Vector localOrigin = localPortal.getOrigin();
        boolean itemFrame = ProjectedItemFrameTransform.isItemFrame(packetType);
        double visibleY = itemFrame ? visual.y() : visual.y() + (visual.height() * 0.5D);
        PortalCoordMap.transformPointInto(visual.x(), visibleY, visual.z(),
            remoteOriginX, remoteOriginY, remoteOriginZ,
            localOrigin.getX(), localOrigin.getY(), localOrigin.getZ(),
            remoteViewFrame, localViewFrame, scratchVisiblePoint);

        if (!frustum.containsPrimitive(scratchVisiblePoint[0], scratchVisiblePoint[1], scratchVisiblePoint[2])) {
            return false;
        }

        remoteViewFrame.transformVectorInto(visual.lookX(), visual.lookY(), visual.lookZ(), localViewFrame, scratchDirection);
        float yaw = ProjectedEntityRenderer.yaw(scratchDirection[0], scratchDirection[2]);
        float pitch = ProjectedEntityRenderer.pitch(scratchDirection[0], scratchDirection[1], scratchDirection[2]);
        Direction sourceFacing = Direction.closest(visual.lookX(), visual.lookY(), visual.lookZ());
        int metadataTransform = itemFrame
            ? ProjectedItemFrameTransform.between(sourceFacing, remoteViewFrame, localViewFrame, scratchDirection)
            : ProjectedItemFrameTransform.NONE;
        Vector3d position;
        if (itemFrame) {
            position = ProjectedItemFrameTransform.betweenAnchor(
                visual.x(), visual.y(), visual.z(),
                remoteOriginX, remoteOriginY, remoteOriginZ,
                localOrigin.getX(), localOrigin.getY(), localOrigin.getZ(),
                remoteViewFrame, localViewFrame, scratchVisiblePoint);
        } else {
            double visualBaseY = scratchVisiblePoint[1] - (visual.height() * 0.5D);
            position = new Vector3d(scratchVisiblePoint[0], visualBaseY, scratchVisiblePoint[2]);
        }
        remoteViewFrame.transformVectorInto(visual.velocityX(), visual.velocityY(), visual.velocityZ(), localViewFrame, scratchDirection);
        Vector3d velocity = new Vector3d(scratchDirection[0], scratchDirection[1], scratchDirection[2]);

        EntityRenderSpoofedEntity state = registry.get(visual.id());
        if (state != null && state.upsideDown != upsideDown) {
            registry.destroySingle(observer, visual.id(), state);
            state = null;
        }
        if (state == null) {
            state = EntityRenderSpoofedEntity.create(visual.isPlayer(), upsideDown,
                visual.isPlayer() || isLivingType(visual.typeKey()));
            registry.track(visual.id(), state);
            if (visual.isPlayer()) {
                identity.sendRemotePlayerInfo(observer, remoteView.getProfile(visual.id()), state, upsideDown);
            }
            WrapperPlayServerSpawnEntity spawn = new WrapperPlayServerSpawnEntity(state.fakeId, Optional.of(state.fakeUuid),
                packetType, position, pitch, yaw, yaw, ProjectedItemFrameTransform.spawnData(metadataTransform), Optional.of(velocity));
            channel.send(observer, spawn);
            identity.spawnPlayerLabel(observer, state, position, visual.height());
            state.updateRotation(yaw, pitch);
            state.updateMetadataTransform(metadataTransform);
            state.rememberPosition(position);
            registry.syncHeadLook(observer, state, yaw);
            state.remoteStateVersion = remoteView.getStateVersion(visual.id());
            metadataBridge.sendRemoteEntityState(observer, remoteView, visual, state, metadataTransform, true);
            state.resetMapCooldown();
            if (Settings.DEBUG) {
                Wormholes.v("[spoof] SPAWN " + (visual.isPlayer() ? "player" : "entity") + " src=" + visual.id() + " type=" + visual.typeKey() + " fakeId=" + state.fakeId + " -> " + observer.getName());
            }
            return true;
        }

        EntityRenderSpoofedEntity.Move move = state.updatePosition(position);
        boolean rotationChanged = state.updateRotation(yaw, pitch);
        boolean metadataTransformChanged = state.updateMetadataTransform(metadataTransform);
        registry.syncMotion(observer, state, move, rotationChanged, position, yaw, pitch, visual.onGround());
        identity.updatePlayerLabelPosition(observer, state, position, visual.height());
        identity.updatePlayerLabelText(observer, state, remoteView.getProfile(visual.id()));
        if (rotationChanged) {
            registry.syncHeadLook(observer, state, yaw);
        }
        if (state.updateVelocity(velocity)) {
            channel.send(observer, new WrapperPlayServerEntityVelocity(state.fakeId, velocity));
        }
        int stateVersion = remoteView.getStateVersion(visual.id());
        boolean mapRefreshDue = itemFrame && remoteView.getMapView(visual.id()) != null && state.shouldRefreshMap();
        if (stateVersion != state.remoteStateVersion || metadataTransformChanged || mapRefreshDue) {
            state.remoteStateVersion = stateVersion;
            metadataBridge.sendRemoteEntityState(observer, remoteView, visual, state, metadataTransform, false);
            state.resetMapCooldown();
        }
        return true;
    }

    boolean projectSnapshotVisual(Player observer,
                                  ILocalPortal localPortal,
                                  double remoteOriginX,
                                  double remoteOriginY,
                                  double remoteOriginZ,
                                  PortalFrame localViewFrame,
                                  PortalFrame remoteViewFrame,
                                  Frustum4D frustum,
                                  ProjectionEntityView entityView,
                                  EntityVisual visual,
                                  boolean upsideDown,
                                  boolean mirror,
                                  int mirrorRotationQuarterTurns) {
        EntityType packetType = packetEntityTypeByKey(visual.typeKey());
        if (packetType == null) {
            return false;
        }

        Vector localOrigin = localPortal.getOrigin();
        boolean itemFrame = ProjectedItemFrameTransform.isItemFrame(packetType);
        double visibleY = itemFrame ? visual.y() : visual.y() + (visual.height() * 0.5D);
        if (mirror) {
            PortalCoordMap.mirrorSourceToDisplayPointInto(visual.x(), visibleY, visual.z(),
                remoteOriginX, remoteOriginY, remoteOriginZ, localPortal.getFrame(), mirrorRotationQuarterTurns,
                scratchVisiblePoint);
        } else {
            PortalCoordMap.transformPointInto(visual.x(), visibleY, visual.z(),
                remoteOriginX, remoteOriginY, remoteOriginZ,
                localOrigin.getX(), localOrigin.getY(), localOrigin.getZ(),
                remoteViewFrame, localViewFrame, scratchVisiblePoint);
        }
        if (!frustum.containsPrimitive(scratchVisiblePoint[0], scratchVisiblePoint[1], scratchVisiblePoint[2])) {
            return false;
        }

        if (mirror) {
            PortalCoordMap.mirrorSourceToDisplayVectorInto(visual.lookX(), visual.lookY(), visual.lookZ(),
                localPortal.getFrame(), mirrorRotationQuarterTurns, scratchDirection);
        } else {
            remoteViewFrame.transformVectorInto(visual.lookX(), visual.lookY(), visual.lookZ(), localViewFrame, scratchDirection);
        }
        float yaw = ProjectedEntityRenderer.yaw(scratchDirection[0], scratchDirection[2]);
        float pitch = ProjectedEntityRenderer.pitch(scratchDirection[0], scratchDirection[1], scratchDirection[2]);
        Direction sourceFacing = Direction.closest(visual.lookX(), visual.lookY(), visual.lookZ());
        int metadataTransform = ProjectedItemFrameTransform.NONE;
        if (itemFrame) {
            metadataTransform = mirror
                ? ProjectedItemFrameTransform.mirror(sourceFacing, localPortal.getFrame(),
                    mirrorRotationQuarterTurns, scratchDirection)
                : ProjectedItemFrameTransform.between(sourceFacing, remoteViewFrame, localViewFrame,
                    scratchDirection);
        }
        Vector3d position;
        if (itemFrame && mirror) {
            position = ProjectedItemFrameTransform.mirrorAnchor(
                visual.x(), visual.y(), visual.z(),
                remoteOriginX, remoteOriginY, remoteOriginZ,
                localPortal.getFrame(), mirrorRotationQuarterTurns, scratchVisiblePoint);
        } else if (itemFrame) {
            position = ProjectedItemFrameTransform.betweenAnchor(
                visual.x(), visual.y(), visual.z(),
                remoteOriginX, remoteOriginY, remoteOriginZ,
                localOrigin.getX(), localOrigin.getY(), localOrigin.getZ(),
                remoteViewFrame, localViewFrame, scratchVisiblePoint);
        } else {
            double visualBaseY = scratchVisiblePoint[1] - (visual.height() * 0.5D);
            position = new Vector3d(scratchVisiblePoint[0], visualBaseY, scratchVisiblePoint[2]);
        }
        if (mirror) {
            PortalCoordMap.mirrorSourceToDisplayVectorInto(visual.velocityX(), visual.velocityY(), visual.velocityZ(),
                localPortal.getFrame(), mirrorRotationQuarterTurns, scratchDirection);
        } else {
            remoteViewFrame.transformVectorInto(visual.velocityX(), visual.velocityY(), visual.velocityZ(), localViewFrame, scratchDirection);
        }
        Vector3d velocity = new Vector3d(scratchDirection[0], scratchDirection[1], scratchDirection[2]);

        EntityRenderSpoofedEntity state = registry.get(visual.id());
        if (state != null && state.upsideDown != upsideDown) {
            registry.destroySingle(observer, visual.id(), state);
            state = null;
        }
        if (state == null) {
            state = EntityRenderSpoofedEntity.create(visual.isPlayer(), upsideDown,
                visual.isPlayer() || isLivingType(visual.typeKey()));
            registry.track(visual.id(), state);
            if (visual.isPlayer()) {
                identity.sendRemotePlayerInfo(observer, entityView.getProfile(visual.id()), state, upsideDown);
            }
            WrapperPlayServerSpawnEntity spawn = new WrapperPlayServerSpawnEntity(state.fakeId, Optional.of(state.fakeUuid),
                packetType, position, pitch, yaw, yaw, ProjectedItemFrameTransform.spawnData(metadataTransform), Optional.of(velocity));
            channel.send(observer, spawn);
            identity.spawnPlayerLabel(observer, state, position, visual.height());
            state.updateRotation(yaw, pitch);
            state.updateMetadataTransform(metadataTransform);
            state.rememberPosition(position);
            registry.syncHeadLook(observer, state, yaw);
            state.remoteStateVersion = entityView.getStateVersion(visual.id());
            metadataBridge.sendRemoteEntityState(observer, entityView, visual, state, metadataTransform, true);
            state.resetMapCooldown();
            return true;
        }

        EntityRenderSpoofedEntity.Move move = state.updatePosition(position);
        boolean rotationChanged = state.updateRotation(yaw, pitch);
        boolean metadataTransformChanged = state.updateMetadataTransform(metadataTransform);
        registry.syncMotion(observer, state, move, rotationChanged, position, yaw, pitch, visual.onGround());
        identity.updatePlayerLabelPosition(observer, state, position, visual.height());
        identity.updatePlayerLabelText(observer, state, entityView.getProfile(visual.id()));
        if (rotationChanged) {
            registry.syncHeadLook(observer, state, yaw);
        }
        if (state.updateVelocity(velocity)) {
            channel.send(observer, new WrapperPlayServerEntityVelocity(state.fakeId, velocity));
        }
        int stateVersion = entityView.getStateVersion(visual.id());
        boolean mapRefreshDue = itemFrame && entityView.getMapView(visual.id()) != null && state.shouldRefreshMap();
        if (stateVersion != state.remoteStateVersion || metadataTransformChanged || mapRefreshDue) {
            state.remoteStateVersion = stateVersion;
            metadataBridge.sendRemoteEntityState(observer, entityView, visual, state, metadataTransform, false);
            state.resetMapCooldown();
        }
        return true;
    }

    private EntityType packetEntityTypeByKey(String typeKey) {
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
