package art.arcane.optics.volume;

import art.arcane.optics.math.Vec3;
import art.arcane.optics.aperture.CellAperture;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;
import art.arcane.optics.frame.ProjectorFrameTransform;

public final class FrustumFit {
    private static final int CELL_BUDGET_SEARCH_ATTEMPTS = 24;

    private Options options;
    private ViewVolume cachedFrustum;
    private CellAperture cachedStructure;
    private long cachedStructureRevision;
    private double cachedEyeX;
    private double cachedEyeY;
    private double cachedEyeZ;
    private double cachedAxial;
    private double cachedLateral;
    private double cachedNearPlanePadding;
    private double cachedCullingRatio;
    private double cachedAperturePadding;
    private ViewVolume cachedFit;
    private CellAperture cachedFitStructure;
    private long cachedFitStructureRevision;
    private double cachedFitEyeX;
    private double cachedFitEyeY;
    private double cachedFitEyeZ;
    private double cachedFitAxial;
    private double cachedFitLateralPad;
    private Face cachedFitNormal;
    private Face cachedFitRight;
    private Face cachedFitUp;
    private int cachedFitBudget;
    private double cachedFitNearPlanePadding;
    private double cachedFitCullingRatio;
    private double cachedFitAperturePadding;
    private double cachedFittedAxial;
    private double cachedFittedLateral;
    private long cachedFittedCandidateWork;
    private long fitRecalculationCount;
    private double fittedAxial;
    private double fittedLateral;
    private long fittedCandidateWork;
    private boolean fittedCoarse;
    private boolean cachedFittedCoarse;
    private LodPolicy lodPolicy = LodPolicy.NONE;
    private LodPolicy cachedFitLod = LodPolicy.NONE;
    private final int[] scratchAxisMin;
    private final int[] scratchAxisMax;
    private final double[] scratchSlabWindowBounds;

    public FrustumFit(Options options) {
        this.options = options;
        this.cachedStructureRevision = Long.MIN_VALUE;
        this.cachedFitStructureRevision = Long.MIN_VALUE;
        this.fitRecalculationCount = 0L;
        this.scratchAxisMin = new int[3];
        this.scratchAxisMax = new int[3];
        this.scratchSlabWindowBounds = new double[4];
    }

    public static double capDistance(double requestedBlocks, int serverChunks, int clientChunks) {
        int client = clientChunks <= 0 ? serverChunks : clientChunks;
        int chunks = Math.max(2, Math.min(serverChunks, client));
        return Math.max(1.0D, Math.min(requestedBlocks, chunks * 16.0D));
    }

    public ViewVolume fit(CellAperture structure,
                  Frame frame,
                  Vec3 eye,
                  double portalDepth,
                  double lateralPadBlocks) {
        double axial = portalDepth;
        int budget = options.cellBudget();
        long structureRevision = structure.getRevision();
        double nearPlanePadding = options.nearPlanePadding();
        double cullingRatio = options.cullingRatio();
        double aperturePadding = options.aperturePadding();
        ViewVolume reusable = cachedFit;
        if (reusable != null
            && cachedFitStructure == structure
            && cachedFitStructureRevision == structureRevision
            && cachedFitEyeX == eye.getX()
            && cachedFitEyeY == eye.getY()
            && cachedFitEyeZ == eye.getZ()
            && cachedFitAxial == axial
            && cachedFitLateralPad == lateralPadBlocks
            && cachedFitNormal == frame.getNormal()
            && cachedFitRight == frame.getRight()
            && cachedFitUp == frame.getUp()
            && cachedFitBudget == budget
            && cachedFitNearPlanePadding == nearPlanePadding
            && cachedFitCullingRatio == cullingRatio
            && cachedFitAperturePadding == aperturePadding
            && cachedFitLod.mergeRuns() == lodPolicy.mergeRuns()
            && cachedFitLod.distanceBlocks() == lodPolicy.distanceBlocks()
            && cachedFitLod.detailCutoffBlocks() == lodPolicy.detailCutoffBlocks()) {
            fittedAxial = cachedFittedAxial;
            fittedLateral = cachedFittedLateral;
            fittedCandidateWork = cachedFittedCandidateWork;
            fittedCoarse = cachedFittedCoarse;
            return reusable;
        }
        fitRecalculationCount++;
        double ceiling = lateralCeiling(axial, lateralPadBlocks);
        FitSolution solution = fitWithinCandidateBudget(structure, frame, eye, axial, ceiling, budget);
        fittedAxial = solution.axial();
        fittedLateral = solution.lateral();
        fittedCandidateWork = solution.candidateWork();
        fittedCoarse = solution.coarse();
        ViewVolume result = solution.frustum();
        cachedFit = result;
        cachedFitStructure = structure;
        cachedFitStructureRevision = structureRevision;
        cachedFitEyeX = eye.getX();
        cachedFitEyeY = eye.getY();
        cachedFitEyeZ = eye.getZ();
        cachedFitAxial = axial;
        cachedFitLateralPad = lateralPadBlocks;
        cachedFitNormal = frame.getNormal();
        cachedFitRight = frame.getRight();
        cachedFitUp = frame.getUp();
        cachedFitBudget = budget;
        cachedFitNearPlanePadding = nearPlanePadding;
        cachedFitCullingRatio = cullingRatio;
        cachedFitAperturePadding = aperturePadding;
        cachedFittedAxial = fittedAxial;
        cachedFittedLateral = fittedLateral;
        cachedFittedCandidateWork = fittedCandidateWork;
        cachedFittedCoarse = fittedCoarse;
        cachedFitLod = lodPolicy;
        return result;
    }

    public void setLodPolicy(LodPolicy policy) {
        lodPolicy = policy == null ? LodPolicy.NONE : policy;
    }

    public LodPolicy lodPolicy() {
        return lodPolicy;
    }

    public double fittedDepth() {
        return fittedAxial;
    }

    /** True when the budget fit kept the depth by run-merging far slabs instead of shedding depth. */
    public boolean fittedCoarse() {
        return fittedCoarse;
    }

    public double fittedLateral() {
        return fittedLateral;
    }

    public long fittedCandidateWork() {
        return fittedCandidateWork;
    }

    public long fitRecalculationCount() {
        return fitRecalculationCount;
    }

    public ViewVolume frustumFor(Vec3 eye, CellAperture structure, double axial, double lateral) {
        long structureRevision = structure.getRevision();
        double nearPlanePadding = options.nearPlanePadding();
        double cullingRatio = options.cullingRatio();
        double aperturePadding = options.aperturePadding();
        ViewVolume cached = cachedFrustum;
        if (cached != null
            && cachedStructure == structure
            && cachedStructureRevision == structureRevision
            && cachedEyeX == eye.getX()
            && cachedEyeY == eye.getY()
            && cachedEyeZ == eye.getZ()
            && cachedAxial == axial
            && cachedLateral == lateral
            && cachedNearPlanePadding == nearPlanePadding
            && cachedCullingRatio == cullingRatio
            && cachedAperturePadding == aperturePadding) {
            return cached;
        }
        ViewVolume built = new ViewVolume(eye, structure,
            new ViewVolume.Options(axial, lateral, nearPlanePadding, cullingRatio, aperturePadding));
        cachedFrustum = built;
        cachedStructure = structure;
        cachedStructureRevision = structureRevision;
        cachedEyeX = eye.getX();
        cachedEyeY = eye.getY();
        cachedEyeZ = eye.getZ();
        cachedAxial = axial;
        cachedLateral = lateral;
        cachedNearPlanePadding = nearPlanePadding;
        cachedCullingRatio = cullingRatio;
        cachedAperturePadding = aperturePadding;
        return built;
    }

    private FitSolution fitWithinCandidateBudget(CellAperture structure,
                                                 Frame frame,
                                                 Vec3 eye,
                                                 double axial,
                                                 double lateralCeiling,
                                                 int budget) {
        long limit = budget <= 0 ? Long.MAX_VALUE : budget;
        ViewVolume full = frustumFor(eye, structure, axial, lateralCeiling);
        long fullWork = estimateCandidateWork(structure, frame, eye, full, axial, limit);
        if (budget <= 0 || fullWork <= budget) {
            return new FitSolution(full, axial, lateralCeiling, fullWork, false);
        }

        ViewVolume narrow = frustumFor(eye, structure, axial, 0.0D);
        long narrowWork = estimateCandidateWork(structure, frame, eye, narrow, axial, budget);
        if (narrowWork <= budget) {
            return fitLateralWithinBudget(structure, frame, eye, axial, lateralCeiling, budget, narrow, narrowWork);
        }
        LodPolicy coarse = lodPolicy.withMergeRuns();
        long exactNarrowWork = estimateCandidateWork(structure, frame, eye, narrow, axial, Long.MAX_VALUE);
        if (coarse.coarsenedWork(exactNarrowWork, axial) <= budget) {
            return new FitSolution(narrow, axial, 0.0D, coarse.coarsenedWork(exactNarrowWork, axial), true);
        }
        return fitAxialWithinBudget(structure, frame, eye, axial, budget);
    }

    private FitSolution fitLateralWithinBudget(CellAperture structure,
                                               Frame frame,
                                               Vec3 eye,
                                               double axial,
                                               double lateralCeiling,
                                               int budget,
                                               ViewVolume initial,
                                               long initialWork) {
        double low = 0.0D;
        double high = lateralCeiling;
        ViewVolume fitted = initial;
        long fittedWork = initialWork;
        for (int attempt = 0; attempt < CELL_BUDGET_SEARCH_ATTEMPTS; attempt++) {
            double candidateLateral = (low + high) * 0.5D;
            ViewVolume candidate = frustumFor(eye, structure, axial, candidateLateral);
            long candidateWork = estimateCandidateWork(structure, frame, eye, candidate, axial, budget);
            if (candidateWork <= budget) {
                low = candidateLateral;
                fitted = candidate;
                fittedWork = candidateWork;
            } else {
                high = candidateLateral;
            }
        }
        return new FitSolution(fitted, axial, low, fittedWork, false);
    }

    private FitSolution fitAxialWithinBudget(CellAperture structure,
                                             Frame frame,
                                             Vec3 eye,
                                             double axialCeiling,
                                             int budget) {
        double low = 0.0D;
        double high = axialCeiling;
        ViewVolume fitted = frustumFor(eye, structure, 0.0D, 0.0D);
        long fittedWork = estimateCandidateWork(structure, frame, eye, fitted, 0.0D, budget);
        if (fittedWork > budget) {
            return new FitSolution(ViewVolume.empty(), 0.0D, 0.0D, 0L, false);
        }
        for (int attempt = 0; attempt < CELL_BUDGET_SEARCH_ATTEMPTS; attempt++) {
            double candidateAxial = (low + high) * 0.5D;
            ViewVolume candidate = frustumFor(eye, structure, candidateAxial, 0.0D);
            long candidateWork = estimateCandidateWork(structure, frame, eye, candidate, candidateAxial, budget);
            if (candidateWork <= budget) {
                low = candidateAxial;
                fitted = candidate;
                fittedWork = candidateWork;
            } else {
                high = candidateAxial;
            }
        }
        if (fittedWork == 0L) {
            return new FitSolution(ViewVolume.empty(), 0.0D, 0.0D, 0L, false);
        }
        return new FitSolution(fitted, low, 0.0D, fittedWork, false);
    }

    public long estimateCandidateWork(CellAperture structure,
                               Frame frame,
                               Vec3 eye,
                               ViewVolume frustum,
                               double depthBlocks,
                               long limit) {
        Box region = frustum.getRegion();
        int[] axisMin = scratchAxisMin;
        axisMin[0] = ProjectorFrameTransform.minBlockForCenter(region.getXa());
        axisMin[1] = ProjectorFrameTransform.minBlockForCenter(region.getYa());
        axisMin[2] = ProjectorFrameTransform.minBlockForCenter(region.getZa());
        int[] axisMax = scratchAxisMax;
        axisMax[0] = ProjectorFrameTransform.maxBlockForCenter(region.getXb());
        axisMax[1] = ProjectorFrameTransform.maxBlockForCenter(region.getYb());
        axisMax[2] = ProjectorFrameTransform.maxBlockForCenter(region.getZb());

        Vec3 center = structure.getArea().center();
        double originX = center.getX();
        double originY = center.getY();
        double originZ = center.getZ();
        Face normal = frame.getNormal();
        double eyeRelX = eye.getX() - originX;
        double eyeRelY = eye.getY() - originY;
        double eyeRelZ = eye.getZ() - originZ;
        boolean eyeFrontSide = dot(eyeRelX, eyeRelY, eyeRelZ, normal) >= 0.0D;
        Frame projectionFrame = frame.view(eyeFrontSide);
        double projectionEyeDot = dot(eyeRelX, eyeRelY, eyeRelZ, projectionFrame.getNormal());
        double portalPlaneClearance = ProjectorFrameTransform.portalPlaneClearance(structure.getArea(), frame);
        double maxProjectionDepth = depthBlocks + portalPlaneClearance;
        double signedMinDistance = eyeFrontSide ? -maxProjectionDepth : portalPlaneClearance;
        double signedMaxDistance = eyeFrontSide ? -portalPlaneClearance : maxProjectionDepth;
        clampNormalBounds(axisMin, axisMax, normal, originX, originY, originZ, signedMinDistance, signedMaxDistance);

        Face projectionNormal = projectionFrame.getNormal();
        Face projectionRight = projectionFrame.getRight();
        Face projectionUp = projectionFrame.getUp();
        int normalAxis = axis(projectionNormal);
        int rightAxis = axis(projectionRight);
        int upAxis = axis(projectionUp);
        int rightSign = (int) coordinate(projectionRight, rightAxis);
        int upSign = (int) coordinate(projectionUp, upAxis);
        double rightOrigin = coordinate(rightAxis, originX, originY, originZ);
        double upOrigin = coordinate(upAxis, originX, originY, originZ);
        if (axisMin[normalAxis] > axisMax[normalAxis]
            || axisMin[rightAxis] > axisMax[rightAxis]
            || axisMin[upAxis] > axisMax[upAxis]) {
            return 0L;
        }

        PlaneWindow planeWindow = PlaneWindow.create(structure, structure.getArea(), projectionFrame,
            originX, originY, originZ, options.aperturePadding(), projectionEyeDot);
        double normalOrigin = coordinate(normalAxis, originX, originY, originZ);
        double projectionFacingNormal = coordinate(projectionNormal, normalAxis);
        long work = 0L;
        for (int n = axisMin[normalAxis]; n <= axisMax[normalAxis]; n++) {
            double slabSignedDistance = projectionFacingNormal * ((n + 0.5D) - normalOrigin);
            if (!planeWindow.slabWindow(eye.getX(), eye.getY(), eye.getZ(), slabSignedDistance, scratchSlabWindowBounds)) {
                continue;
            }
            int rightMin = PlaneWindow.slabBlockMin(scratchSlabWindowBounds[0], scratchSlabWindowBounds[1],
                rightSign, rightOrigin, axisMin[rightAxis]);
            int rightMax = PlaneWindow.slabBlockMax(scratchSlabWindowBounds[0], scratchSlabWindowBounds[1],
                rightSign, rightOrigin, axisMax[rightAxis]);
            int upMin = PlaneWindow.slabBlockMin(scratchSlabWindowBounds[2], scratchSlabWindowBounds[3],
                upSign, upOrigin, axisMin[upAxis]);
            int upMax = PlaneWindow.slabBlockMax(scratchSlabWindowBounds[2], scratchSlabWindowBounds[3],
                upSign, upOrigin, axisMax[upAxis]);
            long rightWork = axisCandidateWork(rightMin, rightMax);
            long upWork = axisCandidateWork(upMin, upMax);
            long slabWork = rightWork * upWork;
            if (slabWork > limit - work) {
                return limit == Long.MAX_VALUE ? Long.MAX_VALUE : limit + 1L;
            }
            work += slabWork;
        }
        return work;
    }

    private static long axisCandidateWork(int minimum, int maximum) {
        return maximum < minimum ? 0L : (long) maximum - minimum + 1L;
    }

    private static void clampNormalBounds(int[] axisMin,
                                          int[] axisMax,
                                          Face normal,
                                          double originX,
                                          double originY,
                                          double originZ,
                                          double signedMinDistance,
                                          double signedMaxDistance) {
        int normalAxis = axis(normal);
        double normalComponent = coordinate(normal, normalAxis);
        double normalOrigin = coordinate(normalAxis, originX, originY, originZ);
        double centerA = normalOrigin + (signedMinDistance / normalComponent);
        double centerB = normalOrigin + (signedMaxDistance / normalComponent);
        axisMin[normalAxis] = Math.max(axisMin[normalAxis], ProjectorFrameTransform.minBlockForCenter(Math.min(centerA, centerB)));
        axisMax[normalAxis] = Math.min(axisMax[normalAxis], ProjectorFrameTransform.maxBlockForCenter(Math.max(centerA, centerB)));
    }

    private static double lateralCeiling(double axial, double lateralPadBlocks) {
        return Math.min(Math.max(lateralPadBlocks, 0.0D), axial);
    }

    private static double dot(double x, double y, double z, Face direction) {
        return (x * direction.x()) + (y * direction.y()) + (z * direction.z());
    }

    private static int axis(Face direction) {
        return direction.x() != 0 ? 0 : direction.y() != 0 ? 1 : 2;
    }

    private static double coordinate(Face direction, int axis) {
        return axis == 0 ? direction.x() : axis == 1 ? direction.y() : direction.z();
    }

    private static double coordinate(int axis, double x, double y, double z) {
        return axis == 0 ? x : axis == 1 ? y : z;
    }

    public void setOptions(Options options) {
        this.options = options;
    }

    public record Options(int cellBudget, double nearPlanePadding, double cullingRatio, double aperturePadding) {
    }

    private record FitSolution(ViewVolume frustum, double axial, double lateral, long candidateWork, boolean coarse) {
    }
}
