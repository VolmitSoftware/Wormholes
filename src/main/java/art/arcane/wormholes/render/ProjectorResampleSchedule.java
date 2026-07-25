package art.arcane.wormholes.render;

import org.bukkit.World;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.NetworkViewQuality;
import art.arcane.wormholes.render.view.ProjectionWorldView;
import art.arcane.wormholes.render.view.RemoteWorldView;

final class ProjectorResampleSchedule {
    private static final int STABLE_RESAMPLE_BACKSTOP_TICKS = 40;

    private final ILocalPortal portal;
    private long projectCallCount;
    private long entityPassCount;
    private long lastSourceViewRevision;
    private long lastResampleVersion;
    private long lastRemoteRevision;
    private boolean pendingRemoteResample;
    private int remoteResendStage;

    ProjectorResampleSchedule(ILocalPortal portal) {
        this.portal = portal;
        this.projectCallCount = 0L;
        this.entityPassCount = 0L;
        this.lastSourceViewRevision = -1L;
        this.lastResampleVersion = -1L;
        this.lastRemoteRevision = -1L;
        this.pendingRemoteResample = false;
        this.remoteResendStage = 0;
    }

    long passCount() {
        return projectCallCount;
    }

    void beginBlockPass() {
        projectCallCount++;
    }

    boolean entityUpdateDue() {
        boolean due = entityUpdateDueNow();
        entityPassCount++;
        return due;
    }

    private boolean entityUpdateDueNow() {
        if (usesStandardViewQuality()) {
            return true;
        }
        int intervalTicks = Math.max(1, portal.getNetworkViewEntityIntervalTicks());
        int globalTicks = Math.max(1, Settings.ENTITY_UPDATE_INTERVAL_TICKS);
        int passInterval = Math.max(1, (intervalTicks + globalTicks - 1) / globalTicks);
        return (entityPassCount % passInterval) == 0L;
    }

    void noteRemoteRevision(long revision) {
        if (revision != lastRemoteRevision) {
            lastRemoteRevision = revision;
            pendingRemoteResample = true;
        }
    }

    boolean fullRemoteResendDue() {
        if (remoteResendStage == 0 && projectCallCount >= 20L) {
            remoteResendStage = 1;
            return true;
        }
        if (remoteResendStage == 1 && projectCallCount >= 60L) {
            remoteResendStage = 2;
            return true;
        }
        return false;
    }

    boolean isRemoteResamplePending() {
        return pendingRemoteResample;
    }

    boolean consumeForcedResample(boolean stableResample) {
        boolean forced = stableResample || pendingRemoteResample;
        pendingRemoteResample = false;
        if (forced && Wormholes.projectionChangeTracker != null) {
            lastResampleVersion = Wormholes.projectionChangeTracker.currentVersion();
        }
        return forced;
    }

    boolean stableResample(boolean firstProjectionDone,
                           ProjectionWorldView sourceView,
                           World destWorld,
                           double destinationOriginX,
                           double destinationOriginZ) {
        if (!firstProjectionDone) {
            return true;
        }
        int cadence = stablePassInterval(stableResampleCadenceTicks());
        if (sourceView instanceof RemoteWorldView) {
            return (projectCallCount % cadence) == 0L;
        }
        if (sourceView.getRevision() != lastSourceViewRevision) {
            return true;
        }
        int backstop = stablePassInterval(fullRefreshBackstopTicks());
        if ((projectCallCount % backstop) == 0L) {
            return true;
        }
        if ((projectCallCount % cadence) != 0L) {
            return false;
        }
        return destinationDirty(destWorld, destinationOriginX, destinationOriginZ, lastResampleVersion);
    }

    boolean destinationDirty(World destWorld, double originX, double originZ, long sinceVersion) {
        if (destWorld == null || Wormholes.projectionChangeTracker == null) {
            return true;
        }
        double depth = portal.getNetworkViewDepth() + 2.0D;
        int minChunkX = ((int) Math.floor(originX - depth)) >> 4;
        int maxChunkX = ((int) Math.floor(originX + depth)) >> 4;
        int minChunkZ = ((int) Math.floor(originZ - depth)) >> 4;
        int maxChunkZ = ((int) Math.floor(originZ + depth)) >> 4;
        return Wormholes.projectionChangeTracker.dirtySince(destWorld.getUID(), minChunkX, minChunkZ, maxChunkX, maxChunkZ, sinceVersion);
    }

    boolean lightingUpdatePass(boolean firstProjectionDone) {
        if (!firstProjectionDone) {
            return true;
        }

        int projectionInterval = Math.max(1, Settings.PROJECTION_REFRESH_INTERVAL_TICKS);
        int lightingInterval = Math.max(1, Settings.LIGHTING_REFRESH_INTERVAL_TICKS);
        int projectPassInterval = Math.max(1, (lightingInterval + projectionInterval - 1) / projectionInterval);
        return (projectCallCount % projectPassInterval) == 0L;
    }

    void noteSourceViewRevision(long revision) {
        lastSourceViewRevision = revision;
    }

    void invalidateDestination() {
        pendingRemoteResample = true;
        lastSourceViewRevision = -1L;
        lastResampleVersion = -1L;
    }

    private boolean usesStandardViewQuality() {
        return NetworkViewQuality.from(
            portal.getNetworkViewDepth(),
            portal.getNetworkViewHeartbeatTicks(),
            portal.getNetworkViewEntityIntervalTicks(),
            portal.getNetworkViewUnsubscribeGraceSeconds()) == NetworkViewQuality.STANDARD;
    }

    private int stableResampleCadenceTicks() {
        if (usesStandardViewQuality()) {
            return Settings.PROJECTION_STABLE_CELL_RESAMPLE_INTERVAL_TICKS;
        }
        return Math.max(1, portal.getNetworkViewHeartbeatTicks());
    }

    private int fullRefreshBackstopTicks() {
        if (usesStandardViewQuality()) {
            return STABLE_RESAMPLE_BACKSTOP_TICKS;
        }
        return Math.max(1, portal.getNetworkViewHeartbeatTicks());
    }

    private static int stablePassInterval(int intervalTicks) {
        int projectionInterval = Math.max(1, Settings.PROJECTION_REFRESH_INTERVAL_TICKS);
        int resampleInterval = Math.max(1, intervalTicks);
        return Math.max(1, (resampleInterval + projectionInterval - 1) / projectionInterval);
    }
}
