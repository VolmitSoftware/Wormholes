package art.arcane.wormholes.render;

import art.arcane.optics.math.Vec3d;
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
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.platform.WormholesPlatform;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.occlusion.LocalOcclusionArbiter;
import art.arcane.optics.volume.LocalEntityEnvelope;
import art.arcane.optics.volume.ViewVolume;
import art.arcane.optics.volume.ApertureSlab;

final class EntityRenderLocalOccluder {
    private static final double LABEL_HORIZONTAL_MARGIN = 0.5D;
    private static final double LABEL_VERTICAL_MARGIN = 0.75D;

    private final LocalOcclusionArbiter<Player, Entity> arbiter;
    private final UUID ownerId;
    private final double[] scratchEntityPosition;
    private boolean localHideOwnershipWarningSent;

    EntityRenderLocalOccluder(LocalOcclusionArbiter<Player, Entity> arbiter, UUID ownerId) {
        this.arbiter = arbiter;
        this.ownerId = ownerId;
        this.scratchEntityPosition = new double[5];
        this.localHideOwnershipWarningSent = false;
    }

    void hideLocalEntities(Player observer, ILocalPortal localPortal, ViewVolume frustum,
                           double projectionDepth) {
        if (Wormholes.instance == null || observer == null || !observer.isOnline() || localPortal == null) {
            return;
        }
        Location localCenter = localPortal.getCenter();
        World localWorld = localPortal.getWorld();
        if (localCenter == null || localWorld == null || !localWorld.equals(observer.getWorld())) {
            return;
        }
        Frame frame = localPortal.getFrame();
        Vec3d origin = localPortal.getOrigin();
        WormholesPlatform.entityPosition(observer, scratchEntityPosition);
        double eyeX = scratchEntityPosition[0];
        double eyeY = scratchEntityPosition[1] + observer.getEyeHeight();
        double eyeZ = scratchEntityPosition[2];
        boolean eyeFrontSide = ApertureSlab.side(frame, origin.getX(), origin.getY(), origin.getZ(), eyeX, eyeY, eyeZ);
        ApertureSlab volume = ApertureSlab.of(localPortal.getStructure().getArea(), frame,
            ApertureSlab.plane(frame, origin.getX(), origin.getY(), origin.getZ()), eyeFrontSide, projectionDepth, 0.0D);
        double ownedRange = largestOwnedLocalEntityRange(localWorld, localCenter, volume.maxDepth());
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
                if (shouldHideLocalEntity(observerId, entity, volume, frustum)) {
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

    private boolean shouldHideLocalEntity(UUID observerId, Entity entity, ApertureSlab volume, ViewVolume frustum) {
        if (entity == null || entity.isDead() || !entity.isValid()) {
            return false;
        }
        if (entity.getUniqueId().equals(observerId)) {
            return false;
        }
        BoundingBox box = entity.getBoundingBox();
        return LocalEntityEnvelope.envelopeFullyProjected(
            box.getMinX() - LABEL_HORIZONTAL_MARGIN,
            box.getMinY(),
            box.getMinZ() - LABEL_HORIZONTAL_MARGIN,
            box.getMaxX() + LABEL_HORIZONTAL_MARGIN,
            box.getMaxY() + LABEL_VERTICAL_MARGIN,
            box.getMaxZ() + LABEL_HORIZONTAL_MARGIN,
            volume, frustum);
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
