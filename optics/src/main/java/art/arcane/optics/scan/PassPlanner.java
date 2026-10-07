package art.arcane.optics.scan;

import art.arcane.optics.frame.Frame;
import art.arcane.optics.volume.GazeScheduler;
import art.arcane.optics.volume.ApertureSlab;

public final class PassPlanner {
    private static final long SEED = 0x6A09E667F3BCC909L;

    private PassPlanner() {
    }

    public static long revision(PassInputs inputs) {
        long hash = frame(SEED, inputs.localFrame);
        hash = frame(hash, inputs.remoteFrame);
        hash = PassRevision.mix(hash, Double.doubleToLongBits(inputs.localX));
        hash = PassRevision.mix(hash, Double.doubleToLongBits(inputs.localY));
        hash = PassRevision.mix(hash, Double.doubleToLongBits(inputs.localZ));
        hash = PassRevision.mix(hash, Double.doubleToLongBits(inputs.remoteX));
        hash = PassRevision.mix(hash, Double.doubleToLongBits(inputs.remoteY));
        hash = PassRevision.mix(hash, Double.doubleToLongBits(inputs.remoteZ));
        hash = PassRevision.mix(hash, inputs.mirror ? 1L : 0L);
        hash = PassRevision.mix(hash, inputs.quarterTurns);
        hash = PassRevision.mix(hash, inputs.depth);
        hash = PassRevision.mix(hash, inputs.lateral);
        hash = PassRevision.mix(hash, Double.doubleToLongBits(inputs.projectionDistance));
        hash = PassRevision.mix(hash, Double.doubleToLongBits(inputs.aperturePadding));
        hash = PassRevision.mix(hash, Double.doubleToLongBits(inputs.nearPlanePadding));
        hash = PassRevision.mix(hash, Double.doubleToLongBits(inputs.cullingRatio));
        hash = PassRevision.mix(hash, Double.doubleToLongBits(inputs.revealMargin));
        hash = PassRevision.mix(hash, inputs.maxCells);
        hash = PassRevision.mix(hash, inputs.recursionDepth);
        hash = PassRevision.mix(hash, inputs.buriedCellCulling ? 1L : 0L);
        hash = PassRevision.mix(hash, inputs.observerOcclusion ? 1L : 0L);
        hash = PassRevision.mix(hash, inputs.lodProfile == null ? -1L : inputs.lodProfile.ordinal());
        hash = PassRevision.mix(hash, inputs.lodMergeRuns ? 1L : 0L);
        hash = PassRevision.mix(hash, inputs.lodDistance);
        hash = PassRevision.mix(hash, inputs.lodCutoff);
        hash = PassRevision.mix(hash, inputs.blockEntities ? 1L : 0L);
        hash = PassRevision.mix(hash, inputs.blackout ? 1L + ((long) inputs.blackoutColor << 1) + (inputs.fogPlate ? 1L << 40 : 0L) : 0L);
        hash = PassRevision.mix(hash, inputs.atmosphere == null ? -1L : inputs.atmosphere.ordinal());
        hash = PassRevision.mix(hash, inputs.apertureRevision);
        hash = PassRevision.mix(hash, inputs.destinationIdentity);
        return PassRevision.mix(hash, side(inputs, inputs.eyeX, inputs.eyeY, inputs.eyeZ) ? 1L : 0L);
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
            reasons |= ResampleReasons.PRESENTATION;
        }
        if (destinationContentStale && inputs.recursiveSamplesCached) {
            reasons |= ResampleReasons.DEST_STALE_RECURSIVE;
        }
        if (destinationDirty) {
            reasons |= ResampleReasons.DEST_STALE_DIRTY;
        }
        if (destinationOverBudget) {
            reasons |= ResampleReasons.DEST_OVER_BUDGET;
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
            reasons |= ResampleReasons.INVALIDATED;
        }
        if (inputs.unresolvedOcclusion) {
            reasons |= ResampleReasons.UNRESOLVED_OCCLUSION;
        }
        if (inputs.stableResample) {
            reasons |= ResampleReasons.STABLE_CADENCE;
        }
        if (inputs.localDirty) {
            reasons |= ResampleReasons.LOCAL_DIRTY;
        }
        if (inputs.remoteResamplePending) {
            reasons |= ResampleReasons.REMOTE_PENDING;
        }
        if (inputs.lightingDue) {
            reasons |= ResampleReasons.LIGHTING;
        }
        if (!inputs.committed || !inputs.hasProjection || inputs.fullSendPending) {
            reasons |= ResampleReasons.FULL_SEND;
        }
        if (!inputs.camera || cameraMoved(inputs) || sideChanged(inputs)) {
            reasons |= ResampleReasons.CAMERA;
        }
        if (inputs.holdsExposed) {
            reasons |= ResampleReasons.HOLDS_EXPOSED;
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
        return ApertureSlab.side(inputs.localFrame, inputs.localX, inputs.localY, inputs.localZ, x, y, z);
    }

    private static long frame(long hash, Frame frame) {
        long mixed = PassRevision.mix(hash, frame.getNormal().ordinal());
        mixed = PassRevision.mix(mixed, frame.getRight().ordinal());
        return PassRevision.mix(mixed, frame.getUp().ordinal());
    }
}
