package art.arcane.wormholes.network.view;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.TextureProperty;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import art.arcane.wormholes.EffectManager;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.network.WireMessage;
import art.arcane.wormholes.platform.WormholesPlatform;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class ViewEntityPipeline {
    private static final class EntityCaptureContext {
        private final ViewServer.EntityCaptureToken token;
        private final Set<UUID> profileUpdates = ConcurrentHashMap.newKeySet();
        private final Map<UUID, ViewServer.BlobCaptureState> blobStateUpdates = new ConcurrentHashMap<>();

        private EntityCaptureContext(ViewServer.EntityCaptureToken token) {
            this.token = token;
        }
    }

    private final ViewSessionRegistry registry;
    private final ViewTimeDelivery timeDelivery;
    private final ViewEntityPublisher publisher;
    private volatile EntityRateScheduler entityRateScheduler;
    private volatile EntityRateScheduler.Bands lastBands;
    private volatile long tickCounter;

    ViewEntityPipeline(ViewSessionRegistry registry, ViewTimeDelivery timeDelivery, ViewEntityPublisher publisher) {
        this.registry = registry;
        this.timeDelivery = timeDelivery;
        this.publisher = publisher;
    }

    EntityRateScheduler scheduler() {
        return entityRateScheduler;
    }

    void advanceTick() {
        tickCounter += ViewServer.DIRTY_DRAIN_INTERVAL_TICKS;
    }

    boolean isIntervalDue(int intervalTicks) {
        long interval = Math.max(ViewServer.DIRTY_DRAIN_INTERVAL_TICKS, intervalTicks);
        long steps = Math.max(1L, interval / ViewServer.DIRTY_DRAIN_INTERVAL_TICKS);
        long normalized = steps * ViewServer.DIRTY_DRAIN_INTERVAL_TICKS;
        return tickCounter % normalized < ViewServer.DIRTY_DRAIN_INTERVAL_TICKS;
    }

    void expireCaptureIfNeeded(ViewSession session) {
        ViewServer.EntityCaptureToken token = session.activeEntityCapture;
        if (token != null && token.isExpired()) {
            completeEntityCaptureFailure(session, token, entityCaptureTimeout(token));
        }
    }

    void beginCapture(ViewSession session) {
        if (!session.entityCaptureRunning.compareAndSet(false, true)) {
            return;
        }
        ViewServer.EntityCaptureToken token = new ViewServer.EntityCaptureToken(
            session.entityCaptureGeneration.incrementAndGet(),
            System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ViewServer.ENTITY_CAPTURE_DEADLINE_MILLIS));
        session.activeEntityCapture = token;
        boolean scheduled = FoliaScheduler.runRegion(Wormholes.instance, session.world, session.centerChunkX, session.centerChunkZ,
            () -> captureEntities(session, token));
        if (!scheduled) {
            completeEntityCaptureFailure(session, token,
                new IllegalStateException("Entity capture center region rejected scheduling"));
        }
    }

    void forwardEntityEvent(UUID entityId, boolean hurt, int animationOrdinal, float yaw) {
        if (registry.isEmpty()) {
            return;
        }
        for (ViewSession session : registry.sessions()) {
            if (session.lastCapturedSnapshots.containsKey(entityId) && !session.peers.isEmpty()) {
                WireMessage.ViewEntityAnimation message = new WireMessage.ViewEntityAnimation(session.portalId, entityId, hurt, animationOrdinal, yaw);
                registry.network().sendToPeers(session.peers, message);
            }
        }
    }

    private void captureEntities(ViewSession session, ViewServer.EntityCaptureToken token) {
        if (!registry.isEntityCaptureActive(session, token)) {
            return;
        }
        EntityCaptureContext context = new EntityCaptureContext(token);
        try {
            int skyDarken = art.arcane.wormholes.render.view.ProjectionWorldView.computeSkyDarken(session.world.getTime());
            if (skyDarken != session.lastSkyDarken) {
                session.lastSkyDarken = skyDarken;
                for (String peerName : session.peers) {
                    timeDelivery.queue(session, peerName, skyDarken);
                }
            }
            long entityTick = tickCounter;
            NetworkConfig.ViewConfig viewConfig = activeViewConfig();
            EntityRateScheduler scheduler = ensureScheduler(viewConfig);
            boolean deltaEnabled = viewConfig.entityDeltaEnabled;
            if (!FoliaScheduler.isFoliaThreading(Bukkit.getServer())) {
                ViewServer.EntityAdmission<Entity> admission = new ViewServer.EntityAdmission<>(ViewServer.MAX_CAPTURED_ENTITIES);
                for (Entity entity : session.world.getNearbyEntities(session.bounds)) {
                    if (entity.isDead() || !entity.isValid() || EffectManager.isPortalEffectEntity(entity)) {
                        continue;
                    }
                    admission.admit(entityRank(session, entity), entity);
                }
                Map<UUID, EntityVisual> captured = new HashMap<>();
                for (Entity entity : admission.selectedEntities()) {
                    if (!registry.isEntityCaptureActive(session, token)) {
                        return;
                    }
                    if (entity.isDead() || !entity.isValid() || EffectManager.isPortalEffectEntity(entity)) {
                        continue;
                    }
                    EntityVisual currentFull = captureEntityVisualFull(session, context, entity, entityTick);
                    captured.put(currentFull.id(), currentFull);
                }
                completeEntityCaptureSuccess(session, context, entityTick, scheduler, deltaEnabled, captured);
                return;
            }
            ViewServer.EntityAdmission<Entity> admission = new ViewServer.EntityAdmission<>(ViewServer.MAX_CAPTURED_ENTITIES);
            List<CompletableFuture<Void>> partitions = new ArrayList<>(session.columns.size());
            for (long[] column : session.columns) {
                int chunkX = (int) column[0];
                int chunkZ = (int) column[1];
                BoundingBox partitionBounds = ViewServer.captureBoundsForChunk(session.bounds, chunkX, chunkZ);
                if (partitionBounds == null) {
                    continue;
                }
                CompletableFuture<Void> partition = new CompletableFuture<>();
                partitions.add(partition);
                boolean scheduled = FoliaScheduler.runRegion(Wormholes.instance, session.world, chunkX, chunkZ, () -> {
                    try {
                        for (Entity entity : session.world.getNearbyEntities(partitionBounds)) {
                            if (!FoliaScheduler.isOwnedByCurrentRegion(entity)
                                || entity.isDead()
                                || !entity.isValid()
                                || EffectManager.isPortalEffectEntity(entity)) {
                                continue;
                            }
                            ViewServer.EntityRank rank = entityRank(session, entity);
                            admission.admit(rank, entity);
                        }
                        partition.complete(null);
                    } catch (Throwable error) {
                        partition.completeExceptionally(error);
                    }
                });
                if (!scheduled) {
                    partition.completeExceptionally(new IllegalStateException("Entity capture partition rejected scheduling for " + chunkX + "," + chunkZ));
                }
            }
            CompletableFuture.allOf(partitions.toArray(CompletableFuture[]::new)).whenComplete((ignored, error) -> {
                if (error != null) {
                    completeEntityCaptureFailure(session, token, error);
                } else {
                    captureAdmittedEntities(session, context, admission.selectedEntities(), entityTick, scheduler, deltaEnabled);
                }
            });
        } catch (Throwable e) {
            completeEntityCaptureFailure(session, token, e);
        }
    }

    private void captureAdmittedEntities(ViewSession session, EntityCaptureContext context, List<Entity> entities, long entityTick,
                                         EntityRateScheduler scheduler, boolean deltaEnabled) {
        if (!registry.isEntityCaptureActive(session, context.token)) {
            return;
        }
        Map<UUID, EntityVisual> captured = new ConcurrentHashMap<>();
        List<CompletableFuture<Void>> captures = new ArrayList<>(entities.size());
        for (Entity entity : entities) {
            CompletableFuture<Void> capture = new CompletableFuture<>();
            captures.add(capture);
            boolean scheduled = WormholesPlatform.scheduleEntity(Wormholes.instance, entity, () -> {
                try {
                    if (!registry.isEntityCaptureActive(session, context.token)) {
                        capture.complete(null);
                        return;
                    }
                    Location location = entity.getLocation();
                    if (entity.isDead()
                        || !entity.isValid()
                        || EffectManager.isPortalEffectEntity(entity)
                        || !session.bounds.contains(location.getX(), location.getY(), location.getZ())) {
                        capture.complete(null);
                        return;
                    }
                    EntityVisual visual = captureEntityVisualFull(session, context, entity, entityTick);
                    if (registry.isEntityCaptureActive(session, context.token)) {
                        captured.put(visual.id(), visual);
                    }
                    capture.complete(null);
                } catch (Throwable error) {
                    capture.completeExceptionally(error);
                }
            }, () -> capture.complete(null), 0L);
            if (!scheduled) {
                capture.complete(null);
            }
        }
        CompletableFuture.allOf(captures.toArray(CompletableFuture[]::new)).whenComplete((ignored, error) -> {
            if (error != null) {
                completeEntityCaptureFailure(session, context.token, error);
                return;
            }
            completeEntityCaptureSuccess(session, context, entityTick, scheduler, deltaEnabled, captured);
        });
    }

    private void completeEntityCaptureSuccess(ViewSession session, EntityCaptureContext context, long entityTick,
                                              EntityRateScheduler scheduler, boolean deltaEnabled,
                                              Map<UUID, EntityVisual> captured) {
        ViewServer.EntityCaptureToken token = context.token;
        if (!registry.isEntityCaptureActive(session, token)) {
            return;
        }
        if (!token.tryCompleteBeforeDeadline()) {
            completeEntityCaptureFailure(session, token, entityCaptureTimeout(token));
            return;
        }
        try {
            session.sentProfiles.addAll(context.profileUpdates);
            session.blobCaptureStates.putAll(context.blobStateUpdates);
            for (EntityVisual visual : captured.values()) {
                session.lastCapturedSnapshots.put(visual.id(), visual);
            }
            publisher.publish(session, entityTick, scheduler, deltaEnabled, captured);
        } finally {
            finishEntityCapture(session, token);
        }
    }

    private void completeEntityCaptureFailure(ViewSession session, ViewServer.EntityCaptureToken token, Throwable error) {
        if (!registry.isEntityCaptureActive(session, token) || !token.tryComplete()) {
            return;
        }
        try {
            publisher.publishEmptyPresence(session, error);
        } finally {
            finishEntityCapture(session, token);
        }
    }

    private TimeoutException entityCaptureTimeout(ViewServer.EntityCaptureToken token) {
        return new TimeoutException(
            "Entity capture generation " + token.generation() + " exceeded " + ViewServer.ENTITY_CAPTURE_DEADLINE_MILLIS + "ms");
    }

    private void finishEntityCapture(ViewSession session, ViewServer.EntityCaptureToken token) {
        if (session.activeEntityCapture == token) {
            session.activeEntityCapture = null;
            session.entityCaptureRunning.set(false);
        }
    }

    private static ViewServer.EntityRank entityRank(ViewSession session, Entity entity) {
        Location location = entity.getLocation();
        double dx = location.getX() - session.portalCenterX;
        double dy = location.getY() - session.portalCenterY;
        double dz = location.getZ() - session.portalCenterZ;
        return new ViewServer.EntityRank(entity.getUniqueId(), entity instanceof Player, (dx * dx) + (dy * dy) + (dz * dz));
    }

    private EntityVisual captureEntityVisualFull(ViewSession session, EntityCaptureContext context, Entity entity, long entityTick) {
        Location location = entity.getLocation();
        Vector look = entity instanceof LivingEntity living ? living.getEyeLocation().getDirection() : location.getDirection();
        Vector velocity = entity.getVelocity();
        String playerName = "";
        String textureValue = "";
        String textureSignature = "";
        if (entity instanceof Player player) {
            playerName = player.getName();
            UUID playerId = player.getUniqueId();
            if (!session.sentProfiles.contains(playerId) && context.profileUpdates.add(playerId)) {
                String[] textures = playerTextures(player);
                textureValue = textures[0];
                textureSignature = textures[1];
            }
        }
        UUID passengerOf = entity.getVehicle() == null ? null : entity.getVehicle().getUniqueId();
        UUID leashHolder = null;
        if (entity instanceof LivingEntity living && living.isLeashed()) {
            try {
                Entity holder = living.getLeashHolder();
                if (holder != null) {
                    leashHolder = holder.getUniqueId();
                }
            } catch (IllegalStateException ignored) {
            }
        }
        EntityVisual previousVisual = session.lastCapturedSnapshots.get(entity.getUniqueId());
        ViewServer.BlobCaptureState previousBlobState = session.blobCaptureStates.get(entity.getUniqueId());
        Pose pose = entity.getPose();
        boolean onFire = entity.getFireTicks() > 0;
        int equipmentSignature = equipmentSignature(entity);
        byte[] metadata;
        byte[] equipment;
        if (ViewServer.shouldRecaptureBlobs(previousVisual, previousBlobState, entityTick, ViewServer.BLOB_RECAPTURE_INTERVAL_TICKS, pose, onFire, equipmentSignature)) {
            metadata = PacketBlobs.captureMetadata(entity);
            equipment = PacketBlobs.captureEquipment(entity);
            context.blobStateUpdates.put(entity.getUniqueId(), new ViewServer.BlobCaptureState(entityTick, pose, onFire, equipmentSignature));
        } else {
            metadata = previousVisual.metadata();
            equipment = previousVisual.equipment();
        }
        return EntityVisual.full(
            entity.getUniqueId(),
            entity.getType().getKey().toString(),
            location.getX(), location.getY(), location.getZ(),
            entity.getHeight(),
            look.getX(), look.getY(), look.getZ(),
            location.getYaw(), location.getPitch(),
            velocity.getX(), velocity.getY(), velocity.getZ(),
            entity.isOnGround(),
            playerName,
            textureValue,
            textureSignature,
            passengerOf,
            leashHolder,
            metadata,
            equipment,
            0
        );
    }

    private static int equipmentSignature(Entity entity) {
        if (!(entity instanceof LivingEntity living)) {
            return 0;
        }
        EntityEquipment equipment = living.getEquipment();
        if (equipment == null) {
            return 0;
        }
        int signature = 1;
        signature = 31 * signature + itemSignature(equipment.getHelmet());
        signature = 31 * signature + itemSignature(equipment.getChestplate());
        signature = 31 * signature + itemSignature(equipment.getLeggings());
        signature = 31 * signature + itemSignature(equipment.getBoots());
        signature = 31 * signature + itemSignature(equipment.getItemInMainHand());
        signature = 31 * signature + itemSignature(equipment.getItemInOffHand());
        return signature;
    }

    private static int itemSignature(ItemStack stack) {
        if (stack == null) {
            return 0;
        }
        return (stack.getType().ordinal() * 31) + stack.getAmount();
    }

    private static NetworkConfig.ViewConfig activeViewConfig() {
        if (Wormholes.settings == null) {
            return new NetworkConfig.ViewConfig();
        }
        NetworkConfig network = Wormholes.settings.getNetwork();
        if (network == null || network.view == null) {
            return new NetworkConfig.ViewConfig();
        }
        return network.view;
    }

    private EntityRateScheduler ensureScheduler(NetworkConfig.ViewConfig view) {
        EntityRateScheduler.Bands desiredBands = new EntityRateScheduler.Bands(
            view.entityRateNearRange, view.entityRateMidRange, view.entityRateFarRange,
            view.entityRateNearHz, view.entityRateMidHz, view.entityRateFarHz, view.entityRateVeryFarHz
        );
        EntityRateScheduler current = entityRateScheduler;
        if (current != null && desiredBands.equals(lastBands)) {
            return current;
        }
        EntityRateScheduler fresh = new EntityRateScheduler(desiredBands);
        entityRateScheduler = fresh;
        lastBands = desiredBands;
        return fresh;
    }

    private static String[] playerTextures(Player player) {
        try {
            UserProfile profile = PacketEvents.getAPI().getPlayerManager().getUser(player).getProfile();
            if (profile != null) {
                for (TextureProperty property : profile.getTextureProperties()) {
                    if ("textures".equals(property.getName())) {
                        return new String[]{property.getValue(), property.getSignature() == null ? "" : property.getSignature()};
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return new String[]{"", ""};
    }
}
