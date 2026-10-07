package art.arcane.optics.scan;

import art.arcane.optics.frame.Frame;
import art.arcane.optics.volume.GazeScheduler;
import art.arcane.optics.volume.ProjectionVolume;

public final class PassPlanner {
    private static final long SEED = 0x6A09E667F3BCC909L;

    private PassPlanner() {
    }

    public static long revision(PassInputs inputs) {
        long hash = frame(SEED, inputs.localFrame);
        hash = frame(hash, inputs.remoteFrame);
        hash = ProjectorPassRevision.mix(hash, Double.doubleToLongBits(inputs.localX));
        hash = ProjectorPassRevision.mix(hash, Double.doubleToLongBits(inputs.localY));
        hash = ProjectorPassRevision.mix(hash, Double.doubleToLongBits(inputs.localZ));
        hash = ProjectorPassRevision.mix(hash, Double.doubleToLongBits(inputs.remoteX));
        hash = ProjectorPassRevision.mix(hash, Double.doubleToLongBits(inputs.remoteY));
        hash = ProjectorPassRevision.mix(hash, Double.doubleToLongBits(inputs.remoteZ));
        hash = ProjectorPassRevision.mix(hash, inputs.mirror ? 1L : 0L);
        hash = ProjectorPassRevision.mix(hash, inputs.quarterTurns);
        hash = ProjectorPassRevision.mix(hash, inputs.depth);
        hash = ProjectorPassRevision.mix(hash, inputs.lateral);
        hash = ProjectorPassRevision.mix(hash, Double.doubleToLongBits(inputs.projectionDistance));
        hash = ProjectorPassRevision.mix(hash, Double.doubleToLongBits(inputs.aperturePadding));
        hash = ProjectorPassRevision.mix(hash, Double.doubleToLongBits(inputs.nearPlanePadding));
        hash = ProjectorPassRevision.mix(hash, Double.doubleToLongBits(inputs.cullingRatio));
        hash = ProjectorPassRevision.mix(hash, Double.doubleToLongBits(inputs.revealMargin));
        hash = ProjectorPassRevision.mix(hash, inputs.maxCells);
        hash = ProjectorPassRevision.mix(hash, inputs.recursionDepth);
        hash = ProjectorPassRevision.mix(hash, inputs.buriedCellCulling ? 1L : 0L);
        hash = ProjectorPassRevision.mix(hash, inputs.observerOcclusion ? 1L : 0L);
        hash = ProjectorPassRevision.mix(hash, inputs.lodProfile == null ? -1L : inputs.lodProfile.ordinal());
        hash = ProjectorPassRevision.mix(hash, inputs.lodMergeRuns ? 1L : 0L);
        hash = ProjectorPassRevision.mix(hash, inputs.lodDistance);
        hash = ProjectorPassRevision.mix(hash, inputs.lodCutoff);
        hash = ProjectorPassRevision.mix(hash, inputs.blockEntities ? 1L : 0L);
        hash = ProjectorPassRevision.mix(hash, inputs.blackout ? 1L + ((long) inputs.blackoutColor << 1) + (inputs.fogPlate ? 1L << 40 : 0L) : 0L);
        hash = ProjectorPassRevision.mix(hash, inputs.atmosphere == null ? -1L : inputs.atmosphere.ordinal());
        hash = ProjectorPassRevision.mix(hash, inputs.apertureRevision);
        hash = ProjectorPassRevision.mix(hash, inputs.destinationIdentity);
        return ProjectorPassRevision.mix(hash, side(inputs, inputs.eyeX, inputs.eyeY, inputs.eyeZ) ? 1L : 0L);
    }

    public static boolean reusable(PassInputs inputs) {
        return reusableFacts(inputs) && revision(inputs) == inputs.committedRevision;
    }

    public static PassPlan plan(PassInputs inputs) {
        long revision = revision(inputs);
        if (reusableFacts(inputs) && revision == inputs.committedRevision) {
            return PassPlan.REUSE;
        }
        boolean scheduled = inputs.stableResample || inputs.remoteResamplePending;
        boolean destinationContentStale = scheduled || inputs.cullingChanged || inputs.recursiveSamplesCached;
        boolean destinationDirty = !destinationContentStale && inputs.destinationDirty;
        boolean destinationOverBudget = !destinationContentStale && !destinationDirty && inputs.destinationOverBudget;
        boolean destinationSamplesStale = destinationContentStale || destinationDirty || destinationOverBudget;
        boolean localContentResample = scheduled || inputs.cullingChanged;
        boolean localSamplesStale = localContentResample || inputs.localStale;
        boolean presentationChanged = !inputs.committed || revision != inputs.committedRevision
            || inputs.coarse != inputs.committedCoarse;
        boolean contentInvalidated = inputs.invalidated || destinationSamplesStale || localSamplesStale || presentationChanged;
        boolean refreshVisibility = inputs.observerOcclusion && inputs.camera && cameraMoved(inputs);
        int flags = 0;
        if (destinationContentStale) {
            flags |= PassPlan.DESTINATION_CONTENT_STALE;
        }
        if (destinationSamplesStale) {
            flags |= PassPlan.DESTINATION_SAMPLES_STALE;
        }
        if (localContentResample) {
            flags |= PassPlan.LOCAL_CONTENT_RESAMPLE;
        }
        if (contentInvalidated) {
            flags |= PassPlan.CONTENT_INVALIDATED;
        }
        if (inputs.fullSendPending) {
            flags |= PassPlan.FULL_SEND;
        }
        if (refreshVisibility) {
            flags |= PassPlan.REFRESH_VISIBILITY;
        }
        int reasons = factReasons(inputs);
        if (inputs.dissolving || presentationChanged) {
            reasons |= ProjectorResampleReasons.PRESENTATION;
        }
        if (destinationContentStale && inputs.recursiveSamplesCached) {
            reasons |= ProjectorResampleReasons.DEST_STALE_RECURSIVE;
        }
        if (destinationDirty) {
            reasons |= ProjectorResampleReasons.DEST_STALE_DIRTY;
        }
        if (destinationOverBudget) {
            reasons |= ProjectorResampleReasons.DEST_OVER_BUDGET;
        }
        boolean resume = inputs.committed && !inputs.dissolving && !contentInvalidated && !inputs.fullSendPending
            && inputs.occlusionResumable;
        return new PassPlan(resume ? PassPlan.Kind.RESUME_OCCLUSION : PassPlan.Kind.RESCAN, flags, reasons, revision);
    }

    private static boolean reusableFacts(PassInputs inputs) {
        return inputs.committed && inputs.hasProjection && inputs.camera && !inputs.invalidated && !inputs.fullSendPending
            && !inputs.dissolving && !inputs.unresolvedOcclusion && !inputs.stableResample && !inputs.remoteResamplePending
            && !inputs.lightingDue && !inputs.localDirty && !inputs.holdsExposed && !inputs.cullingChanged
            && !cameraMoved(inputs) && !sideChanged(inputs);
    }

    private static int factReasons(PassInputs inputs) {
        int reasons = 0;
        if (inputs.invalidated) {
            reasons |= ProjectorResampleReasons.INVALIDATED;
        }
        if (inputs.unresolvedOcclusion) {
            reasons |= ProjectorResampleReasons.UNRESOLVED_OCCLUSION;
        }
        if (inputs.stableResample) {
            reasons |= ProjectorResampleReasons.STABLE_CADENCE;
        }
        if (inputs.localDirty) {
            reasons |= ProjectorResampleReasons.LOCAL_DIRTY;
        }
        if (inputs.remoteResamplePending) {
            reasons |= ProjectorResampleReasons.REMOTE_PENDING;
        }
        if (inputs.lightingDue) {
            reasons |= ProjectorResampleReasons.LIGHTING;
        }
        if (!inputs.committed || !inputs.hasProjection || inputs.fullSendPending) {
            reasons |= ProjectorResampleReasons.FULL_SEND;
        }
        if (!inputs.camera || cameraMoved(inputs) || sideChanged(inputs)) {
            reasons |= ProjectorResampleReasons.CAMERA;
        }
        if (inputs.holdsExposed) {
            reasons |= ProjectorResampleReasons.HOLDS_EXPOSED;
        }
        return reasons;
    }

    private static boolean cameraMoved(PassInputs inputs) {
        double dx = inputs.eyeX - inputs.cameraX;
        double dy = inputs.eyeY - inputs.cameraY;
        double dz = inputs.eyeZ - inputs.cameraZ;
        return (dx * dx) + (dy * dy) + (dz * dz) >= GazeScheduler.REUSE_EYE_EPSILON_SQUARED;
    }

    private static boolean sideChanged(PassInputs inputs) {
        return side(inputs, inputs.eyeX, inputs.eyeY, inputs.eyeZ) != side(inputs, inputs.cameraX, inputs.cameraY, inputs.cameraZ);
    }

    private static boolean side(PassInputs inputs, double x, double y, double z) {
        return ProjectionVolume.side(inputs.localFrame, inputs.localX, inputs.localY, inputs.localZ, x, y, z);
    }

    private static long frame(long hash, Frame frame) {
        long mixed = ProjectorPassRevision.mix(hash, frame.getNormal().ordinal());
        mixed = ProjectorPassRevision.mix(mixed, frame.getRight().ordinal());
        return ProjectorPassRevision.mix(mixed, frame.getUp().ordinal());
    }
}
