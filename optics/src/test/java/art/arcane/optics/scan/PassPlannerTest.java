package art.arcane.optics.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.optics.fidelity.AtmosphereMode;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.math.Face;
import art.arcane.optics.volume.LodProfile;
import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import org.junit.jupiter.api.Test;

public final class PassPlannerTest {
    private static final int COMMITTED = 0;
    private static final int HAS_PROJECTION = 1;
    private static final int CAMERA = 2;
    private static final int INVALIDATED = 3;
    private static final int FULL_SEND = 4;
    private static final int DISSOLVING = 5;
    private static final int UNRESOLVED = 6;
    private static final int STABLE = 7;
    private static final int REMOTE_PENDING = 8;
    private static final int LIGHTING = 9;
    private static final int LOCAL_DIRTY = 10;
    private static final int HOLDS_EXPOSED = 11;
    private static final int CULLING_CHANGED = 12;
    private static final int RECURSIVE_CACHED = 13;
    private static final int DESTINATION_DIRTY = 14;
    private static final int OVER_BUDGET = 15;
    private static final int LOCAL_STALE = 16;
    private static final int RESUMABLE = 17;
    private static final int COARSE_CHANGED = 18;
    private static final int OBSERVER_OCCLUSION = 19;
    private static final int MOVED = 20;
    private static final int FLIPPED = 21;
    private static final int REVISION_CHANGED = 22;
    private static final int DIMENSIONS = 23;
    private static final double PLANE_Z = 10.0D;

    @Test
    public void everyBooleanCombinationMatchesTheDerivationItReplaces() {
        PassInputs inputs = scene(false);
        boolean[] bits = new boolean[DIMENSIONS];
        int combinations = 1 << DIMENSIONS;
        for (int combination = 0; combination < combinations; combination++) {
            for (int bit = 0; bit < DIMENSIONS; bit++) {
                bits[bit] = (combination & (1 << bit)) != 0;
            }
            apply(inputs, bits);
            PassPlan plan = PassPlanner.plan(inputs);
            Expected expected = expected(bits);
            if (expected.kind() != plan.kind() || expected.flags() != plan.flags() || expected.reasons() != plan.reasons()) {
                throw new AssertionError("combination " + Integer.toBinaryString(combination) + " expected " + expected
                    + " but planned " + plan);
            }
            if (PassPlanner.reusable(inputs) != (expected.kind() == PassPlan.Kind.REUSE)) {
                throw new AssertionError("reusable disagrees with plan for combination " + Integer.toBinaryString(combination));
            }
        }
    }

    @Test
    public void reuseAllowsSmallEyeDriftForAnyNumberOfPasses() {
        PassInputs inputs = settled(false);
        assertSame(PassPlan.REUSE, PassPlanner.plan(inputs));

        inputs.eye(1.7D, 65.0D, PLANE_Z + 4.0D);
        assertSame(PassPlan.REUSE, PassPlanner.plan(inputs));

        inputs.eye(1.5D + 0.3D, 65.0D, PLANE_Z + 4.0D);
        assertEquals(PassPlan.Kind.RESCAN, PassPlanner.plan(inputs).kind());
    }

    @Test
    public void reuseDefeatedBySideFlip() {
        PassInputs inputs = settled(false);
        inputs.eye(1.5D, 65.0D, PLANE_Z + 0.1D);
        inputs.camera(true, 1.5D, 65.0D, PLANE_Z + 0.1D);
        inputs.committed(true, PassPlanner.revision(inputs), false);
        assertSame(PassPlan.REUSE, PassPlanner.plan(inputs));

        inputs.eye(1.5D, 65.0D, PLANE_Z - 0.1D);
        PassPlan plan = PassPlanner.plan(inputs);

        assertEquals(PassPlan.Kind.RESCAN, plan.kind());
        assertTrue(plan.has(PassPlan.CONTENT_INVALIDATED), "the projection side is part of the presentation");
        assertTrue((plan.reasons() & ProjectorResampleReasons.CAMERA) != 0);
    }

    @Test
    public void reuseDefeatedByLocalWorldChange() {
        PassInputs inputs = settled(false);
        inputs.localDirty(true);
        assertFalse(PassPlanner.reusable(inputs));

        inputs.eye(1.7D, 65.0D, PLANE_Z + 4.0D);
        assertFalse(PassPlanner.reusable(inputs));
    }

    @Test
    public void reuseDefeatedByResampleTriggers() {
        PassInputs inputs = settled(false);
        inputs.resample(true, false);
        assertFalse(PassPlanner.reusable(inputs));
        inputs.resample(false, true);
        assertFalse(PassPlanner.reusable(inputs));
        inputs.resample(false, false);
        inputs.projection(true, false, true);
        assertFalse(PassPlanner.reusable(inputs));
        inputs.projection(true, false, false);
        inputs.lightingDue(true);
        assertFalse(PassPlanner.reusable(inputs));
        inputs.lightingDue(false);
        inputs.committed(false, PassPlanner.revision(inputs), false);
        assertFalse(PassPlanner.reusable(inputs));
        inputs.committed(true, PassPlanner.revision(inputs), false);
        inputs.projection(false, false, false);
        assertFalse(PassPlanner.reusable(inputs));
        inputs.projection(true, false, false);
        inputs.camera(false, 0.0D, 0.0D, 0.0D);
        assertFalse(PassPlanner.reusable(inputs));
    }

    @Test
    public void viewOcclusionRefreshesVisibilityAfterMeaningfulEyeMovement() {
        assertFalse(refreshesVisibility(false, true, 0.25D));
        assertFalse(refreshesVisibility(true, true, 0.249D));
        assertTrue(refreshesVisibility(true, true, 0.25D));
        assertFalse(refreshesVisibility(true, false, 4.0D));
    }

    @Test
    public void cameraOnlyVenticularMovementRetainsDestinationContent() {
        PassInputs inputs = settled(true);
        inputs.eye(1.5D + 1.0D, 65.0D, PLANE_Z + 4.0D);
        inputs.fitted(false, false);

        PassPlan plan = PassPlanner.plan(inputs);

        assertEquals(PassPlan.Kind.RESCAN, plan.kind());
        assertTrue(plan.has(PassPlan.REFRESH_VISIBILITY), "camera movement must still rebuild Venticular view cells");
        assertFalse(plan.has(PassPlan.DESTINATION_CONTENT_STALE), "camera movement alone does not change destination content");
        assertFalse(plan.has(PassPlan.DESTINATION_SAMPLES_STALE));
        assertFalse(plan.has(PassPlan.CONTENT_INVALIDATED));
    }

    @Test
    public void scheduledContentResamplingDropsDestinationContent() {
        PassInputs inputs = settled(false);
        inputs.resample(true, false);

        PassPlan plan = PassPlanner.plan(inputs);

        assertTrue(plan.has(PassPlan.DESTINATION_CONTENT_STALE));
        assertTrue(plan.has(PassPlan.DESTINATION_SAMPLES_STALE));
        assertTrue(plan.has(PassPlan.LOCAL_CONTENT_RESAMPLE));
        assertTrue(plan.has(PassPlan.CONTENT_INVALIDATED));
        assertTrue((plan.reasons() & ProjectorResampleReasons.STABLE_CADENCE) != 0);
    }

    @Test
    public void cullingAndRecursiveChangesInvalidateDestinationContent() {
        PassInputs inputs = settled(false);
        inputs.sampler(true, false);
        assertTrue(PassPlanner.plan(inputs).has(PassPlan.DESTINATION_CONTENT_STALE));
        inputs.sampler(false, true);
        assertSame(PassPlan.REUSE, PassPlanner.plan(inputs), "cached recursive samples refresh on the next scanned pass");
        inputs.unresolvedOcclusion(true);
        PassPlan recursive = PassPlanner.plan(inputs);
        assertTrue(recursive.has(PassPlan.DESTINATION_CONTENT_STALE));
        assertTrue((recursive.reasons() & ProjectorResampleReasons.DEST_STALE_RECURSIVE) != 0);
        inputs.sampler(false, false);
        inputs.unresolvedOcclusion(false);
        inputs.scanMode(new ScanMode(true, true));
        PassPlan modeChange = PassPlanner.plan(inputs);
        assertTrue(modeChange.has(PassPlan.CONTENT_INVALIDATED));
        assertTrue((modeChange.reasons() & ProjectorResampleReasons.PRESENTATION) != 0);
    }

    @Test
    public void anUnchangedCommittedScanWithUnresolvedOcclusionResumes() {
        PassInputs inputs = settled(true);
        inputs.unresolvedOcclusion(true);
        inputs.fitted(false, true);

        PassPlan plan = PassPlanner.plan(inputs);

        assertEquals(PassPlan.Kind.RESUME_OCCLUSION, plan.kind());
        assertEquals(0, plan.flags());
        assertEquals(ProjectorResampleReasons.UNRESOLVED_OCCLUSION, plan.reasons());
        assertEquals(PassPlanner.revision(inputs), plan.revision());
    }

    @Test
    public void aCoarsenedFitIsAPresentationChange() {
        PassInputs inputs = settled(true);
        inputs.unresolvedOcclusion(true);
        inputs.fitted(true, true);

        PassPlan plan = PassPlanner.plan(inputs);

        assertEquals(PassPlan.Kind.RESCAN, plan.kind());
        assertTrue(plan.has(PassPlan.CONTENT_INVALIDATED));
    }

    @Test
    public void blackoutColourIsIgnoredWhileBlackoutIsOff() {
        PassInputs inputs = settled(false);
        long revision = PassPlanner.revision(inputs);

        inputs.blackout(false, 7, true);
        assertEquals(revision, PassPlanner.revision(inputs));

        inputs.blackout(true, 7, true);
        long enabled = PassPlanner.revision(inputs);
        assertTrue(enabled != revision);
        inputs.blackout(true, 8, true);
        assertTrue(PassPlanner.revision(inputs) != enabled);
        inputs.blackout(true, 7, false);
        assertTrue(PassPlanner.revision(inputs) != enabled);
    }

    @Test
    public void reusePathAllocatesNothing() {
        PassInputs inputs = settled(true);
        ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long sink = 0L;
        for (int warmup = 0; warmup < 200_000; warmup++) {
            sink += PassPlanner.reusable(inputs) ? 1L : 0L;
            sink += PassPlanner.plan(inputs).flags();
        }
        long allocated = Long.MAX_VALUE;
        for (int round = 0; round < 5; round++) {
            long before = threads.getCurrentThreadAllocatedBytes();
            for (int pass = 0; pass < 1_000_000; pass++) {
                sink += PassPlanner.reusable(inputs) ? 1L : 0L;
                sink += PassPlanner.plan(inputs).flags();
                sink += PassPlanner.revision(inputs);
            }
            allocated = Math.min(allocated, threads.getCurrentThreadAllocatedBytes() - before);
        }
        long calibrationStart = threads.getCurrentThreadAllocatedBytes();
        long[] calibration = new long[4_096];
        long calibrated = threads.getCurrentThreadAllocatedBytes() - calibrationStart;

        assertTrue(threads.isThreadAllocatedMemorySupported() && threads.isThreadAllocatedMemoryEnabled());
        assertTrue(calibrated >= calibration.length * 8L, "the allocation counter must observe a known allocation");
        assertTrue(sink != 0L);
        assertEquals(0L, allocated, "planning a reused pass must not allocate");
    }

    private static boolean refreshesVisibility(boolean observerOcclusion, boolean camera, double moved) {
        PassInputs inputs = settled(observerOcclusion);
        inputs.camera(camera, 1.5D, 65.0D, PLANE_Z + 4.0D);
        inputs.eye(1.5D + moved, 65.0D, PLANE_Z + 4.0D);
        inputs.committed(true, PassPlanner.revision(inputs), false);
        inputs.resample(true, false);
        return PassPlanner.plan(inputs).has(PassPlan.REFRESH_VISIBILITY);
    }

    static PassInputs scene(boolean observerOcclusion) {
        PassInputs inputs = new PassInputs();
        inputs.local(Frame.canonical(Face.S), 1.5D, 65.0D, PLANE_Z);
        inputs.remote(Frame.canonical(Face.N), 101.5D, 70.0D, -40.0D);
        inputs.mirror(false, 0);
        inputs.extent(48, 24, 48.0D);
        inputs.padding(0.75D, 0.2D);
        inputs.frustum(0.85D, 3.0D);
        inputs.limits(20_000, 2);
        inputs.scanMode(new ScanMode(observerOcclusion, observerOcclusion));
        inputs.lod(LodProfile.BALANCED, true, 32, 48);
        inputs.blockEntities(true);
        inputs.blackout(false, 15, false);
        inputs.atmosphere(AtmosphereMode.TINT);
        inputs.identity(9L, 0x5EED5EEDL);
        inputs.eye(1.5D, 65.0D, PLANE_Z + 4.0D);
        return inputs;
    }

    static PassInputs settled(boolean observerOcclusion) {
        PassInputs inputs = scene(observerOcclusion);
        inputs.camera(true, 1.5D, 65.0D, PLANE_Z + 4.0D);
        inputs.projection(true, false, false);
        inputs.dissolving(false);
        inputs.unresolvedOcclusion(false);
        inputs.resample(false, false);
        inputs.lightingDue(false);
        inputs.localDirty(false);
        inputs.holdsExposed(false);
        inputs.sampler(false, false);
        inputs.fitted(false, false);
        inputs.samples(false, false, false);
        inputs.committed(true, PassPlanner.revision(inputs), false);
        return inputs;
    }

    private static void apply(PassInputs inputs, boolean[] bits) {
        inputs.scanMode(new ScanMode(bits[OBSERVER_OCCLUSION], bits[OBSERVER_OCCLUSION]));
        double eyeX = 1.5D;
        double eyeZ = PLANE_Z + 0.1D;
        inputs.eye(eyeX, 65.0D, eyeZ);
        double cameraX = bits[MOVED] ? eyeX + 0.3D : eyeX;
        double cameraZ = bits[FLIPPED] ? PLANE_Z - 0.1D : eyeZ;
        inputs.camera(bits[CAMERA], cameraX, 65.0D, cameraZ);
        inputs.projection(bits[HAS_PROJECTION], bits[INVALIDATED], bits[FULL_SEND]);
        inputs.dissolving(bits[DISSOLVING]);
        inputs.unresolvedOcclusion(bits[UNRESOLVED]);
        inputs.resample(bits[STABLE], bits[REMOTE_PENDING]);
        inputs.lightingDue(bits[LIGHTING]);
        inputs.localDirty(bits[LOCAL_DIRTY]);
        inputs.holdsExposed(bits[HOLDS_EXPOSED]);
        inputs.sampler(bits[CULLING_CHANGED], bits[RECURSIVE_CACHED]);
        inputs.samples(bits[DESTINATION_DIRTY], bits[OVER_BUDGET], bits[LOCAL_STALE]);
        inputs.fitted(bits[COARSE_CHANGED], bits[RESUMABLE]);
        long revision = PassPlanner.revision(inputs);
        inputs.committed(bits[COMMITTED], bits[REVISION_CHANGED] ? revision + 1L : revision, false);
    }

    private static Expected expected(boolean[] bits) {
        boolean committed = bits[COMMITTED];
        boolean hasProjection = bits[HAS_PROJECTION];
        boolean camera = bits[CAMERA];
        boolean invalidated = bits[INVALIDATED];
        boolean fullSend = bits[FULL_SEND];
        boolean dissolving = bits[DISSOLVING];
        boolean unresolved = bits[UNRESOLVED];
        boolean stable = bits[STABLE];
        boolean remotePending = bits[REMOTE_PENDING];
        boolean lighting = bits[LIGHTING];
        boolean localDirty = bits[LOCAL_DIRTY];
        boolean holdsExposed = bits[HOLDS_EXPOSED];
        boolean cullingChanged = bits[CULLING_CHANGED];
        boolean recursiveCached = bits[RECURSIVE_CACHED];
        boolean moved = bits[MOVED];
        boolean flipped = bits[FLIPPED];
        boolean revisionChanged = bits[REVISION_CHANGED];

        boolean sideFlipped = camera && flipped;
        boolean canReuse = !invalidated && !unresolved && committed && hasProjection && camera
            && !fullSend && !stable && !remotePending && !lighting && !sideFlipped && !(localDirty || holdsExposed) && !moved;
        boolean reuse = !dissolving && canReuse && !revisionChanged && !cullingChanged;
        if (reuse) {
            return new Expected(PassPlan.Kind.REUSE, 0, 0);
        }
        int reasons = 0;
        if (invalidated) {
            reasons |= ProjectorResampleReasons.INVALIDATED;
        }
        if (unresolved) {
            reasons |= ProjectorResampleReasons.UNRESOLVED_OCCLUSION;
        }
        if (stable) {
            reasons |= ProjectorResampleReasons.STABLE_CADENCE;
        }
        if (localDirty) {
            reasons |= ProjectorResampleReasons.LOCAL_DIRTY;
        }
        if (remotePending) {
            reasons |= ProjectorResampleReasons.REMOTE_PENDING;
        }
        if (lighting) {
            reasons |= ProjectorResampleReasons.LIGHTING;
        }
        if (!committed || !hasProjection || fullSend) {
            reasons |= ProjectorResampleReasons.FULL_SEND;
        }
        if (dissolving) {
            reasons |= ProjectorResampleReasons.PRESENTATION;
        }
        if (!camera || moved || flipped) {
            reasons |= ProjectorResampleReasons.CAMERA;
        }
        if (holdsExposed) {
            reasons |= ProjectorResampleReasons.HOLDS_EXPOSED;
        }
        boolean scheduled = stable || remotePending;
        boolean destinationContentStale = scheduled || cullingChanged || recursiveCached;
        boolean destinationDirty = !destinationContentStale && bits[DESTINATION_DIRTY];
        boolean destinationOverBudget = !destinationContentStale && !destinationDirty && bits[OVER_BUDGET];
        boolean destinationSamplesStale = destinationContentStale || destinationDirty || destinationOverBudget;
        if (destinationContentStale && recursiveCached) {
            reasons |= ProjectorResampleReasons.DEST_STALE_RECURSIVE;
        }
        if (destinationDirty) {
            reasons |= ProjectorResampleReasons.DEST_STALE_DIRTY;
        }
        if (destinationOverBudget) {
            reasons |= ProjectorResampleReasons.DEST_OVER_BUDGET;
        }
        boolean localContentResample = scheduled || cullingChanged;
        boolean localSamplesStale = localContentResample || bits[LOCAL_STALE];
        boolean presentationChanged = !committed || revisionChanged || bits[COARSE_CHANGED];
        boolean contentInvalidated = invalidated || destinationSamplesStale || localSamplesStale || presentationChanged;
        boolean viewCameraMoved = bits[OBSERVER_OCCLUSION] && camera && moved;
        boolean reuseStableContent = viewCameraMoved && !contentInvalidated && !scheduled;
        boolean forceStableCellResample = contentInvalidated || scheduled || (viewCameraMoved && !reuseStableContent);
        if (presentationChanged) {
            reasons |= ProjectorResampleReasons.PRESENTATION;
        }
        boolean resume = committed && !invalidated && !dissolving && !forceStableCellResample && !fullSend
            && !destinationSamplesStale && !localSamplesStale && !presentationChanged && bits[RESUMABLE];
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
        if (forceStableCellResample) {
            flags |= PassPlan.CONTENT_INVALIDATED;
        }
        if (fullSend) {
            flags |= PassPlan.FULL_SEND;
        }
        if (viewCameraMoved) {
            flags |= PassPlan.REFRESH_VISIBILITY;
        }
        return new Expected(resume ? PassPlan.Kind.RESUME_OCCLUSION : PassPlan.Kind.RESCAN, flags, reasons);
    }

    private record Expected(PassPlan.Kind kind, int flags, int reasons) {
    }
}
