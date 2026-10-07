package art.arcane.wormholes.render;

import org.bukkit.block.data.BlockData;
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
import art.arcane.optics.fidelity.BedrockProfile;
import art.arcane.wormholes.Wormholes;
import art.arcane.optics.entity.EntitySnapshot;
import art.arcane.wormholes.platform.WormholesPlatform;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.IPortal;
import art.arcane.wormholes.render.view.ProjectionEntityView;
import art.arcane.wormholes.render.view.ProjectionWorldView;
import art.arcane.wormholes.render.view.RemoteWorldView;
import art.arcane.optics.entity.EntityRelationship;
import art.arcane.optics.entity.ItemFrameTransform;
import art.arcane.optics.entity.PlayerNames;
import art.arcane.optics.entity.ProjectionRecovery;
import art.arcane.optics.entity.SnapshotProjector;
import art.arcane.optics.entity.SpoofRegistry;
import art.arcane.optics.entity.SpoofedEntity;
import art.arcane.optics.entity.EntityProjection;
import art.arcane.optics.occlusion.LocalOcclusionArbiter;
import art.arcane.optics.occlusion.ProjectedEntityOcclusion;
import art.arcane.optics.recursion.EntityPath;
import art.arcane.optics.recursion.RecursiveEndpoints;
import art.arcane.optics.spi.OpticsScheduler;
import art.arcane.optics.volume.ViewVolume;
import art.arcane.optics.math.Angles;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.math.Box;
import org.bukkit.util.BoundingBox;

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
    private final BukkitEntityRegistryHost output;
    private BedrockProfile viewerProfile = BedrockProfile.JAVA;
    private final SpoofRegistry<Player, Vector3d> registry;
    private final EntityRenderMetadataBridge metadataBridge;
    private final EntityRenderLocalOccluder occluder;
    private final SnapshotProjector<Player, World, ILocalPortal, Vector3d, EntityType, ProjectionEntityView> visualProjector;
    private final Map<NamespacedKey, EntityType> entityTypeCache;
    private final EntityProjection projection;
    private final double[] scratchLook;
    private final double[] scratchEntityPosition;
    private final List<EntityRelationship> scratchRelationships;
    private final ProjectionRecovery<Player> recovery;
    private final OpticsScheduler<Player, ?> scheduler;
    private volatile int publishedSpoofedCount;
    private final Map<UUID, ProjectedEntityRenderer> nestedRenderers = new HashMap<UUID, ProjectedEntityRenderer>();
    private RecursiveEndpoints<World, ILocalPortal> recursivePortals;
    private volatile int publishedNestedCount;
    private EntityPath<World, ILocalPortal> projectionPath;
    private int renderLimit = Integer.MAX_VALUE;

    public ProjectedEntityRenderer(OpticsScheduler<Player, ?> scheduler) {
        this(new BukkitEntityRegistryHost(new EntityRenderPacketChannel(), BukkitEntityRegistryHost.PLUGIN_VISIBILITY), scheduler);
    }

    ProjectedEntityRenderer(LocalOcclusionArbiter<Player, Entity> localOcclusion, OpticsScheduler<Player, ?> scheduler,
                            UUID localOcclusionOwnerId) {
        this(new BukkitEntityRegistryHost(new EntityRenderPacketChannel(), BukkitEntityRegistryHost.PLUGIN_VISIBILITY), localOcclusion,
            scheduler, localOcclusionOwnerId);
    }

    ProjectedEntityRenderer(BukkitEntityRegistryHost output, SpoofRegistry<Player, Vector3d> registry, OpticsScheduler<Player, ?> scheduler) {
        this(output, registry, new LocalOcclusionArbiter<>(BukkitEntityVisualHost.FEED, output, scheduler), scheduler, UUID.randomUUID());
    }

    private ProjectedEntityRenderer(BukkitEntityRegistryHost output, OpticsScheduler<Player, ?> scheduler) {
        this(output, new SpoofRegistry<>(output), scheduler);
    }

    private ProjectedEntityRenderer(BukkitEntityRegistryHost output, LocalOcclusionArbiter<Player, Entity> localOcclusion,
                                    OpticsScheduler<Player, ?> scheduler, UUID localOcclusionOwnerId) {
        this(output, new SpoofRegistry<>(output), localOcclusion, scheduler, localOcclusionOwnerId);
    }

    private ProjectedEntityRenderer(BukkitEntityRegistryHost output,
                                    SpoofRegistry<Player, Vector3d> registry,
                                    LocalOcclusionArbiter<Player, Entity> localOcclusion,
                                    OpticsScheduler<Player, ?> scheduler,
                                    UUID localOcclusionOwnerId) {
        this.channel = output.channel();
        this.identity = output.identity();
        this.output = output;
        this.registry = registry;
        this.metadataBridge = output.metadataBridge();
        this.occluder = new EntityRenderLocalOccluder(localOcclusion, localOcclusionOwnerId);
        this.visualProjector = new SnapshotProjector<>(registry, BukkitEntityVisualHost.FEED, output, FidelitySettings::snapshot);
        this.entityTypeCache = new HashMap<NamespacedKey, EntityType>(32);
        this.projection = new EntityProjection();
        this.scratchLook = new double[3];
        this.scratchEntityPosition = new double[5];
        this.scratchRelationships = new ArrayList<EntityRelationship>(16);
        this.scheduler = scheduler;
        this.recovery = new ProjectionRecovery<>(output, scheduler, new ProjectionRecovery.Teardown<>(this::hasRenderState,
            this::sendTeardown, this::dropRenderState, occluder::release));
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

    void prepareRecursiveProjection(EntityPath.Root<World, ILocalPortal> root, RecursiveEndpoints<World, ILocalPortal> portals) {
        recursivePortals = portals;
        portals.revalidate();
        projectionPath = root == null ? null : new EntityPath<>(root, portals);
    }

    void applyRecursive(Player observer, RecursiveRender context) {
        int[] budget = {Math.max(0, entityLimit() - registry.size()), 256};
        applyRecursive(observer, context, recursivePortals, budget);
    }

    private void applyRecursive(Player observer, RecursiveRender context,
                                RecursiveEndpoints<World, ILocalPortal> portals, int[] budget) {
        Set<UUID> visiblePaths = new HashSet<UUID>();
        if (projectionPath != null && Settings.ENTITY_SPOOFING) {
            for (RecursiveEndpoints<World, ILocalPortal>.Candidate candidate : projectionPath.index.paths()) {
                if (budget[0] <= 0 || budget[1] <= 0) {
                    break;
                }
                EntityPath<World, ILocalPortal> childPath = projectionPath.child(candidate, portals);
                if (childPath == null) {
                    continue;
                }
                budget[1]--;
                ProjectionWorldView view = context.viewLookup().apply(candidate.nestedWorld);
                if (context.snapshots() && !(view instanceof ProjectionEntityView)) {
                    continue;
                }
                ProjectedEntityRenderer renderer = nestedRenderers.computeIfAbsent(candidate.portalId,
                    ignored -> new ProjectedEntityRenderer(new BukkitEntityRegistryHost(channel, BukkitEntityRegistryHost.PLUGIN_VISIBILITY),
                        scheduler));
                visiblePaths.add(candidate.portalId);
                renderer.setViewerProfile(viewerProfile);
                renderer.projectionPath = childPath;
                renderer.renderLimit = budget[0];
                ILocalPortal destination = candidate.nestedDestination;
                if (context.snapshots()) {
                    renderer.applySnapshot(observer, context.localPortal(), destination, (ProjectionEntityView) view, context.frustum(),
                        context.depth(), childPath.transform(), context.occlusion());
                } else {
                    renderer.apply(observer, context.localPortal(), destination, context.frustum(), context.depth(), childPath.transform(),
                        context.occlusion());
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

    record RecursiveRender(ILocalPortal localPortal, ViewVolume frustum, double depth,
                           boolean snapshots, Function<World, ProjectionWorldView> viewLookup, ProjectedEntityOcclusion<BlockData, ProjectionWorldView> occlusion) {
    }

    public void apply(Player observer,
                      ILocalPortal localPortal,
                      ILocalPortal remotePortal,
                      ViewVolume frustum,
                      double projectionDepth,
                      OpticTransform transform,
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
            boolean upsideDown = projectionPath == null ? transform.flipsWorldUp() : projectionPath.transform().flipsWorldUp();
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
                if (!projectEntity(observer, transform, frustum, entity, upsideDown)) {
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
                            RemoteWorldView remoteView,
                            ViewVolume frustum,
                            double projectionDepth,
                            OpticTransform transform,
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
            boolean upsideDown = projectionPath == null ? transform.flipsWorldUp() : projectionPath.transform().flipsWorldUp();
            int count = 0;

            List<EntitySnapshot> visuals = remoteView.getEntities();
            for (EntitySnapshot visual : visuals) {
                if (count >= entityLimit()) {
                    break;
                }
                if (!remoteView.isVisibleTo(observer, visual.id())) {
                    continue;
                }
                if (BukkitEntityOcclusion.fullyHidden(entityOcclusion, visual, projectionPath)) {
                    continue;
                }
                if (!visualProjector.projectRemoteVisual(observer, transform, frustum, remoteView, visual, upsideDown)) {
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
                              ProjectionEntityView entityView,
                              ViewVolume frustum,
                              double projectionDepth,
                              OpticTransform transform,
                              ProjectedEntityOcclusion<BlockData, ProjectionWorldView> entityOcclusion) {
        if (!Settings.ENTITY_SPOOFING || entityLimit() <= 0) {
            close(observer);
            return;
        }
        if (observer == null || remotePortal == null || entityView == null) {
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
            visualProjector.apply(observer, new SnapshotProjector.Pass<>(localPortal, remotePortal, entityView, transform, frustum,
                projectionPath, entityOcclusion, range, entityLimit()));
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

    private boolean hasRenderState() {
        return registry.size() != 0 || identity.hasVanillaNameTeam();
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

    private boolean projectEntity(Player observer, OpticTransform transform, ViewVolume frustum, Entity entity, boolean upsideDown) {
        EntityType packetType = packetEntityType(entity);
        if (packetType == null) {
            return false;
        }

        boolean itemFrame = output.isItemFrame(packetType);
        boolean hanging = output.isHanging(packetType);
        EntitySnapshot visual = liveSnapshot(entity, hanging);
        boolean projected = projectionPath == null ? projection.project(visual, transform, frustum, itemFrame, hanging)
            : projection.project(visual, projectionPath, bounds(entity.getBoundingBox()), itemFrame, hanging);
        if (!projected) {
            return false;
        }
        Vector3d position = new Vector3d(projection.x(), projection.y(), projection.z());
        Vector3d velocity = new Vector3d(projection.velocityX(), projection.velocityY(), projection.velocityZ());
        float yaw = projection.yaw();
        float pitch = projection.pitch();
        int metadataTransform = projection.metadataTransform();

        SpoofedEntity state = registry.get(entity.getUniqueId());
        if (state != null && (state.upsideDown() != upsideDown
            || entity instanceof Player player && identity.playerProfileChanged(player, state, System.nanoTime()))) {
            registry.destroySingle(observer, entity.getUniqueId(), state);
            state = null;
        }
        if (state == null) {
            boolean playerEntity = entity instanceof Player;
            state = SpoofedEntity.create(output::allocateEntityId, playerEntity, upsideDown, entity instanceof LivingEntity);
            registry.track(entity.getUniqueId(), state);
            if (playerEntity) {
                identity.sendPlayerInfo(observer, (Player) entity, state, upsideDown);
            }
            WrapperPlayServerSpawnEntity spawn = new WrapperPlayServerSpawnEntity(state.fakeId(), Optional.of(state.fakeUuid()),
                packetType, position, pitch, yaw, yaw, ItemFrameTransform.spawnData(metadataTransform), Optional.of(velocity));
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

        SpoofedEntity.Move move = state.updatePosition(position.getX(), position.getY(), position.getZ());
        boolean rotationChanged = state.updateRotation(yaw, pitch);
        boolean metadataTransformChanged = state.updateMetadataTransform(metadataTransform);
        registry.syncMotion(observer, state, move, rotationChanged, position, yaw, pitch, entity.isOnGround());
        identity.updatePlayerLabelPosition(observer, state, position, entity.getHeight());
        if (rotationChanged) {
            registry.syncHeadLook(observer, state, yaw);
        }
        if (state.updateVelocity(velocity.getX(), velocity.getY(), velocity.getZ(),
            FidelitySettings.snapshot().entityVelocityEpsilon())) {
            channel.send(observer, new WrapperPlayServerEntityVelocity(state.fakeId(), velocity));
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

    private static Box bounds(BoundingBox box) {
        return new Box(box.getMinX(), box.getMaxX(), box.getMinY(), box.getMaxY(), box.getMinZ(), box.getMaxZ());
    }

    private EntitySnapshot liveSnapshot(Entity entity, boolean hanging) {
        WormholesPlatform.entityPosition(entity, scratchEntityPosition);
        float yaw = (float) scratchEntityPosition[3];
        float pitch = (float) scratchEntityPosition[4];
        if (hanging && entity instanceof Hanging hangingEntity) {
            BlockFace facing = hangingEntity.getFacing();
            scratchLook[0] = facing.getModX();
            scratchLook[1] = facing.getModY();
            scratchLook[2] = facing.getModZ();
        } else {
            Angles.directionInto(yaw, pitch, scratchLook);
        }
        Vector velocity = entity.getVelocity();
        return EntitySnapshot.full(entity.getUniqueId(), entity.getType().getKey().toString(), scratchEntityPosition[0],
            scratchEntityPosition[1], scratchEntityPosition[2], entity.getHeight(), scratchLook[0], scratchLook[1], scratchLook[2], yaw, pitch,
            velocity.getX(), velocity.getY(), velocity.getZ(), entity.isOnGround(), "", "", "", null, null, EntitySnapshot.EMPTY,
            EntitySnapshot.EMPTY, 0);
    }

    static Vector3d playerLabelPosition(Vector3d playerPosition, double playerHeight) {
        return new Vector3d(playerPosition.getX(), PlayerNames.labelY(playerPosition.getY(), playerHeight), playerPosition.getZ());
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
            Component.text(ProjectedEntityIdentity.NAMING.labelText(label), NamedTextColor.WHITE),
            TEXT_DISPLAY_BACKGROUND_INDEX,
            0);
    }

    static List<EntityData<?>> playerLabelTextMetadata(String label) {
        return List.of(new EntityData<Component>(TEXT_DISPLAY_TEXT_INDEX, EntityDataTypes.ADV_COMPONENT,
            Component.text(ProjectedEntityIdentity.NAMING.labelText(label), NamedTextColor.WHITE)));
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
