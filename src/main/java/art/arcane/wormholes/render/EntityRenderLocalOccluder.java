package art.arcane.wormholes.render;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.platform.WormholesPlatform;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.PortalFrame;

final class EntityRenderLocalOccluder {
    private final EntityRenderLocalOcclusionArbiter arbiter;
    private final UUID ownerId;
    private final double[] scratchEntityPosition;
    private boolean localHideOwnershipWarningSent;

    EntityRenderLocalOccluder(EntityRenderLocalOcclusionArbiter arbiter, UUID ownerId) {
        this.arbiter = arbiter;
        this.ownerId = ownerId;
        this.scratchEntityPosition = new double[5];
        this.localHideOwnershipWarningSent = false;
    }

    void hideLocalEntities(Player observer, ILocalPortal localPortal, Frustum4D frustum, double range,
                           double projectionDepth) {
        if (Wormholes.instance == null || observer == null || !observer.isOnline() || localPortal == null) {
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
            reportOwnershipFailure(error);
            return;
        }
        Map<UUID, Entity> desired = new HashMap<UUID, Entity>(Math.max(4, candidates.size()));
        UUID observerId = observer.getUniqueId();
        try {
            for (Entity entity : candidates) {
                if (shouldHideLocalEntity(observerId, entity, origin, frame, frustum,
                    eyeFrontSide, clearance, maxDepth)) {
                    desired.put(entity.getUniqueId(), entity);
                }
            }
        } catch (IllegalStateException error) {
            reportOwnershipFailure(error);
            return;
        }
        arbiter.replace(observer, ownerId, desired);
    }

    void release(Player observer) {
        arbiter.release(observer, ownerId);
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

    private static double dot(double x, double y, double z, PortalFrame frame) {
        return (x * frame.getNormal().x()) + (y * frame.getNormal().y()) + (z * frame.getNormal().z());
    }

    private void reportOwnershipFailure(IllegalStateException error) {
        if (localHideOwnershipWarningSent) {
            return;
        }
        localHideOwnershipWarningSent = true;
        Wormholes plugin = Wormholes.instance;
        Logger logger = plugin == null ? Logger.getLogger("Wormholes") : plugin.getLogger();
        logger.log(Level.WARNING,
            "[spoof] Local entity discovery crossed an unowned Folia region; this projection will retain its prior visibility state.",
            error);
    }
}
