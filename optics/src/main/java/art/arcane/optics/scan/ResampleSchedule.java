package art.arcane.optics.scan;


import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import art.arcane.optics.view.WorldChangeTracker;

public final class ResampleSchedule {
    private static final int STABLE_RESAMPLE_BACKSTOP_TICKS = 1_200;
    private static final WorldChangeTracker.ChangeFilter ANY_CHANGE = new WorldChangeTracker.ChangeFilter() {
        @Override
        public boolean affectsBlock(int x, int y, int z) {
            return true;
        }

        @Override
        public boolean affectsColumn(int chunkX, int chunkZ) {
            return true;
        }
    };

    private final Supplier<ViewCadence> view;
    private final Supplier<WorldChangeTracker> tracker;
    private final Supplier<Cadence> cadence;
    private Cadence currentCadence;
    private ViewCadence currentView;
    private long projectCallCount;
    private long entityPassCount;
    private long lastSourceViewRevision;
    private long lastResampleVersion;
    private long lastRemoteRevision;
    private boolean pendingRemoteResample;
    private boolean pendingDestinationChange;
    private int remoteResendStage;

    public ResampleSchedule(Supplier<ViewCadence> view, Supplier<WorldChangeTracker> tracker, Supplier<Cadence> cadence) {
        this.view = Objects.requireNonNull(view);
        this.tracker = Objects.requireNonNull(tracker);
        this.cadence = Objects.requireNonNull(cadence);
        this.projectCallCount = 0L;
        this.entityPassCount = 0L;
        this.lastSourceViewRevision = -1L;
        this.lastResampleVersion = -1L;
        this.lastRemoteRevision = -1L;
        this.pendingRemoteResample = false;
        this.pendingDestinationChange = false;
        this.remoteResendStage = 0;
    }

    public long passCount() {
        return projectCallCount;
    }

    public void beginBlockPass() {
        projectCallCount++;
        currentCadence = cadence.get();
        currentView = view.get();
    }

    public boolean entityUpdateDue() {
        boolean due = entityUpdateDueNow();
        entityPassCount++;
        return due;
    }

    public void noteRemoteRevision(long revision) {
        if (revision != lastRemoteRevision) {
            lastRemoteRevision = revision;
            pendingRemoteResample = true;
        }
    }

    public boolean fullRemoteResendDue() {
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

    public boolean isRemoteResamplePending() {
        return pendingRemoteResample;
    }

    public boolean consumeForcedResample(boolean stableResample) {
        boolean forced = stableResample || pendingRemoteResample;
        pendingRemoteResample = false;
        if (forced) {
            pendingDestinationChange = false;
            WorldChangeTracker changes = tracker.get();
            if (changes != null) {
                lastResampleVersion = changes.currentVersion();
            }
        }
        return forced;
    }

    public boolean stableResample(boolean firstProjectionDone,
                                  long sourceRevision,
                                  boolean remoteSource,
                                  UUID destWorldId,
                                  double destinationOriginX,
                                  double destinationOriginZ,
                                  ProjectorRemoteFootprint footprint) {
        if (!firstProjectionDone) {
            return true;
        }
        if (sourceRevision != lastSourceViewRevision) {
            return true;
        }
        if (remoteSource) {
            return false;
        }
        if (!pendingDestinationChange) {
            long through = destinationUnaffectedThrough(destWorldId, destinationOriginX, destinationOriginZ, lastResampleVersion, footprint);
            if (through == WorldChangeTracker.AFFECTED) {
                pendingDestinationChange = true;
            } else {
                lastResampleVersion = through;
            }
        }
        int refreshIntervalTicks = cadence().refreshIntervalTicks();
        int backstop = stablePassInterval(fullRefreshBackstopTicks(), refreshIntervalTicks);
        if ((projectCallCount % backstop) == 0L) {
            return true;
        }
        int stableCadence = stablePassInterval(stableResampleCadenceTicks(), refreshIntervalTicks);
        return pendingDestinationChange && (projectCallCount % stableCadence) == 0L;
    }

    public long destinationUnaffectedThrough(UUID destWorldId, double originX, double originZ, long sinceVersion,
                                             ProjectorRemoteFootprint footprint) {
        WorldChangeTracker changes = tracker.get();
        if (destWorldId == null || changes == null) {
            return WorldChangeTracker.AFFECTED;
        }
        if (!footprint.nested()) {
            return changes.unaffectedThrough(destWorldId, footprint.queryMinChunkX(), footprint.queryMinChunkZ(),
                footprint.queryMaxChunkX(), footprint.queryMaxChunkZ(), sinceVersion, footprint);
        }
        double depth = view().depth() + 2.0D;
        return changes.unaffectedThrough(destWorldId, ((int) Math.floor(originX - depth)) >> 4,
            ((int) Math.floor(originZ - depth)) >> 4, ((int) Math.floor(originX + depth)) >> 4,
            ((int) Math.floor(originZ + depth)) >> 4, sinceVersion, ANY_CHANGE);
    }

    public boolean lightingUpdatePass(boolean firstProjectionDone) {
        if (!firstProjectionDone) {
            return true;
        }

        Cadence current = cadence();
        int projectionInterval = Math.max(1, current.refreshIntervalTicks());
        int lightingInterval = Math.max(1, current.lightingRefreshIntervalTicks());
        int projectPassInterval = Math.max(1, (lightingInterval + projectionInterval - 1) / projectionInterval);
        return (projectCallCount % projectPassInterval) == 0L;
    }

    public void noteSourceViewRevision(long revision) {
        lastSourceViewRevision = revision;
    }

    public void invalidateDestination() {
        pendingRemoteResample = true;
        pendingDestinationChange = false;
        lastSourceViewRevision = -1L;
        lastResampleVersion = -1L;
    }

    private Cadence cadence() {
        if (currentCadence == null) {
            currentCadence = cadence.get();
        }
        return currentCadence;
    }

    private ViewCadence view() {
        if (currentView == null) {
            currentView = view.get();
        }
        return currentView;
    }

    private boolean entityUpdateDueNow() {
        ViewCadence current = view();
        if (current.globalCadence()) {
            return true;
        }
        int intervalTicks = Math.max(1, current.entityIntervalTicks());
        int globalTicks = Math.max(1, cadence().entityUpdateIntervalTicks());
        int passInterval = Math.max(1, (intervalTicks + globalTicks - 1) / globalTicks);
        return (entityPassCount % passInterval) == 0L;
    }

    private int stableResampleCadenceTicks() {
        ViewCadence current = view();
        if (current.globalCadence()) {
            return cadence().stableCellResampleIntervalTicks();
        }
        return Math.max(1, current.heartbeatTicks());
    }

    private int fullRefreshBackstopTicks() {
        ViewCadence current = view();
        if (current.globalCadence()) {
            return STABLE_RESAMPLE_BACKSTOP_TICKS;
        }
        return Math.max(1, current.heartbeatTicks());
    }

    private static int stablePassInterval(int intervalTicks, int refreshIntervalTicks) {
        int projectionInterval = Math.max(1, refreshIntervalTicks);
        int resampleInterval = Math.max(1, intervalTicks);
        return Math.max(1, (resampleInterval + projectionInterval - 1) / projectionInterval);
    }

    public record Cadence(int refreshIntervalTicks, int stableCellResampleIntervalTicks, int lightingRefreshIntervalTicks,
                          int entityUpdateIntervalTicks) {
    }
}
