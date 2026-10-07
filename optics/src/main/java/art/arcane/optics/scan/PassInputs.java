package art.arcane.optics.scan;

import art.arcane.optics.fidelity.AtmosphereMode;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.volume.LodProfile;

public final class PassInputs {
    boolean committed;
    long committedRevision;
    boolean committedCoarse;
    boolean camera;
    double cameraX;
    double cameraY;
    double cameraZ;
    double eyeX;
    double eyeY;
    double eyeZ;
    Frame localFrame;
    double localX;
    double localY;
    double localZ;
    Frame remoteFrame;
    double remoteX;
    double remoteY;
    double remoteZ;
    boolean mirror;
    int quarterTurns;
    int depth;
    int lateral;
    double projectionDistance;
    double aperturePadding;
    double nearPlanePadding;
    double cullingRatio;
    double revealMargin;
    int maxCells;
    int recursionDepth;
    boolean buriedCellCulling;
    boolean observerOcclusion;
    LodProfile lodProfile;
    boolean lodMergeRuns;
    int lodDistance;
    int lodCutoff;
    boolean blockEntities;
    boolean blackout;
    int blackoutColor;
    boolean fogPlate;
    AtmosphereMode atmosphere;
    long apertureRevision;
    long destinationIdentity;
    boolean hasProjection;
    boolean invalidated;
    boolean fullSendPending;
    boolean dissolving;
    boolean unresolvedOcclusion;
    boolean stableResample;
    boolean remoteResamplePending;
    boolean lightingDue;
    boolean localDirty;
    boolean holdsExposed;
    boolean cullingChanged;
    boolean recursiveSamplesCached;
    boolean coarse;
    boolean occlusionResumable;
    boolean destinationDirty;
    boolean destinationOverBudget;
    boolean localStale;

    public void committed(boolean committed, long revision, boolean coarse) {
        this.committed = committed;
        this.committedRevision = revision;
        this.committedCoarse = coarse;
    }

    public void camera(boolean present, double x, double y, double z) {
        camera = present;
        cameraX = x;
        cameraY = y;
        cameraZ = z;
    }

    public void eye(double x, double y, double z) {
        eyeX = x;
        eyeY = y;
        eyeZ = z;
    }

    public void local(Frame frame, double x, double y, double z) {
        localFrame = frame;
        localX = x;
        localY = y;
        localZ = z;
    }

    public void remote(Frame frame, double x, double y, double z) {
        remoteFrame = frame;
        remoteX = x;
        remoteY = y;
        remoteZ = z;
    }

    public void mirror(boolean mirror, int quarterTurns) {
        this.mirror = mirror;
        this.quarterTurns = quarterTurns;
    }

    public void extent(int depth, int lateral, double projectionDistance) {
        this.depth = depth;
        this.lateral = lateral;
        this.projectionDistance = projectionDistance;
    }

    public void padding(double aperture, double nearPlane) {
        aperturePadding = aperture;
        nearPlanePadding = nearPlane;
    }

    public void frustum(double cullingRatio, double revealMargin) {
        this.cullingRatio = cullingRatio;
        this.revealMargin = revealMargin;
    }

    public void limits(int maxCells, int recursionDepth) {
        this.maxCells = maxCells;
        this.recursionDepth = recursionDepth;
    }

    public void scanMode(ScanMode mode) {
        buriedCellCulling = mode.buriedCellCulling();
        observerOcclusion = mode.observerOcclusion();
    }

    public void lod(LodProfile profile, boolean mergeRuns, int distance, int cutoff) {
        lodProfile = profile;
        lodMergeRuns = mergeRuns;
        lodDistance = distance;
        lodCutoff = cutoff;
    }

    public void blockEntities(boolean enabled) {
        blockEntities = enabled;
    }

    public void blackout(boolean enabled, int color, boolean fogPlate) {
        blackout = enabled;
        blackoutColor = color;
        this.fogPlate = fogPlate;
    }

    public void atmosphere(AtmosphereMode mode) {
        atmosphere = mode;
    }

    public void identity(long apertureRevision, long destinationIdentity) {
        this.apertureRevision = apertureRevision;
        this.destinationIdentity = destinationIdentity;
    }

    public void projection(boolean hasProjection, boolean invalidated, boolean fullSendPending) {
        this.hasProjection = hasProjection;
        this.invalidated = invalidated;
        this.fullSendPending = fullSendPending;
    }

    public void dissolving(boolean dissolving) {
        this.dissolving = dissolving;
    }

    public void unresolvedOcclusion(boolean unresolved) {
        unresolvedOcclusion = unresolved;
    }

    public void resample(boolean stable, boolean remotePending) {
        stableResample = stable;
        remoteResamplePending = remotePending;
    }

    public void lightingDue(boolean due) {
        lightingDue = due;
    }

    public void localDirty(boolean dirty) {
        localDirty = dirty;
    }

    public void holdsExposed(boolean exposed) {
        holdsExposed = exposed;
    }

    public void sampler(boolean cullingChanged, boolean recursiveSamplesCached) {
        this.cullingChanged = cullingChanged;
        this.recursiveSamplesCached = recursiveSamplesCached;
    }

    public void fitted(boolean coarse, boolean occlusionResumable) {
        this.coarse = coarse;
        this.occlusionResumable = occlusionResumable;
    }

    public void samples(boolean destinationDirty, boolean destinationOverBudget, boolean localStale) {
        this.destinationDirty = destinationDirty;
        this.destinationOverBudget = destinationOverBudget;
        this.localStale = localStale;
    }
}
