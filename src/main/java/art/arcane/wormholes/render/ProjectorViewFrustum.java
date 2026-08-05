package art.arcane.wormholes.render;

import java.lang.reflect.Method;
import java.util.logging.Level;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;

final class ProjectorViewFrustum {
    private static final int CELL_BUDGET_SEARCH_ATTEMPTS = 24;

    private static final Method CLIENT_VIEW_DISTANCE_METHOD = resolveClientViewDistanceMethod();

    private final Method clientViewDistanceMethod;
    private boolean clientViewDistanceFailed;
    private Frustum4D cachedFrustum;
    private PortalStructure cachedStructure;
    private long cachedStructureRevision;
    private double cachedEyeX;
    private double cachedEyeY;
    private double cachedEyeZ;
    private double cachedAxial;
    private double cachedLateral;
    private double cachedNearPlanePadding;
    private double cachedCullingRatio;
    private double cachedAperturePadding;
    private Frustum4D cachedFit;
    private PortalStructure cachedFitStructure;
    private long cachedFitStructureRevision;
    private double cachedFitEyeX;
    private double cachedFitEyeY;
    private double cachedFitEyeZ;
    private double cachedFitAxial;
    private double cachedFitLateralPad;
    private Direction cachedFitNormal;
    private Direction cachedFitRight;
    private Direction cachedFitUp;
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
    private final int[] scratchAxisMin;
    private final int[] scratchAxisMax;
    private final double[] scratchSlabWindowBounds;

    ProjectorViewFrustum() {
        this(CLIENT_VIEW_DISTANCE_METHOD);
    }

    ProjectorViewFrustum(Method clientViewDistanceMethod) {
        this.clientViewDistanceMethod = clientViewDistanceMethod;
        this.clientViewDistanceFailed = false;
        this.cachedStructureRevision = Long.MIN_VALUE;
        this.cachedFitStructureRevision = Long.MIN_VALUE;
        this.fitRecalculationCount = 0L;
        this.scratchAxisMin = new int[3];
        this.scratchAxisMax = new int[3];
        this.scratchSlabWindowBounds = new double[4];
    }

    Frustum4D fit(Player observer,
                  PortalStructure structure,
                  PortalFrame frame,
                  Location eye,
                  double portalDepth,
                  double lateralPadBlocks) {
        double axial = capProjectionDistance(observer, portalDepth);
        int budget = Settings.PROJECTION_MAX_PROJECTED_CELLS;
        long structureRevision = structure.getRevision();
        double nearPlanePadding = Settings.NEAR_PLANE_PADDING;
        double cullingRatio = Settings.FRUSTUM_CULLING_RATIO;
        double aperturePadding = Settings.PROJECTION_APERTURE_PADDING_BLOCKS;
        Frustum4D reusable = cachedFit;
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
            && cachedFitAperturePadding == aperturePadding) {
            fittedAxial = cachedFittedAxial;
            fittedLateral = cachedFittedLateral;
            fittedCandidateWork = cachedFittedCandidateWork;
            return reusable;
        }
        fitRecalculationCount++;
        double ceiling = lateralCeiling(axial, lateralPadBlocks);
        FitSolution solution = fitWithinCandidateBudget(structure, frame, eye, axial, ceiling, budget);
        fittedAxial = solution.axial();
        fittedLateral = solution.lateral();
        fittedCandidateWork = solution.candidateWork();
        Frustum4D result = solution.frustum();
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
        return result;
    }

    double fittedDepth() {
        return fittedAxial;
    }

    double fittedLateral() {
        return fittedLateral;
    }

    long fittedCandidateWork() {
        return fittedCandidateWork;
    }

    long fitRecalculationCount() {
        return fitRecalculationCount;
    }

    Frustum4D frustumFor(Location eye, PortalStructure structure, double axial, double lateral) {
        long structureRevision = structure.getRevision();
        double nearPlanePadding = Settings.NEAR_PLANE_PADDING;
        double cullingRatio = Settings.FRUSTUM_CULLING_RATIO;
        double aperturePadding = Settings.PROJECTION_APERTURE_PADDING_BLOCKS;
        Frustum4D cached = cachedFrustum;
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
        Frustum4D built = new Frustum4D(eye, structure, axial, lateral);
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

    private FitSolution fitWithinCandidateBudget(PortalStructure structure,
                                                 PortalFrame frame,
                                                 Location eye,
                                                 double axial,
                                                 double lateralCeiling,
                                                 int budget) {
        long limit = budget <= 0 ? Long.MAX_VALUE : budget;
        Frustum4D full = frustumFor(eye, structure, axial, lateralCeiling);
        long fullWork = estimateCandidateWork(structure, frame, eye, full, axial, limit);
        if (budget <= 0 || fullWork <= budget) {
            return new FitSolution(full, axial, lateralCeiling, fullWork);
        }

        Frustum4D narrow = frustumFor(eye, structure, axial, 0.0D);
        long narrowWork = estimateCandidateWork(structure, frame, eye, narrow, axial, budget);
        if (narrowWork <= budget) {
            return fitLateralWithinBudget(structure, frame, eye, axial, lateralCeiling, budget, narrow, narrowWork);
        }
        return fitAxialWithinBudget(structure, frame, eye, axial, budget);
    }

    private FitSolution fitLateralWithinBudget(PortalStructure structure,
                                               PortalFrame frame,
                                               Location eye,
                                               double axial,
                                               double lateralCeiling,
                                               int budget,
                                               Frustum4D initial,
                                               long initialWork) {
        double low = 0.0D;
        double high = lateralCeiling;
        Frustum4D fitted = initial;
        long fittedWork = initialWork;
        for (int attempt = 0; attempt < CELL_BUDGET_SEARCH_ATTEMPTS; attempt++) {
            double candidateLateral = (low + high) * 0.5D;
            Frustum4D candidate = frustumFor(eye, structure, axial, candidateLateral);
            long candidateWork = estimateCandidateWork(structure, frame, eye, candidate, axial, budget);
            if (candidateWork <= budget) {
                low = candidateLateral;
                fitted = candidate;
                fittedWork = candidateWork;
            } else {
                high = candidateLateral;
            }
        }
        return new FitSolution(fitted, axial, low, fittedWork);
    }

    private FitSolution fitAxialWithinBudget(PortalStructure structure,
                                             PortalFrame frame,
                                             Location eye,
                                             double axialCeiling,
                                             int budget) {
        double low = 0.0D;
        double high = axialCeiling;
        Frustum4D fitted = frustumFor(eye, structure, 0.0D, 0.0D);
        long fittedWork = estimateCandidateWork(structure, frame, eye, fitted, 0.0D, budget);
        if (fittedWork > budget) {
            return new FitSolution(Frustum4D.empty(), 0.0D, 0.0D, 0L);
        }
        for (int attempt = 0; attempt < CELL_BUDGET_SEARCH_ATTEMPTS; attempt++) {
            double candidateAxial = (low + high) * 0.5D;
            Frustum4D candidate = frustumFor(eye, structure, candidateAxial, 0.0D);
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
            return new FitSolution(Frustum4D.empty(), 0.0D, 0.0D, 0L);
        }
        return new FitSolution(fitted, low, 0.0D, fittedWork);
    }

    long estimateCandidateWork(PortalStructure structure,
                               PortalFrame frame,
                               Location eye,
                               Frustum4D frustum,
                               double depthBlocks,
                               long limit) {
        AxisAlignedBB region = frustum.getRegion();
        int[] axisMin = scratchAxisMin;
        axisMin[0] = PortalProjector.minBlockForCenter(region.getXa());
        axisMin[1] = PortalProjector.minBlockForCenter(region.getYa());
        axisMin[2] = PortalProjector.minBlockForCenter(region.getZa());
        int[] axisMax = scratchAxisMax;
        axisMax[0] = PortalProjector.maxBlockForCenter(region.getXb());
        axisMax[1] = PortalProjector.maxBlockForCenter(region.getYb());
        axisMax[2] = PortalProjector.maxBlockForCenter(region.getZb());

        Location center = structure.getCenter();
        double originX = center.getX();
        double originY = center.getY();
        double originZ = center.getZ();
        Direction normal = frame.getNormal();
        double eyeRelX = eye.getX() - originX;
        double eyeRelY = eye.getY() - originY;
        double eyeRelZ = eye.getZ() - originZ;
        boolean eyeFrontSide = dot(eyeRelX, eyeRelY, eyeRelZ, normal) >= 0.0D;
        PortalFrame projectionFrame = frame.view(eyeFrontSide);
        double projectionEyeDot = dot(eyeRelX, eyeRelY, eyeRelZ, projectionFrame.getNormal());
        double portalPlaneClearance = PortalProjector.portalPlaneClearance(structure.getArea(), frame);
        double maxProjectionDepth = depthBlocks + portalPlaneClearance;
        double signedMinDistance = eyeFrontSide ? -maxProjectionDepth : portalPlaneClearance;
        double signedMaxDistance = eyeFrontSide ? -portalPlaneClearance : maxProjectionDepth;
        clampNormalBounds(axisMin, axisMax, normal, originX, originY, originZ, signedMinDistance, signedMaxDistance);

        Direction projectionNormal = projectionFrame.getNormal();
        Direction projectionRight = projectionFrame.getRight();
        Direction projectionUp = projectionFrame.getUp();
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

        ProjectorPlaneWindow planeWindow = ProjectorPlaneWindow.create(structure, structure.getArea(), projectionFrame,
            originX, originY, originZ, Settings.PROJECTION_APERTURE_PADDING_BLOCKS, projectionEyeDot);
        double normalOrigin = coordinate(normalAxis, originX, originY, originZ);
        double projectionFacingNormal = coordinate(projectionNormal, normalAxis);
        long work = 0L;
        for (int n = axisMin[normalAxis]; n <= axisMax[normalAxis]; n++) {
            double slabSignedDistance = projectionFacingNormal * ((n + 0.5D) - normalOrigin);
            if (!planeWindow.slabWindow(eye.getX(), eye.getY(), eye.getZ(), slabSignedDistance, scratchSlabWindowBounds)) {
                continue;
            }
            int rightMin = ProjectorPlaneWindow.slabBlockMin(scratchSlabWindowBounds[0], scratchSlabWindowBounds[1],
                rightSign, rightOrigin, axisMin[rightAxis]);
            int rightMax = ProjectorPlaneWindow.slabBlockMax(scratchSlabWindowBounds[0], scratchSlabWindowBounds[1],
                rightSign, rightOrigin, axisMax[rightAxis]);
            int upMin = ProjectorPlaneWindow.slabBlockMin(scratchSlabWindowBounds[2], scratchSlabWindowBounds[3],
                upSign, upOrigin, axisMin[upAxis]);
            int upMax = ProjectorPlaneWindow.slabBlockMax(scratchSlabWindowBounds[2], scratchSlabWindowBounds[3],
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
                                          Direction normal,
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
        axisMin[normalAxis] = Math.max(axisMin[normalAxis], PortalProjector.minBlockForCenter(Math.min(centerA, centerB)));
        axisMax[normalAxis] = Math.min(axisMax[normalAxis], PortalProjector.maxBlockForCenter(Math.max(centerA, centerB)));
    }

    private static double lateralCeiling(double axial, double lateralPadBlocks) {
        return Math.min(Math.max(lateralPadBlocks, 0.0D), axial);
    }

    private static double dot(double x, double y, double z, Direction direction) {
        return (x * direction.x()) + (y * direction.y()) + (z * direction.z());
    }

    private static int axis(Direction direction) {
        return direction.x() != 0 ? 0 : direction.y() != 0 ? 1 : 2;
    }

    private static double coordinate(Direction direction, int axis) {
        return axis == 0 ? direction.x() : axis == 1 ? direction.y() : direction.z();
    }

    private static double coordinate(int axis, double x, double y, double z) {
        return axis == 0 ? x : axis == 1 ? y : z;
    }

    private double capProjectionDistance(Player observer, double requestedBlocks) {
        if (!Settings.PROJECTION_CLIENT_VIEW_DISTANCE_CAP || observer == null) {
            return requestedBlocks;
        }
        int serverChunks = Wormholes.instance == null ? 8 : Wormholes.instance.getServer().getViewDistance();
        int clientChunks = clientViewDistance(observer);
        if (clientChunks <= 0) {
            clientChunks = serverChunks;
        }
        int chunks = Math.max(2, Math.min(serverChunks, clientChunks));
        double cap = chunks * 16.0D;
        return Math.max(1.0D, Math.min(requestedBlocks, cap));
    }

    private static Method resolveClientViewDistanceMethod() {
        try {
            return Player.class.getMethod("getClientViewDistance");
        } catch (Throwable ignored) {
            return null;
        }
    }

    int clientViewDistance(Player observer) {
        if (clientViewDistanceMethod == null || clientViewDistanceFailed || observer == null) {
            return 0;
        }
        try {
            Object result = clientViewDistanceMethod.invoke(observer);
            if (result instanceof Integer) {
                return (Integer) result;
            }
        } catch (Throwable failure) {
            clientViewDistanceFailed = true;
            Wormholes plugin = Wormholes.instance;
            if (plugin != null) {
                plugin.getLogger().log(Level.WARNING, "[Projector] Player.getClientViewDistance() is unusable on this platform,"
                    + " falling back to the server view distance for every projection", failure);
            }
        }
        return 0;
    }

    boolean clientViewDistanceFailed() {
        return clientViewDistanceFailed;
    }

    private record FitSolution(Frustum4D frustum, double axial, double lateral, long candidateWork) {
    }
}
