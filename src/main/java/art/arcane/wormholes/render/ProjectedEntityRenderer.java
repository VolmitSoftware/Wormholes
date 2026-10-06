package art.arcane.wormholes.render;

import org.bukkit.block.data.BlockData;
import art.arcane.wormholes.geometry.GeometryVector;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.function.Function;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemSwingAnimation;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityAnimation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityAnimation.EntityAnimationType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerHurtAnimation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSwingAnimation;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.render.bedrock.BedrockProfile;
import art.arcane.wormholes.Wormholes;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.network.view.EntityVisual;
import art.arcane.wormholes.platform.WormholesPlatform;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.IPortal;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.render.view.ProjectionEntityView;
import art.arcane.wormholes.render.view.ProjectionWorldView;
import art.arcane.wormholes.render.view.RemoteWorldView;
import art.arcane.wormholes.util.Direction;

public final class ProjectedEntityRenderer {
    private static final int DISPLAY_POSITION_ROTATION_INTERPOLATION_INDEX = 10;
    private static final int DISPLAY_BILLBOARD_INDEX = 15;
    private static final int DISPLAY_BRIGHTNESS_INDEX = 16;
    private static final int TEXT_DISPLAY_TEXT_INDEX = 23;
    private static final int TEXT_DISPLAY_BACKGROUND_INDEX = 25;
    private static final byte CENTER_BILLBOARD = 3;
    private static final int FULL_BRIGHT = (15 << 4) | (15 << 20);
    private static final int LABEL_INTERPOLATION_TICKS = 3;

    private final EntityRenderPacketChannel channel;
    private final EntityRenderPlayerIdentity identity;
    private BedrockProfile viewerProfile = BedrockProfile.JAVA;
    private final EntityRenderSpoofRegistry<Player, Vector3d> registry;
    private final EntityRenderMetadataBridge metadataBridge;
    private final EntityRenderLocalOccluder occluder;
    private final EntityRenderVisualProjector<Player, World, ILocalPortal, Vector3d, EntityType, ProjectionEntityView> visualProjector;
    private final Map<NamespacedKey, EntityType> entityTypeCache;
    private final double[] scratchVisiblePoint;
    private final double[] scratchDirection;
    private final double[] scratchLook;
    private final double[] scratchEntityPosition;
    private final List<EntityRelationship> scratchRelationships;
    private final EntityProjectionRecovery<Player> recovery;
    private volatile int publishedSpoofedCount;
    private final Map<UUID, ProjectedEntityRenderer> nestedRenderers = new HashMap<UUID, ProjectedEntityRenderer>();
    private ProjectorRecursivePortals<World, ILocalPortal> recursivePortals;
    private volatile int publishedNestedCount;
    private EntityProjectionPath<World, ILocalPortal> projectionPath;
    private int renderLimit = Integer.MAX_VALUE;

    public ProjectedEntityRenderer() {
        this(new EntityRenderPacketChannel());
    }

    private ProjectedEntityRenderer(EntityRenderPacketChannel channel) {
        this(channel, new EntityRenderPlayerIdentity(channel));
    }

    private ProjectedEntityRenderer(EntityRenderPacketChannel channel, EntityRenderPlayerIdentity identity) {
        this(channel, identity, new EntityRenderSpoofRegistry<>(new BukkitEntityRegistryHost(channel, identity)));
    }

    ProjectedEntityRenderer(EntityRenderPacketChannel channel, EntityRenderPlayerIdentity identity, EntityRenderSpoofRegistry<Player, Vector3d> registry) {
        this(channel, identity, registry, new EntityRenderLocalOcclusionArbiter<>(BukkitEntityVisibility.create()), UUID.randomUUID());
    }

    ProjectedEntityRenderer(EntityRenderLocalOcclusionArbiter<Player, Entity> localOcclusion, UUID localOcclusionOwnerId) {
        this(new EntityRenderPacketChannel(), localOcclusion, localOcclusionOwnerId);
    }

    private ProjectedEntityRenderer(EntityRenderPacketChannel channel,
                                    EntityRenderLocalOcclusionArbiter<Player, Entity> localOcclusion,
                                    UUID localOcclusionOwnerId) {
        this(channel, new EntityRenderPlayerIdentity(channel), localOcclusion, localOcclusionOwnerId);
    }

    private ProjectedEntityRenderer(EntityRenderPacketChannel channel,
                                    EntityRenderPlayerIdentity identity,
                                    EntityRenderLocalOcclusionArbiter<Player, Entity> localOcclusion,
                                    UUID localOcclusionOwnerId) {
        this(channel, identity, new EntityRenderSpoofRegistry<>(new BukkitEntityRegistryHost(channel, identity)), localOcclusion,
            localOcclusionOwnerId);
    }

    private ProjectedEntityRenderer(EntityRenderPacketChannel channel,
                                    EntityRenderPlayerIdentity identity,
                                    EntityRenderSpoofRegistry<Player, Vector3d> registry,
                                    EntityRenderLocalOcclusionArbiter<Player, Entity> localOcclusion,
                                    UUID localOcclusionOwnerId) {
        this.channel = channel;
        this.identity = identity;
        this.registry = registry;
        this.metadataBridge = new EntityRenderMetadataBridge(channel);
        this.occluder = new EntityRenderLocalOccluder(localOcclusion, localOcclusionOwnerId);
        this.visualProjector = new EntityRenderVisualProjector<>(registry, new BukkitEntityVisualHost(channel,
            new BukkitEntityVisualHost.Options(identity, this.metadataBridge)), FidelitySettings::snapshot);
        this.entityTypeCache = new HashMap<NamespacedKey, EntityType>(32);
        this.scratchVisiblePoint = new double[3];
        this.scratchDirection = new double[3];
        this.scratchLook = new double[3];
        this.scratchEntityPosition = new double[5];
        this.scratchRelationships = new ArrayList<EntityRelationship>(16);
        this.recovery = new EntityProjectionRecovery<>(new RecoveryHost());
    }

    public void setViewerProfile(BedrockProfile profile) {
        viewerProfile = profile == null ? BedrockProfile.JAVA : profile;
        identity.setLabelsEnabled(!viewerProfile.withholdsDisplays());
    }

    private int entityLimit() {
        return Math.min(renderLimit, viewerProfile.entityLimit(Settings.MAX_SPOOFED_ENTITIES));
    }

    public int getSpoofedCount() {
        return publishedSpoofedCount + publishedNestedCount;
    }

    void prepareRecursiveProjection(EntityProjectionPath.Root<World, ILocalPortal> root, ProjectorRecursivePortals<World, ILocalPortal> portals) {
        recursivePortals = portals;
        portals.revalidate();
        projectionPath = root == null ? null : new EntityProjectionPath<>(root, portals);
    }

    void applyRecursive(Player observer, RecursiveRender context) {
        int[] budget = {Math.max(0, entityLimit() - registry.size()), 256};
        applyRecursive(observer, context, recursivePortals, budget);
    }

    private void applyRecursive(Player observer, RecursiveRender context,
                                ProjectorRecursivePortals<World, ILocalPortal> portals, int[] budget) {
        Set<UUID> visiblePaths = new HashSet<UUID>();
        if (projectionPath != null && Settings.ENTITY_SPOOFING) {
            for (ProjectorRecursivePortals<World, ILocalPortal>.Candidate candidate : projectionPath.index.paths()) {
                if (budget[0] <= 0 || budget[1] <= 0) {
                    break;
                }
                EntityProjectionPath<World, ILocalPortal> childPath = projectionPath.child(candidate, portals);
                if (childPath == null) {
                    continue;
                }
                budget[1]--;
                ProjectionWorldView view = context.viewLookup().apply(candidate.nestedWorld);
                if (context.snapshots() && !(view instanceof ProjectionEntityView)) {
                    continue;
                }
                ProjectedEntityRenderer renderer = nestedRenderers.computeIfAbsent(candidate.portalId,
                    ignored -> new ProjectedEntityRenderer(channel));
                visiblePaths.add(candidate.portalId);
                renderer.setViewerProfile(viewerProfile);
                renderer.projectionPath = childPath;
                renderer.renderLimit = budget[0];
                ILocalPortal destination = candidate.nestedDestination;
                if (context.snapshots()) {
                    renderer.applySnapshot(observer, context.localPortal(), destination, false, 0,
                        (ProjectionEntityView) view, context.frustum(), context.depth(),
                        context.localFrame(), destination.getFrame(), context.occlusion());
                } else {
                    renderer.apply(observer, context.localPortal(), destination, context.frustum(), context.depth(),
                        context.localFrame(), destination.getFrame(), 0, context.occlusion());
                }
                budget[0] -= renderer.registry.size();
                renderer.applyRecursive(observer, context, portals, budget);
            }
        }
        Iterator<Map.Entry<UUID, ProjectedEntityRenderer>> iterator = nestedRenderers.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, ProjectedEntityRenderer> entry = iterator.next();
            if (!visiblePaths.contains(entry.getKey())) {
                entry.getValue().close(observer);
                iterator.remove();
            }
        }
        int count = 0;
        for (ProjectedEntityRenderer renderer : nestedRenderers.values()) {
            count += renderer.getSpoofedCount();
        }
        publishedNestedCount = count;
    }

    record RecursiveRender(ILocalPortal localPortal, PortalFrame localFrame, Frustum4D frustum, double depth,
                           boolean snapshots, Function<World, ProjectionWorldView> viewLookup, ProjectedEntityOcclusion<BlockData, ProjectionWorldView> occlusion) {
    }

    public void apply(Player observer,
                      ILocalPortal localPortal,
                      ILocalPortal remotePortal,
                      Frustum4D frustum,
                      double projectionDepth,
                      PortalFrame localViewFrame,
                      PortalFrame remoteViewFrame,
                      int mirrorRotationQuarterTurns,
                      ProjectedEntityOcclusion<BlockData, ProjectionWorldView> entityOcclusion) {
        if (!Settings.ENTITY_SPOOFING || entityLimit() <= 0) {
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
            scratchRelationships.clear();
            if (projectionPath == null || !projectionPath.nested()) {
                entityOcclusion.startBatch();
            }
            if (projectionPath == null || !projectionPath.nested()) {
                occluder.hideLocalEntities(observer, localPortal, frustum, projectionDepth);
            }
            boolean upsideDown = remotePortal == localPortal
                ? PortalCoordMap.mirrorTransformFlipsWorldUp(localPortal.getFrame(), mirrorRotationQuarterTurns)
                : PortalCoordMap.transformFlipsWorldUp(remoteViewFrame, localViewFrame);
            if (projectionPath != null) {
                upsideDown = projectionPath.upsideDown();
            }
            int count = 0;

            for (Entity entity : EntityRenderCaches.nearbyRemoteEntities(remotePortal, remoteCenter, range)) {
                if (count >= entityLimit()) {
                    break;
                }
                if (!ProjectionEntityFilter.canCapture(entity)
                    || !WormholesPlatform.isEntityVisible(observer, entity.getUniqueId(),
                        entity.isVisibleByDefault(), Wormholes.instance)) {
                    continue;
                }
                if (BukkitEntityOcclusion.fullyHidden(entityOcclusion, entity.getBoundingBox(), projectionPath)) {
                    continue;
                }
                if (!projectEntity(observer, localPortal, remotePortal, localViewFrame, remoteViewFrame, frustum,
                    entity, upsideDown, mirrorRotationQuarterTurns)) {
                    continue;
                }
                registry.markVisible(entity.getUniqueId());
                scratchRelationships.add(BukkitEntityRelationships.of(entity));
                count++;
            }

            registry.destroyHidden(observer);
            registry.applyRelationships(observer, scratchRelationships);
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
                            ProjectedEntityOcclusion<BlockData, ProjectionWorldView> entityOcclusion) {
        if (!Settings.ENTITY_SPOOFING || entityLimit() <= 0) {
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
            if (projectionPath == null || !projectionPath.nested()) {
                entityOcclusion.startBatch();
            }
            if (projectionPath == null || !projectionPath.nested()) {
                occluder.hideLocalEntities(observer, localPortal, frustum, projectionDepth);
            }
            boolean upsideDown = PortalCoordMap.transformFlipsWorldUp(remoteViewFrame, localViewFrame);
            if (projectionPath != null) {
                upsideDown = projectionPath.upsideDown();
            }
            int count = 0;

            List<EntityVisual> visuals = remoteView.getEntities();
            for (EntityVisual visual : visuals) {
                if (count >= entityLimit()) {
                    break;
                }
                if (!remoteView.isVisibleTo(observer, visual.id())) {
                    continue;
                }
                if (BukkitEntityOcclusion.fullyHidden(entityOcclusion, visual, projectionPath)) {
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
                              ProjectedEntityOcclusion<BlockData, ProjectionWorldView> entityOcclusion) {
        if (!Settings.ENTITY_SPOOFING || entityLimit() <= 0) {
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
            if (projectionPath == null || !projectionPath.nested()) {
                entityOcclusion.startBatch();
            }
            if (projectionPath == null || !projectionPath.nested()) {
                occluder.hideLocalEntities(observer, localPortal, frustum, projectionDepth);
            }
            visualProjector.apply(observer, new EntityRenderVisualProjector.Pass<>(localPortal, remotePortal, entityView,
                localViewFrame, remoteViewFrame, frustum, mirror, mirrorRotationQuarterTurns, projectionPath,
                entityOcclusion, range, entityLimit()));
        } catch (RuntimeException error) {
            batchFailure = error;
            throw error;
        } finally {
            finishRenderBatch(observer, batchFailure);
        }
    }

    public void close(Player observer) {
        for (ProjectedEntityRenderer renderer : nestedRenderers.values()) {
            renderer.close(observer);
        }
        nestedRenderers.clear();
        publishedNestedCount = 0;
        recovery.teardown(observer);
    }

    public void discard(Player observer) {
        for (ProjectedEntityRenderer renderer : nestedRenderers.values()) {
            renderer.close(observer);
        }
        nestedRenderers.clear();
        publishedNestedCount = 0;
        recovery.teardown(observer);
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
        recovery.recovered();
    }

    private void prepareRenderBatch(Player observer) {
        if (recovery.pending()) {
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
        recovery.clearPending();
        publishedSpoofedCount = 0;
    }

    private void markRecoveryPending(Player observer) {
        recovery.markPending(observer);
        publishedSpoofedCount = registry.size();
    }

    public boolean hasProjectedEntity(UUID sourceId) {
        if (registry.contains(sourceId)) {
            return true;
        }
        for (ProjectedEntityRenderer renderer : nestedRenderers.values()) {
            if (renderer.hasProjectedEntity(sourceId)) {
                return true;
            }
        }
        return false;
    }

    public Set<UUID> getProjectedEntityIds() {
        if (nestedRenderers.isEmpty()) {
            return registry.sourceIds();
        }
        Set<UUID> ids = new HashSet<UUID>(registry.sourceIds());
        for (ProjectedEntityRenderer renderer : nestedRenderers.values()) {
            ids.addAll(renderer.getProjectedEntityIds());
        }
        return ids;
    }

    public void sendAnimation(Player observer, UUID sourceId, EntityAnimationType type) {
        if (observer == null || !observer.isOnline() || sourceId == null || type == null) {
            return;
        }
        for (ProjectedEntityRenderer renderer : nestedRenderers.values()) {
            renderer.sendAnimation(observer, sourceId, type);
        }
        int fakeId = registry.livingId(sourceId);
        if (fakeId < 0) {
            // Swing/hurt animations are LivingEntity-only on the client (handleAnimate casts to
            // LivingEntity); sending one for a projected non-living entity (e.g. an arrow) crashes
            // the viewer with a ClassCastException.
            return;
        }
        if ((type == EntityAnimationType.SWING_MAIN_ARM || type == EntityAnimationType.SWING_OFF_HAND)
            && PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_26_3)) {
            InteractionHand hand = type == EntityAnimationType.SWING_OFF_HAND
                ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
            channel.send(observer, new WrapperPlayServerSwingAnimation(fakeId, hand,
                new ItemSwingAnimation(ItemSwingAnimation.Type.WHACK, 6)));
            return;
        }
        channel.send(observer, new WrapperPlayServerEntityAnimation(fakeId, type));
    }

    public void sendHurt(Player observer, UUID sourceId, float yaw) {
        if (observer == null || !observer.isOnline() || sourceId == null) {
            return;
        }
        for (ProjectedEntityRenderer renderer : nestedRenderers.values()) {
            renderer.sendHurt(observer, sourceId, yaw);
        }
        int fakeId = registry.livingId(sourceId);
        if (fakeId < 0) {
            return;
        }
        channel.send(observer, new WrapperPlayServerHurtAnimation(fakeId, yaw));
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
        GeometryVector localOrigin = localPortal.getOrigin();
        GeometryVector remoteOrigin = remotePortal.getOrigin();
        PortalFrame mirrorPlaneFrame = mirror ? localPortal.getFrame() : null;
        GeometryVector mirrorPlaneOrigin = mirror ? localOrigin : null;

        WormholesPlatform.entityPosition(entity, scratchEntityPosition);
        double entityX = scratchEntityPosition[0];
        double entityZ = scratchEntityPosition[2];
        double halfHeight = entity.getHeight() * 0.5D;
        boolean itemFrame = BukkitItemFrameMetadata.isItemFrame(packetType);
        boolean hanging = BukkitItemFrameMetadata.isHanging(packetType);
        double visibleY = hanging ? scratchEntityPosition[1] : scratchEntityPosition[1] + halfHeight;
        if (projectionPath != null) {
            if (!BukkitEntityOcclusion.visible(projectionPath, entityX, visibleY, entityZ, entity.getBoundingBox(), scratchVisiblePoint)) {
                return false;
            }
        } else if (mirror) {
            PortalCoordMap.mirrorSourceToDisplayPointInto(entityX, visibleY, entityZ,
                mirrorPlaneOrigin.getX(), mirrorPlaneOrigin.getY(), mirrorPlaneOrigin.getZ(),
                mirrorPlaneFrame, mirrorRotationQuarterTurns, scratchVisiblePoint);
        } else {
            PortalCoordMap.transformPointInto(entityX, visibleY, entityZ,
                remoteOrigin.getX(), remoteOrigin.getY(), remoteOrigin.getZ(),
                localOrigin.getX(), localOrigin.getY(), localOrigin.getZ(),
                remoteViewFrame, localViewFrame, scratchVisiblePoint);
        }

        if (projectionPath == null && !frustum.containsPrimitive(scratchVisiblePoint[0], scratchVisiblePoint[1], scratchVisiblePoint[2])) {
            return false;
        }

        if (hanging && entity instanceof Hanging hangingEntity) {
            BlockFace facing = hangingEntity.getFacing();
            scratchLook[0] = facing.getModX();
            scratchLook[1] = facing.getModY();
            scratchLook[2] = facing.getModZ();
        } else {
            EntityVisualProjection.lookDirectionInto((float) scratchEntityPosition[3], (float) scratchEntityPosition[4], scratchLook);
        }
        if (projectionPath != null) {
            projectionPath.vector(scratchLook[0], scratchLook[1], scratchLook[2], scratchDirection);
        } else if (mirror) {
            PortalCoordMap.mirrorSourceToDisplayVectorInto(scratchLook[0], scratchLook[1], scratchLook[2],
                mirrorPlaneFrame, mirrorRotationQuarterTurns, scratchDirection);
        } else {
            remoteViewFrame.transformVectorInto(scratchLook[0], scratchLook[1], scratchLook[2], localViewFrame, scratchDirection);
        }
        float yaw = EntityVisualProjection.yaw(scratchDirection[0], scratchDirection[2]);
        float pitch = EntityVisualProjection.pitch(scratchDirection[0], scratchDirection[1], scratchDirection[2]);
        Direction sourceFacing = Direction.closest(scratchLook[0], scratchLook[1], scratchLook[2]);
        int metadataTransform = ProjectedItemFrameTransform.NONE;
        if (itemFrame) {
            metadataTransform = projectionPath != null ? projectionPath.itemFrameTransform(sourceFacing) : mirror
                ? ProjectedItemFrameTransform.mirror(sourceFacing, mirrorPlaneFrame,
                    mirrorRotationQuarterTurns, scratchDirection)
                : ProjectedItemFrameTransform.between(sourceFacing, remoteViewFrame, localViewFrame,
                    scratchDirection);
        }
        Vector3d position;
        if (hanging && projectionPath != null) {
            position = projectionPath.anchor(entityX, scratchEntityPosition[1], entityZ, Vector3d::new);
        } else if (hanging && mirror) {
            position = ProjectedItemFrameTransform.mirrorAnchor(
                entityX, scratchEntityPosition[1], entityZ,
                mirrorPlaneOrigin.getX(), mirrorPlaneOrigin.getY(), mirrorPlaneOrigin.getZ(),
                mirrorPlaneFrame, mirrorRotationQuarterTurns, scratchVisiblePoint, Vector3d::new);
        } else if (hanging) {
            position = ProjectedItemFrameTransform.betweenAnchor(
                entityX, scratchEntityPosition[1], entityZ,
                remoteOrigin.getX(), remoteOrigin.getY(), remoteOrigin.getZ(),
                localOrigin.getX(), localOrigin.getY(), localOrigin.getZ(),
                remoteViewFrame, localViewFrame, scratchVisiblePoint, Vector3d::new);
        } else {
            double visualBaseY = scratchVisiblePoint[1] - halfHeight;
            position = new Vector3d(scratchVisiblePoint[0], visualBaseY, scratchVisiblePoint[2]);
        }
        Vector3d velocity = projectionPath != null ? projectedVelocity(entity) : mirror
            ? mirroredVelocity(entity, mirrorPlaneFrame, mirrorRotationQuarterTurns)
            : transformedVelocity(entity, remoteViewFrame, localViewFrame);

        EntityRenderSpoofedEntity state = registry.get(entity.getUniqueId());
        if (state != null && (state.upsideDown != upsideDown
            || entity instanceof Player player && identity.playerProfileChanged(player, state, System.nanoTime()))) {
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
            state.rememberPosition(position.getX(), position.getY(), position.getZ());
            registry.syncHeadLook(observer, state, yaw);
            metadataBridge.sendEntityState(observer, entity, state, metadataTransform, true);
            state.resetMetadataCooldown();
            return true;
        }

        EntityRenderSpoofedEntity.Move move = state.updatePosition(position.getX(), position.getY(), position.getZ());
        boolean rotationChanged = state.updateRotation(yaw, pitch);
        boolean metadataTransformChanged = state.updateMetadataTransform(metadataTransform);
        registry.syncMotion(observer, state, move, rotationChanged, position, yaw, pitch, entity.isOnGround());
        identity.updatePlayerLabelPosition(observer, state, position, entity.getHeight());
        if (rotationChanged) {
            registry.syncHeadLook(observer, state, yaw);
        }
        if (state.updateVelocity(velocity.getX(), velocity.getY(), velocity.getZ(),
            FidelitySettings.snapshot().entityVelocityEpsilon())) {
            channel.send(observer, new WrapperPlayServerEntityVelocity(state.fakeId, velocity));
        }
        boolean metadataRefreshDue = state.shouldRefreshMetadata();
        if (metadataTransformChanged || metadataRefreshDue) {
            metadataBridge.sendEntityState(observer, entity, state, metadataTransform, false);
            state.resetMetadataCooldown();
        }
        return true;
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

    private Vector3d projectedVelocity(Entity entity) {
        Vector velocity = entity.getVelocity();
        projectionPath.vector(velocity.getX(), velocity.getY(), velocity.getZ(), scratchDirection);
        return new Vector3d(scratchDirection[0], scratchDirection[1], scratchDirection[2]);
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
        return new Vector3d(playerPosition.getX(), ProjectedPlayerNames.labelY(playerPosition.getY(), playerHeight), playerPosition.getZ());
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
            Component.text(ProjectedPlayerNames.playerLabelText(label), NamedTextColor.WHITE),
            TEXT_DISPLAY_BACKGROUND_INDEX,
            0);
    }

    static List<EntityData<?>> playerLabelTextMetadata(String label) {
        return List.of(new EntityData<Component>(TEXT_DISPLAY_TEXT_INDEX, EntityDataTypes.ADV_COMPONENT,
            Component.text(ProjectedPlayerNames.playerLabelText(label), NamedTextColor.WHITE)));
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
    private final class RecoveryHost implements EntityProjectionRecovery.Host<Player> {
        public boolean online(Player observer) { return observer != null && observer.isOnline(); }
        public boolean hasState() { return registry.size() != 0 || identity.hasVanillaNameTeam(); }
        public void send(Player observer) { sendTeardown(observer); }
        public void drop(Player observer) { dropRenderState(observer); }
        public void release(Player observer) { occluder.release(observer); }

        public boolean schedule(Player observer, Runnable task) {
            Wormholes plugin = Wormholes.instance;
            return plugin != null && FoliaScheduler.runEntity(plugin, observer, task, 1L);
        }

        public void warning(Player observer, RuntimeException error) {
            Wormholes plugin = Wormholes.instance;
            if (plugin != null) {
                plugin.getLogger().log(Level.WARNING, "[spoof] failed to send projected entity teardown to "
                    + (observer == null ? "unknown" : observer.getName()), error);
            }
        }
    }

}
