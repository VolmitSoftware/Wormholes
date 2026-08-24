package art.arcane.wormholes.render;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.platform.WormholesPlatform;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.PortalFrame;

final class EntityRenderLocalOccluder {
    private final Map<UUID, Entity> hiddenLocalEntities;
    private final Set<UUID> visibleLocalHides;
    private final double[] scratchEntityPosition;
    private final AtomicBoolean localRestoreRetryScheduled;
    private boolean localHideOwnershipWarningSent;
    private volatile boolean restoreAllRequested;

    EntityRenderLocalOccluder() {
        this.hiddenLocalEntities = new HashMap<UUID, Entity>(16);
        this.visibleLocalHides = new HashSet<UUID>(16);
        this.scratchEntityPosition = new double[5];
        this.localRestoreRetryScheduled = new AtomicBoolean(false);
        this.localHideOwnershipWarningSent = false;
        this.restoreAllRequested = false;
    }

    void clearVisibleHides() {
        visibleLocalHides.clear();
    }

    void requestRestoreAll() {
        restoreAllRequested = true;
    }

    void hideLocalEntities(Player observer, ILocalPortal localPortal, Frustum4D frustum, double range, double projectionDepth) {
        if (Wormholes.instance == null || observer == null || !observer.isOnline()) {
            return;
        }
        Location localCenter = localPortal.getCenter();
        World localWorld = localPortal.getWorld();
        if (localCenter == null || localWorld == null || !localWorld.equals(observer.getWorld())) {
            return;
        }
        PortalFrame frame = localPortal.getFrame();
        Vector origin = localPortal.getOrigin();
        WormholesPlatform.entityPosition(observer, scratchEntityPosition);
        double eyeX = scratchEntityPosition[0];
        double eyeY = scratchEntityPosition[1] + observer.getEyeHeight();
        double eyeZ = scratchEntityPosition[2];
        double eyeDot = dot(eyeX - origin.getX(), eyeY - origin.getY(), eyeZ - origin.getZ(), frame);
        boolean eyeFrontSide = eyeDot >= 0.0D;
        double clearance = PortalProjector.portalPlaneClearance(localPortal.getStructure().getArea(), frame);
        double maxDepth = projectionDepth + clearance;
        double ownedRange = largestOwnedLocalEntityRange(localWorld, localCenter, range);
        if (ownedRange <= 0.0D) {
            return;
        }
        Collection<Entity> candidates;
        try {
            candidates = EntityRenderCaches.nearbyLocalEntities(localPortal, localCenter, ownedRange);
        } catch (IllegalStateException error) {
            reportLocalHideOwnershipFailure(error);
            return;
        }
        restoreAllRequested = false;
        UUID observerId = observer.getUniqueId();
        for (Entity entity : candidates) {
            try {
                if (!shouldHideLocalEntity(observerId, entity, origin, frame, frustum, eyeFrontSide, clearance, maxDepth)) {
                    continue;
                }
                UUID entityId = entity.getUniqueId();
                visibleLocalHides.add(entityId);
                if (hiddenLocalEntities.containsKey(entityId)) {
                    hiddenLocalEntities.put(entityId, entity);
                    continue;
                }
                observer.hideEntity(Wormholes.instance, entity);
                hiddenLocalEntities.put(entityId, entity);
            } catch (IllegalStateException error) {
                reportLocalHideOwnershipFailure(error);
            }
        }
    }

    private static double largestOwnedLocalEntityRange(World world, Location center, double requestedRange) {
        int radius = Math.max(1, (int) Math.ceil(requestedRange));
        while (radius >= 1) {
            int minChunkX = ((int) Math.floor(center.getX() - radius)) >> 4;
            int minChunkZ = ((int) Math.floor(center.getZ() - radius)) >> 4;
            int maxChunkX = ((int) Math.floor(center.getX() + radius)) >> 4;
            int maxChunkZ = ((int) Math.floor(center.getZ() + radius)) >> 4;
            if (WormholesPlatform.isOwnedByCurrentRegion(world, minChunkX, minChunkZ, maxChunkX, maxChunkZ)) {
                return Math.min(requestedRange, radius);
            }
            if (radius == 1) {
                return 0.0D;
            }
            radius = Math.max(1, radius / 2);
        }
        return 0.0D;
    }

    private void reportLocalHideOwnershipFailure(IllegalStateException error) {
        if (localHideOwnershipWarningSent) {
            return;
        }
        localHideOwnershipWarningSent = true;
        Wormholes.instance.getLogger().log(Level.WARNING,
            "[spoof] Local entity occlusion crossed an unowned Folia region; this projection will keep rendering without local occlusion.",
            error);
    }

    private boolean shouldHideLocalEntity(UUID observerId,
                                          Entity entity,
                                          Vector origin,
                                          PortalFrame frame,
                                          Frustum4D frustum,
                                          boolean eyeFrontSide,
                                          double clearance,
                                          double maxDepth) {
        if (entity == null || entity.isDead() || !entity.isValid()) {
            return false;
        }
        if (entity.getUniqueId().equals(observerId)) {
            return false;
        }
        WormholesPlatform.entityPosition(entity, scratchEntityPosition);
        double entityX = scratchEntityPosition[0];
        double entityZ = scratchEntityPosition[2];
        double centerY = scratchEntityPosition[1] + (entity.getHeight() * 0.5D);
        double signedDistance = dot(entityX - origin.getX(), centerY - origin.getY(), entityZ - origin.getZ(), frame);
        if (Math.abs(signedDistance) <= clearance || Math.abs(signedDistance) > maxDepth) {
            return false;
        }
        boolean entityFrontSide = signedDistance >= 0.0D;
        if (entityFrontSide == eyeFrontSide) {
            return false;
        }
        return frustum.containsPrimitive(entityX, centerY, entityZ);
    }

    void restoreLocalEntities(Player observer) {
        if (hiddenLocalEntities.isEmpty() || observer == null || !observer.isOnline() || Wormholes.instance == null) {
            return;
        }
        Iterator<Map.Entry<UUID, Entity>> iterator = hiddenLocalEntities.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Entity> entry = iterator.next();
            if (visibleLocalHides.contains(entry.getKey())) {
                continue;
            }
            Entity entity = entry.getValue();
            try {
                if (entity != null && entity.isValid() && !entity.isDead()) {
                    observer.showEntity(Wormholes.instance, entity);
                }
            } catch (IllegalStateException error) {
                reportLocalHideOwnershipFailure(error);
                continue;
            }
            iterator.remove();
        }
    }

    void restoreAllLocalEntities(Player observer) {
        if (hiddenLocalEntities.isEmpty()) {
            return;
        }
        if (observer == null || !observer.isOnline()) {
            hiddenLocalEntities.clear();
            return;
        }
        if (Wormholes.instance == null || !FoliaScheduler.isOwnedByCurrentRegion(observer)) {
            scheduleLocalEntityRestore(observer);
            return;
        }
        if (!showAllLocalEntities(observer)) {
            scheduleLocalEntityRestore(observer);
        }
    }

    private boolean showAllLocalEntities(Player observer) {
        return ProjectedEntityRenderer.removeCompletedRestores(hiddenLocalEntities, entity -> tryShowLocalEntity(observer, entity));
    }

    private boolean tryShowLocalEntity(Player observer, Entity entity) {
        try {
            if (entity != null && entity.isValid() && !entity.isDead()) {
                observer.showEntity(Wormholes.instance, entity);
            }
            return true;
        } catch (IllegalStateException error) {
            reportLocalHideOwnershipFailure(error);
            return false;
        }
    }

    private void scheduleLocalEntityRestore(Player observer) {
        if (hiddenLocalEntities.isEmpty() || observer == null || !observer.isOnline()
            || Wormholes.instance == null || !localRestoreRetryScheduled.compareAndSet(false, true)) {
            return;
        }
        boolean scheduled = FoliaScheduler.runEntity(Wormholes.instance, observer, () -> {
            localRestoreRetryScheduled.set(false);
            if (restoreAllRequested) {
                restoreAllLocalEntities(observer);
            } else {
                restoreLocalEntities(observer);
            }
        }, 1L);
        if (!scheduled) {
            localRestoreRetryScheduled.set(false);
        }
    }

    private static double dot(double x, double y, double z, PortalFrame frame) {
        return (x * frame.getNormal().x()) + (y * frame.getNormal().y()) + (z * frame.getNormal().z());
    }
}
