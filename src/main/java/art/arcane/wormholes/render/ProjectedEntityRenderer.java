package art.arcane.wormholes.render;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.logging.Level;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityAnimation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityAnimation.EntityAnimationType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerHurtAnimation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import art.arcane.wormholes.EffectManager;
import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.network.view.EntityVisual;
import art.arcane.wormholes.platform.WormholesPlatform;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.IPortal;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.render.view.ProjectionEntityView;
import art.arcane.wormholes.render.view.RemoteWorldView;
import art.arcane.wormholes.util.Direction;

public final class ProjectedEntityRenderer {
    static final String FLIP_NAME = "Dinnerbone";
    private static final String FLIP_NAME_ALT = "Grumm";
    private static final String NEUTRAL_PROFILE_NAME = "PortalPlayer";
    private static final int DISPLAY_POSITION_ROTATION_INTERPOLATION_INDEX = 10;
    private static final int DISPLAY_BILLBOARD_INDEX = 15;
    private static final int DISPLAY_BRIGHTNESS_INDEX = 16;
    private static final int TEXT_DISPLAY_TEXT_INDEX = 23;
    private static final int TEXT_DISPLAY_BACKGROUND_INDEX = 25;
    private static final byte CENTER_BILLBOARD = 3;
    private static final int FULL_BRIGHT = (15 << 4) | (15 << 20);
    private static final int LABEL_INTERPOLATION_TICKS = 3;
    private static final double LABEL_VERTICAL_MARGIN = 0.5D;

    private final EntityRenderPacketChannel channel;
    private final EntityRenderPlayerIdentity identity;
    private final EntityRenderSpoofRegistry registry;
    private final EntityRenderMetadataBridge metadataBridge;
    private final EntityRenderLocalOccluder occluder;
    private final EntityRenderVisualProjector visualProjector;
    private final Map<NamespacedKey, EntityType> entityTypeCache;
    private final double[] scratchVisiblePoint;
    private final double[] scratchDirection;
    private final double[] scratchLook;
    private final double[] scratchEntityPosition;
    private final AtomicBoolean teardownRetryScheduled;
    private final AtomicBoolean teardownFailureReported;
    private volatile boolean recoveryPending;
    private volatile int publishedSpoofedCount;

    public ProjectedEntityRenderer() {
        this(new EntityRenderPacketChannel());
    }

    private ProjectedEntityRenderer(EntityRenderPacketChannel channel) {
        this(channel, new EntityRenderPlayerIdentity(channel));
    }

    private ProjectedEntityRenderer(EntityRenderPacketChannel channel, EntityRenderPlayerIdentity identity) {
        this(channel, identity, new EntityRenderSpoofRegistry(channel, identity));
    }

    ProjectedEntityRenderer(EntityRenderPacketChannel channel, EntityRenderPlayerIdentity identity, EntityRenderSpoofRegistry registry) {
        this(channel, identity, registry, new EntityRenderLocalOcclusionArbiter(), UUID.randomUUID());
    }

    ProjectedEntityRenderer(EntityRenderLocalOcclusionArbiter localOcclusion, UUID localOcclusionOwnerId) {
        this(new EntityRenderPacketChannel(), localOcclusion, localOcclusionOwnerId);
    }

    private ProjectedEntityRenderer(EntityRenderPacketChannel channel,
                                    EntityRenderLocalOcclusionArbiter localOcclusion,
                                    UUID localOcclusionOwnerId) {
        this(channel, new EntityRenderPlayerIdentity(channel), localOcclusion, localOcclusionOwnerId);
    }

    private ProjectedEntityRenderer(EntityRenderPacketChannel channel,
                                    EntityRenderPlayerIdentity identity,
                                    EntityRenderLocalOcclusionArbiter localOcclusion,
                                    UUID localOcclusionOwnerId) {
        this(channel, identity, new EntityRenderSpoofRegistry(channel, identity), localOcclusion,
            localOcclusionOwnerId);
    }

    private ProjectedEntityRenderer(EntityRenderPacketChannel channel,
                                    EntityRenderPlayerIdentity identity,
                                    EntityRenderSpoofRegistry registry,
                                    EntityRenderLocalOcclusionArbiter localOcclusion,
                                    UUID localOcclusionOwnerId) {
        this.channel = channel;
        this.identity = identity;
        this.registry = registry;
        this.metadataBridge = new EntityRenderMetadataBridge(channel);
        this.occluder = new EntityRenderLocalOccluder(localOcclusion, localOcclusionOwnerId);
        this.visualProjector = new EntityRenderVisualProjector(channel, registry, identity, this.metadataBridge);
        this.entityTypeCache = new HashMap<NamespacedKey, EntityType>(32);
        this.scratchVisiblePoint = new double[3];
        this.scratchDirection = new double[3];
        this.scratchLook = new double[3];
        this.scratchEntityPosition = new double[5];
        this.teardownRetryScheduled = new AtomicBoolean(false);
        this.teardownFailureReported = new AtomicBoolean(false);
        this.recoveryPending = false;
    }

    public int getSpoofedCount() {
        return publishedSpoofedCount;
    }

    public void apply(Player observer,
                      ILocalPortal localPortal,
                      ILocalPortal remotePortal,
                      Frustum4D frustum,
                      double projectionDepth,
                      PortalFrame localViewFrame,
                      PortalFrame remoteViewFrame,
                      int mirrorRotationQuarterTurns,
                      ProjectedEntityOcclusion entityOcclusion) {
        if (!Settings.ENTITY_SPOOFING || Settings.MAX_SPOOFED_ENTITIES <= 0) {
            close(observer);
            return;
        }

        Location remoteCenter = remotePortal.getCenter();
        World remoteWorld = remotePortal.getWorld();
        if (observer == null || remoteCenter == null || remoteWorld == null) {
            close(observer);
            return;
        }

        prepareRenderBatch(observer);
        RuntimeException batchFailure = null;
        try {
            double range = Math.min(Settings.ENTITY_SPOOF_RANGE, projectionDepth);
            registry.clearVisible();
            entityOcclusion.startBatch();
            occluder.hideLocalEntities(observer, localPortal, frustum, projectionDepth);
            boolean upsideDown = remotePortal == localPortal
                ? PortalCoordMap.mirrorTransformFlipsWorldUp(localPortal.getFrame(), mirrorRotationQuarterTurns)
                : PortalCoordMap.transformFlipsWorldUp(remoteViewFrame, localViewFrame);
            int count = 0;

            for (Entity entity : EntityRenderCaches.nearbyRemoteEntities(remotePortal, remoteCenter, range)) {
                if (count >= Settings.MAX_SPOOFED_ENTITIES) {
                    break;
                }
                if (!canSpoof(entity)) {
                    continue;
                }
                if (entityOcclusion.fullyHidden(entity.getBoundingBox())) {
                    continue;
                }
                if (!projectEntity(observer, localPortal, remotePortal, localViewFrame, remoteViewFrame, frustum,
                    entity, upsideDown, mirrorRotationQuarterTurns)) {
                    continue;
                }
                registry.markVisible(entity.getUniqueId());
                count++;
            }

            registry.destroyHidden(observer);
        } catch (RuntimeException error) {
            batchFailure = error;
            throw error;
        } finally {
            finishRenderBatch(observer, batchFailure);
        }
    }

    public void applyRemote(Player observer,
                            ILocalPortal localPortal,
                            double remoteOriginX,
                            double remoteOriginY,
                            double remoteOriginZ,
                            RemoteWorldView remoteView,
                            Frustum4D frustum,
                            double projectionDepth,
                            PortalFrame localViewFrame,
                            PortalFrame remoteViewFrame,
                            ProjectedEntityOcclusion entityOcclusion) {
        if (!Settings.ENTITY_SPOOFING || Settings.MAX_SPOOFED_ENTITIES <= 0) {
            close(observer);
            return;
        }
        if (observer == null) {
            close(observer);
            return;
        }

        prepareRenderBatch(observer);
        RuntimeException batchFailure = null;
        try {
            double range = Math.min(Settings.ENTITY_SPOOF_RANGE, projectionDepth);
            registry.clearVisible();
            entityOcclusion.startBatch();
            occluder.hideLocalEntities(observer, localPortal, frustum, projectionDepth);
            boolean upsideDown = PortalCoordMap.transformFlipsWorldUp(remoteViewFrame, localViewFrame);
            int count = 0;

            List<EntityVisual> visuals = remoteView.getEntities();
            for (EntityVisual visual : visuals) {
                if (count >= Settings.MAX_SPOOFED_ENTITIES) {
                    break;
                }
                if (entityOcclusion.fullyHidden(visual)) {
                    continue;
                }
                if (!visualProjector.projectRemoteVisual(observer, localPortal, remoteOriginX, remoteOriginY, remoteOriginZ, localViewFrame, remoteViewFrame, frustum, remoteView, visual, upsideDown)) {
                    continue;
                }
                registry.markVisible(visual.id());
                count++;
            }

            registry.destroyHidden(observer);
            registry.applyRelationships(observer, visuals);
        } catch (RuntimeException error) {
            batchFailure = error;
            throw error;
        } finally {
            finishRenderBatch(observer, batchFailure);
        }
    }

    public void applySnapshot(Player observer,
                              ILocalPortal localPortal,
                              IPortal remotePortal,
                              boolean mirror,
                              int mirrorRotationQuarterTurns,
                              ProjectionEntityView entityView,
                              Frustum4D frustum,
                              double projectionDepth,
                              PortalFrame localViewFrame,
                              PortalFrame remoteViewFrame,
                              ProjectedEntityOcclusion entityOcclusion) {
        if (!Settings.ENTITY_SPOOFING || Settings.MAX_SPOOFED_ENTITIES <= 0) {
            close(observer);
            return;
        }
        if (observer == null || remotePortal == null || entityView == null) {
            close(observer);
            return;
        }

        double remoteOriginX = remotePortal.getOrigin().getX();
        double remoteOriginY = remotePortal.getOrigin().getY();
        double remoteOriginZ = remotePortal.getOrigin().getZ();
        prepareRenderBatch(observer);
        RuntimeException batchFailure = null;
        try {
            double range = Math.min(Settings.ENTITY_SPOOF_RANGE, projectionDepth);
            registry.clearVisible();
            entityOcclusion.startBatch();
            occluder.hideLocalEntities(observer, localPortal, frustum, projectionDepth);
            boolean upsideDown = mirror
                ? PortalCoordMap.mirrorTransformFlipsWorldUp(localPortal.getFrame(), mirrorRotationQuarterTurns)
                : PortalCoordMap.transformFlipsWorldUp(remoteViewFrame, localViewFrame);
            int count = 0;
            List<EntityVisual> visuals = entityView.getEntities(remoteOriginX, remoteOriginY, remoteOriginZ, range);
            for (EntityVisual visual : visuals) {
                if (count >= Settings.MAX_SPOOFED_ENTITIES) {
                    break;
                }
                if (entityOcclusion.fullyHidden(visual)) {
                    continue;
                }
                if (!visualProjector.projectSnapshotVisual(observer, localPortal, remoteOriginX, remoteOriginY, remoteOriginZ,
                    localViewFrame, remoteViewFrame, frustum, entityView, visual, upsideDown, mirror,
                    mirrorRotationQuarterTurns)) {
                    continue;
                }
                registry.markVisible(visual.id());
                count++;
            }
            registry.destroyHidden(observer);
            registry.applyRelationships(observer, visuals);
        } catch (RuntimeException error) {
            batchFailure = error;
            throw error;
        } finally {
            finishRenderBatch(observer, batchFailure);
        }
    }

    public void close(Player observer) {
        teardown(observer);
    }

    public void discard(Player observer) {
        teardown(observer);
    }

    private void teardown(Player observer) {
        if (observer == null || !observer.isOnline()) {
            dropRenderState(observer);
            occluder.release(observer);
            return;
        }
        if (!recoveryPending && registry.size() == 0 && !identity.hasVanillaNameTeam()) {
            occluder.release(observer);
            return;
        }
        try {
            sendTeardown(observer);
            dropRenderState(observer);
        } catch (RuntimeException error) {
            reportTeardownFailure(observer, error);
            scheduleTeardownRetry(observer);
        } finally {
            occluder.release(observer);
        }
    }

    private void sendTeardown(Player observer) {
        try {
            channel.begin(observer);
        } catch (RuntimeException error) {
            markRecoveryPending(observer);
            throw error;
        }
        RuntimeException batchFailure = null;
        try {
            registry.destroyAll(observer);
            identity.sendVanillaNameTeamRemoval(observer);
        } catch (RuntimeException error) {
            batchFailure = error;
            throw error;
        } finally {
            finishRenderBatch(observer, batchFailure);
        }
        identity.forgetVanillaNameTeam();
        recoveryPending = false;
        teardownFailureReported.set(false);
    }

    private void prepareRenderBatch(Player observer) {
        if (recoveryPending) {
            sendTeardown(observer);
        }
        try {
            channel.begin(observer);
        } catch (RuntimeException error) {
            markRecoveryPending(observer);
            throw error;
        }
    }

    private void finishRenderBatch(Player observer, RuntimeException batchFailure) {
        RuntimeException flushFailure = null;
        try {
            channel.end();
        } catch (RuntimeException error) {
            flushFailure = error;
        }
        if (batchFailure == null && flushFailure == null) {
            registry.commitDestroyed();
        } else {
            markRecoveryPending(observer);
        }
        publishedSpoofedCount = registry.size();
        if (batchFailure != null) {
            if (flushFailure != null) {
                batchFailure.addSuppressed(flushFailure);
            }
            return;
        }
        if (flushFailure != null) {
            throw flushFailure;
        }
    }

    private void dropRenderState(Player observer) {
        registry.clear();
        identity.forgetVanillaNameTeam();
        recoveryPending = false;
        publishedSpoofedCount = 0;
    }

    private void markRecoveryPending(Player observer) {
        recoveryPending = true;
        occluder.release(observer);
        publishedSpoofedCount = registry.size();
        scheduleTeardownRetry(observer);
    }

    private void scheduleTeardownRetry(Player observer) {
        Wormholes plugin = Wormholes.instance;
        if (plugin == null || observer == null || !observer.isOnline()
            || !recoveryPending || !teardownRetryScheduled.compareAndSet(false, true)) {
            return;
        }
        boolean scheduled = FoliaScheduler.runEntity(plugin, observer, () -> {
            teardownRetryScheduled.set(false);
            if (!recoveryPending) {
                return;
            }
            if (!observer.isOnline()) {
                dropRenderState(observer);
                return;
            }
            try {
                sendTeardown(observer);
                dropRenderState(observer);
            } catch (RuntimeException error) {
                reportTeardownFailure(observer, error);
                scheduleTeardownRetry(observer);
            }
        }, 1L);
        if (!scheduled) {
            teardownRetryScheduled.set(false);
        }
    }

    private void reportTeardownFailure(Player observer, RuntimeException error) {
        Wormholes plugin = Wormholes.instance;
        if (plugin == null || !teardownFailureReported.compareAndSet(false, true)) {
            return;
        }
        plugin.getLogger().log(Level.WARNING, "[spoof] failed to send projected entity teardown to "
            + (observer == null ? "unknown" : observer.getName()), error);
    }

    public boolean hasProjectedEntity(UUID sourceId) {
        return registry.contains(sourceId);
    }

    public Set<UUID> getProjectedEntityIds() {
        return registry.sourceIds();
    }

    public void sendAnimation(Player observer, UUID sourceId, EntityAnimationType type) {
        if (observer == null || !observer.isOnline() || sourceId == null || type == null) {
            return;
        }
        EntityRenderSpoofedEntity state = registry.get(sourceId);
        if (state == null || !state.living) {
            // Swing/hurt animations are LivingEntity-only on the client (handleAnimate casts to
            // LivingEntity); sending one for a projected non-living entity (e.g. an arrow) crashes
            // the viewer with a ClassCastException.
            return;
        }
        channel.send(observer, new WrapperPlayServerEntityAnimation(state.fakeId, type));
    }

    public void sendHurt(Player observer, UUID sourceId, float yaw) {
        if (observer == null || !observer.isOnline() || sourceId == null) {
            return;
        }
        EntityRenderSpoofedEntity state = registry.get(sourceId);
        if (state == null || !state.living) {
            return;
        }
        channel.send(observer, new WrapperPlayServerEntityAnimation(state.fakeId, EntityAnimationType.HURT));
        channel.send(observer, new WrapperPlayServerHurtAnimation(state.fakeId, yaw));
    }

    private boolean projectEntity(Player observer,
                                  ILocalPortal localPortal,
                                  ILocalPortal remotePortal,
                                  PortalFrame localViewFrame,
                                  PortalFrame remoteViewFrame,
                                  Frustum4D frustum,
                                  Entity entity,
                                  boolean upsideDown,
                                  int mirrorRotationQuarterTurns) {
        EntityType packetType = packetEntityType(entity);
        if (packetType == null) {
            return false;
        }

        boolean mirror = remotePortal == localPortal;
        Vector localOrigin = localPortal.getOrigin();
        Vector remoteOrigin = remotePortal.getOrigin();
        PortalFrame mirrorPlaneFrame = mirror ? localPortal.getFrame() : null;
        Vector mirrorPlaneOrigin = mirror ? localOrigin : null;

        WormholesPlatform.entityPosition(entity, scratchEntityPosition);
        double entityX = scratchEntityPosition[0];
        double entityZ = scratchEntityPosition[2];
        double halfHeight = entity.getHeight() * 0.5D;
        boolean itemFrame = ProjectedItemFrameTransform.isItemFrame(packetType);
        double visibleY = itemFrame ? scratchEntityPosition[1] : scratchEntityPosition[1] + halfHeight;
        if (mirror) {
            PortalCoordMap.mirrorSourceToDisplayPointInto(entityX, visibleY, entityZ,
                mirrorPlaneOrigin.getX(), mirrorPlaneOrigin.getY(), mirrorPlaneOrigin.getZ(),
                mirrorPlaneFrame, mirrorRotationQuarterTurns, scratchVisiblePoint);
        } else {
            PortalCoordMap.transformPointInto(entityX, visibleY, entityZ,
                remoteOrigin.getX(), remoteOrigin.getY(), remoteOrigin.getZ(),
                localOrigin.getX(), localOrigin.getY(), localOrigin.getZ(),
                remoteViewFrame, localViewFrame, scratchVisiblePoint);
        }

        if (!frustum.containsPrimitive(scratchVisiblePoint[0], scratchVisiblePoint[1], scratchVisiblePoint[2])) {
            return false;
        }

        if (itemFrame && entity instanceof Hanging hanging) {
            BlockFace facing = hanging.getFacing();
            scratchLook[0] = facing.getModX();
            scratchLook[1] = facing.getModY();
            scratchLook[2] = facing.getModZ();
        } else {
            lookDirectionInto((float) scratchEntityPosition[3], (float) scratchEntityPosition[4], scratchLook);
        }
        if (mirror) {
            PortalCoordMap.mirrorSourceToDisplayVectorInto(scratchLook[0], scratchLook[1], scratchLook[2],
                mirrorPlaneFrame, mirrorRotationQuarterTurns, scratchDirection);
        } else {
            remoteViewFrame.transformVectorInto(scratchLook[0], scratchLook[1], scratchLook[2], localViewFrame, scratchDirection);
        }
        float yaw = yaw(scratchDirection[0], scratchDirection[2]);
        float pitch = pitch(scratchDirection[0], scratchDirection[1], scratchDirection[2]);
        Direction sourceFacing = Direction.closest(scratchLook[0], scratchLook[1], scratchLook[2]);
        int metadataTransform = ProjectedItemFrameTransform.NONE;
        if (itemFrame) {
            metadataTransform = mirror
                ? ProjectedItemFrameTransform.mirror(sourceFacing, mirrorPlaneFrame,
                    mirrorRotationQuarterTurns, scratchDirection)
                : ProjectedItemFrameTransform.between(sourceFacing, remoteViewFrame, localViewFrame,
                    scratchDirection);
        }
        Vector3d position;
        if (itemFrame && mirror) {
            position = ProjectedItemFrameTransform.mirrorAnchor(
                entityX, scratchEntityPosition[1], entityZ,
                mirrorPlaneOrigin.getX(), mirrorPlaneOrigin.getY(), mirrorPlaneOrigin.getZ(),
                mirrorPlaneFrame, mirrorRotationQuarterTurns, scratchVisiblePoint);
        } else if (itemFrame) {
            position = ProjectedItemFrameTransform.betweenAnchor(
                entityX, scratchEntityPosition[1], entityZ,
                remoteOrigin.getX(), remoteOrigin.getY(), remoteOrigin.getZ(),
                localOrigin.getX(), localOrigin.getY(), localOrigin.getZ(),
                remoteViewFrame, localViewFrame, scratchVisiblePoint);
        } else {
            double visualBaseY = scratchVisiblePoint[1] - halfHeight;
            position = new Vector3d(scratchVisiblePoint[0], visualBaseY, scratchVisiblePoint[2]);
        }
        Vector3d velocity = mirror
            ? mirroredVelocity(entity, mirrorPlaneFrame, mirrorRotationQuarterTurns)
            : transformedVelocity(entity, remoteViewFrame, localViewFrame);

        EntityRenderSpoofedEntity state = registry.get(entity.getUniqueId());
        if (state != null && state.upsideDown != upsideDown) {
            registry.destroySingle(observer, entity.getUniqueId(), state);
            state = null;
        }
        if (state == null) {
            boolean playerEntity = entity instanceof Player;
            state = EntityRenderSpoofedEntity.create(playerEntity, upsideDown, entity instanceof LivingEntity);
            registry.track(entity.getUniqueId(), state);
            if (playerEntity) {
                identity.sendPlayerInfo(observer, (Player) entity, state, upsideDown);
            }
            WrapperPlayServerSpawnEntity spawn = new WrapperPlayServerSpawnEntity(state.fakeId, Optional.of(state.fakeUuid),
                packetType, position, pitch, yaw, yaw, ProjectedItemFrameTransform.spawnData(metadataTransform), Optional.of(velocity));
            channel.send(observer, spawn);
            identity.spawnPlayerLabel(observer, state, position, entity.getHeight());
            state.updateRotation(yaw, pitch);
            state.updateMetadataTransform(metadataTransform);
            state.rememberPosition(position);
            registry.syncHeadLook(observer, state, yaw);
            metadataBridge.sendEntityState(observer, entity, state, metadataTransform, true);
            state.resetMetadataCooldown();
            return true;
        }

        EntityRenderSpoofedEntity.Move move = state.updatePosition(position);
        boolean rotationChanged = state.updateRotation(yaw, pitch);
        boolean metadataTransformChanged = state.updateMetadataTransform(metadataTransform);
        registry.syncMotion(observer, state, move, rotationChanged, position, yaw, pitch, entity.isOnGround());
        identity.updatePlayerLabelPosition(observer, state, position, entity.getHeight());
        if (rotationChanged) {
            registry.syncHeadLook(observer, state, yaw);
        }
        if (state.updateVelocity(velocity)) {
            channel.send(observer, new WrapperPlayServerEntityVelocity(state.fakeId, velocity));
        }
        boolean metadataRefreshDue = state.shouldRefreshMetadata();
        if (metadataTransformChanged || metadataRefreshDue) {
            metadataBridge.sendEntityState(observer, entity, state, metadataTransform, false);
            state.resetMetadataCooldown();
        }
        return true;
    }

    private boolean canSpoof(Entity entity) {
        if (entity == null || entity.isDead() || EffectManager.isPortalEffectEntity(entity)) {
            return false;
        }
        return entity.isValid();
    }

    private EntityType packetEntityType(Entity entity) {
        NamespacedKey key = entity.getType().getKey();
        if (key == null || "unknown".equals(key.getKey())) {
            return null;
        }
        EntityType cached = entityTypeCache.get(key);
        if (cached != null) {
            return cached;
        }
        EntityType resolved = EntityTypes.getByName(key.getNamespace() + ":" + key.getKey());
        if (resolved != null) {
            entityTypeCache.put(key, resolved);
        }
        return resolved;
    }

    private Vector3d transformedVelocity(Entity entity, PortalFrame fromFrame, PortalFrame toFrame) {
        Vector velocity = entity.getVelocity();
        fromFrame.transformVectorInto(velocity.getX(), velocity.getY(), velocity.getZ(), toFrame, scratchDirection);
        return new Vector3d(scratchDirection[0], scratchDirection[1], scratchDirection[2]);
    }

    private Vector3d mirroredVelocity(Entity entity, PortalFrame planeFrame, int mirrorRotationQuarterTurns) {
        Vector velocity = entity.getVelocity();
        PortalCoordMap.mirrorSourceToDisplayVectorInto(velocity.getX(), velocity.getY(), velocity.getZ(), planeFrame,
            mirrorRotationQuarterTurns, scratchDirection);
        return new Vector3d(scratchDirection[0], scratchDirection[1], scratchDirection[2]);
    }

    static Vector3d playerLabelPosition(Vector3d playerPosition, double playerHeight) {
        double safeHeight = Double.isFinite(playerHeight) ? Math.max(0.0D, playerHeight) : 0.0D;
        return new Vector3d(playerPosition.getX(), playerPosition.getY() + safeHeight + LABEL_VERTICAL_MARGIN, playerPosition.getZ());
    }

    static List<EntityData<?>> playerLabelMetadata(String label) {
        PlayerLabelMetadataSpec spec = playerLabelMetadataSpec(label);
        return List.of(
            new EntityData<Integer>(spec.interpolationIndex(), EntityDataTypes.INT, Integer.valueOf(spec.interpolationTicks())),
            new EntityData<Byte>(spec.billboardIndex(), EntityDataTypes.BYTE, Byte.valueOf(spec.billboard())),
            new EntityData<Integer>(spec.brightnessIndex(), EntityDataTypes.INT, Integer.valueOf(spec.brightness())),
            new EntityData<Component>(spec.textIndex(), EntityDataTypes.ADV_COMPONENT, spec.text()),
            new EntityData<Integer>(spec.backgroundIndex(), EntityDataTypes.INT, Integer.valueOf(spec.background()))
        );
    }

    static PlayerLabelMetadataSpec playerLabelMetadataSpec(String label) {
        return new PlayerLabelMetadataSpec(
            DISPLAY_POSITION_ROTATION_INTERPOLATION_INDEX,
            LABEL_INTERPOLATION_TICKS,
            DISPLAY_BILLBOARD_INDEX,
            CENTER_BILLBOARD,
            DISPLAY_BRIGHTNESS_INDEX,
            FULL_BRIGHT,
            TEXT_DISPLAY_TEXT_INDEX,
            Component.text(playerLabelText(label), NamedTextColor.WHITE),
            TEXT_DISPLAY_BACKGROUND_INDEX,
            0);
    }

    static List<EntityData<?>> playerLabelTextMetadata(String label) {
        return List.of(new EntityData<Component>(TEXT_DISPLAY_TEXT_INDEX, EntityDataTypes.ADV_COMPONENT,
            Component.text(playerLabelText(label), NamedTextColor.WHITE)));
    }

    static String projectedProfileName(String sourceName, UUID fakeUuid, boolean upsideDown) {
        if (upsideDown) {
            return isFlipName(sourceName) ? NEUTRAL_PROFILE_NAME : FLIP_NAME;
        }
        return syntheticProfileName(fakeUuid);
    }

    static String syntheticProfileName(UUID fakeUuid) {
        String compact = fakeUuid.toString().replace("-", "");
        return "wh" + compact.substring(0, 14);
    }

    static String playerLabelText(String name) {
        String safe = name == null || name.isBlank() ? NEUTRAL_PROFILE_NAME : name;
        if (safe.length() <= 16) {
            return safe;
        }
        return safe.substring(0, 16);
    }

    static boolean isFlipName(String name) {
        return FLIP_NAME.equals(name) || FLIP_NAME_ALT.equals(name);
    }

    static void lookDirectionInto(float yaw, float pitch, double[] out) {
        double pitchRadians = Math.toRadians(pitch);
        double yawRadians = Math.toRadians(yaw);
        double horizontal = Math.cos(pitchRadians);
        out[0] = -horizontal * Math.sin(yawRadians);
        out[1] = -Math.sin(pitchRadians);
        out[2] = horizontal * Math.cos(yawRadians);
    }

    static float yaw(double x, double z) {
        return (float) Math.toDegrees(Math.atan2(-x, z));
    }

    static float pitch(double x, double y, double z) {
        double horizontal = Math.sqrt(x * x + z * z);
        return (float) Math.toDegrees(-Math.atan2(y, horizontal));
    }

    static <K, V> boolean removeCompletedRestores(Map<K, V> pending, Predicate<V> completed) {
        Iterator<Map.Entry<K, V>> iterator = pending.entrySet().iterator();
        while (iterator.hasNext()) {
            if (completed.test(iterator.next().getValue())) {
                iterator.remove();
            }
        }
        return pending.isEmpty();
    }

    record PlayerLabelMetadataSpec(int interpolationIndex,
                                   int interpolationTicks,
                                   int billboardIndex,
                                   byte billboard,
                                   int brightnessIndex,
                                   int brightness,
                                   int textIndex,
                                   Component text,
                                   int backgroundIndex,
                                   int background) {
    }
}
