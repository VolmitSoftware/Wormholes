package art.arcane.wormholes.render;

import java.lang.reflect.Method;
import java.util.function.DoubleUnaryOperator;
import java.util.logging.Level;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.util.AxisAlignedBB;

final class ProjectorViewFrustum {
    static final double CELL_BUDGET_MIN_RANGE = 4.0D;
    private static final double CELL_BUDGET_FLOOR_RANGE = 1.0D;
    private static final double PERSPECTIVE_MIN_STANDOFF = 1.0E-6D;

    private static final int CELL_BUDGET_LATERAL_ATTEMPTS = 4;
    private static final double CELL_BUDGET_LATERAL_MIN_SHRINK = 0.35D;
    private static final double CELL_BUDGET_LATERAL_MAX_GROWTH = 4.0D;
    private static final double CELL_BUDGET_LATERAL_STEP = 0.05D;

    private static final int CELL_BUDGET_FIT_ATTEMPTS = 4;
    private static final int CELL_BUDGET_FORCED_ATTEMPTS = 8;
    private static final double CELL_BUDGET_MIN_SHRINK = 0.35D;
    private static final double CELL_BUDGET_FORCED_SHRINK = 0.5D;
    private static final double CELL_BUDGET_FIT_MARGIN = 0.90D;

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
    private int cachedFitBudget;
    private double cachedFitNearPlanePadding;
    private double cachedFitCullingRatio;
    private double cachedFitAperturePadding;
    private double cachedFittedAxial;
    private long fitRecalculationCount;
    private double fittedAxial;

    ProjectorViewFrustum() {
        this(CLIENT_VIEW_DISTANCE_METHOD);
    }

    ProjectorViewFrustum(Method clientViewDistanceMethod) {
        this.clientViewDistanceMethod = clientViewDistanceMethod;
        this.clientViewDistanceFailed = false;
        this.cachedStructureRevision = Long.MIN_VALUE;
        this.cachedFitStructureRevision = Long.MIN_VALUE;
        this.fitRecalculationCount = 0L;
    }

    Frustum4D fit(Player observer, PortalStructure structure, Location eye, double portalDepth, double lateralPadBlocks) {
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
            && cachedFitBudget == budget
            && cachedFitNearPlanePadding == nearPlanePadding
            && cachedFitCullingRatio == cullingRatio
            && cachedFitAperturePadding == aperturePadding) {
            fittedAxial = cachedFittedAxial;
            return reusable;
        }
        fitRecalculationCount++;
        double ceiling = lateralCeiling(axial, lateralPadBlocks);
        double lateral = tuneLateralToCellBudget(structure, eye, axial, budget, ceiling,
            fitLateralToCellBudget(structure, eye, axial, budget, lateralPadBlocks));
        double fitted = fitRangeToCellBudget(axial, budget,
            candidate -> regionCellCount(frustumFor(eye, structure, candidate, lateral).getRegion()));
        fittedAxial = fitted;
        Frustum4D result = frustumFor(eye, structure, fitted, lateral);
        cachedFit = result;
        cachedFitStructure = structure;
        cachedFitStructureRevision = structureRevision;
        cachedFitEyeX = eye.getX();
        cachedFitEyeY = eye.getY();
        cachedFitEyeZ = eye.getZ();
        cachedFitAxial = axial;
        cachedFitLateralPad = lateralPadBlocks;
        cachedFitBudget = budget;
        cachedFitNearPlanePadding = nearPlanePadding;
        cachedFitCullingRatio = cullingRatio;
        cachedFitAperturePadding = aperturePadding;
        cachedFittedAxial = fitted;
        return result;
    }

    private double tuneLateralToCellBudget(PortalStructure structure,
                                           Location eye,
                                           double axial,
                                           int budget,
                                           double ceiling,
                                           double lateral) {
        if (budget <= 0) {
            return lateral;
        }
        double target = budget * CELL_BUDGET_FIT_MARGIN;
        double tuned = lateral;
        for (int attempt = 0; attempt < CELL_BUDGET_LATERAL_ATTEMPTS; attempt++) {
            double cells = regionCellCount(frustumFor(eye, structure, axial, tuned).getRegion());
            if (cells > budget) {
                tuned = tuned * Math.max(CELL_BUDGET_LATERAL_MIN_SHRINK, Math.sqrt(target / cells));
                continue;
            }
            if (cells >= target || tuned >= ceiling) {
                return tuned;
            }
            double growth = Math.min(CELL_BUDGET_LATERAL_MAX_GROWTH, Math.sqrt(target / Math.max(cells, 1.0D)));
            double grown = Math.min(ceiling, tuned * growth);
            if (grown - tuned <= CELL_BUDGET_LATERAL_STEP) {
                return tuned;
            }
            if (regionCellCount(frustumFor(eye, structure, axial, grown).getRegion()) > budget) {
                return tuned;
            }
            tuned = grown;
        }
        return tuned;
    }

    double fittedDepth() {
        return fittedAxial;
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

    static double fitLateralToCellBudget(PortalStructure structure,
                                         Location eye,
                                         double axial,
                                         int budget,
                                         double lateralPadBlocks) {
        double ceiling = lateralCeiling(axial, lateralPadBlocks);
        if (budget <= 0) {
            return ceiling;
        }
        AxisAlignedBB area = structure.getArea();
        double padding = Settings.PROJECTION_APERTURE_PADDING_BLOCKS;
        double sizeX = area.sizeX();
        double sizeY = area.sizeY();
        double sizeZ = area.sizeZ();
        double largest = Math.max(sizeX, Math.max(sizeY, sizeZ));
        double smallest = Math.min(sizeX, Math.min(sizeY, sizeZ));
        double middle = (sizeX + sizeY + sizeZ) - largest - smallest;
        double first = largest + (2.0D * padding);
        double second = middle + (2.0D * padding);
        double allowedFaceArea = (budget * CELL_BUDGET_FIT_MARGIN) / Math.max(axial, 1.0D);
        double linear = first + second;
        double constant = (first * second) - allowedFaceArea;
        double solved = constant >= 0.0D
            ? 0.0D
            : (Math.sqrt((linear * linear) - (4.0D * constant)) - linear) * 0.25D;
        return Math.min(ceiling, Math.min(solved, perspectiveHalfSpan(area, eye, axial, smallest)));
    }

    private static double lateralCeiling(double axial, double lateralPadBlocks) {
        return Math.min(Math.max(lateralPadBlocks, 0.0D), axial);
    }

    private static double perspectiveHalfSpan(AxisAlignedBB area, Location eye, double axial, double smallest) {
        double sizeX = area.sizeX();
        double sizeY = area.sizeY();
        double sizeZ = area.sizeZ();
        Vector center = area.center();
        double deltaX = eye.getX() - center.getX();
        double deltaY = eye.getY() - center.getY();
        double deltaZ = eye.getZ() - center.getZ();
        double normalDelta;
        double firstReach;
        double secondReach;
        if (smallest == sizeX) {
            normalDelta = deltaX;
            firstReach = (sizeY * 0.5D) + Math.abs(deltaY);
            secondReach = (sizeZ * 0.5D) + Math.abs(deltaZ);
        } else if (smallest == sizeY) {
            normalDelta = deltaY;
            firstReach = (sizeX * 0.5D) + Math.abs(deltaX);
            secondReach = (sizeZ * 0.5D) + Math.abs(deltaZ);
        } else {
            normalDelta = deltaZ;
            firstReach = (sizeX * 0.5D) + Math.abs(deltaX);
            secondReach = (sizeY * 0.5D) + Math.abs(deltaY);
        }
        double standoff = Math.max(PERSPECTIVE_MIN_STANDOFF, Math.abs(normalDelta) - (smallest * 0.5D));
        double reach = Math.max(firstReach, secondReach) + Settings.PROJECTION_APERTURE_PADDING_BLOCKS;
        return reach * (axial / standoff);
    }

    static double fitRangeToCellBudget(double range, int budget, DoubleUnaryOperator cellCountForRange) {
        if (budget <= 0 || range <= CELL_BUDGET_MIN_RANGE) {
            return range;
        }
        double fitted = range;
        for (int attempt = 0; attempt < CELL_BUDGET_FIT_ATTEMPTS; attempt++) {
            double cells = cellCountForRange.applyAsDouble(fitted);
            if (cells <= budget) {
                return fitted;
            }
            double shrink = Math.max(CELL_BUDGET_MIN_SHRINK, Math.cbrt((budget * CELL_BUDGET_FIT_MARGIN) / cells));
            double next = fitted * shrink;
            if (next <= CELL_BUDGET_MIN_RANGE) {
                fitted = CELL_BUDGET_MIN_RANGE;
                break;
            }
            fitted = next;
        }
        for (int attempt = 0; attempt < CELL_BUDGET_FORCED_ATTEMPTS; attempt++) {
            if (cellCountForRange.applyAsDouble(fitted) <= budget) {
                return fitted;
            }
            double next = fitted * CELL_BUDGET_FORCED_SHRINK;
            if (next <= CELL_BUDGET_FLOOR_RANGE) {
                fitted = CELL_BUDGET_FLOOR_RANGE;
                break;
            }
            fitted = next;
        }
        return fitted;
    }

    private static double regionCellCount(AxisAlignedBB region) {
        double sizeX = Math.max(1.0D, region.getXb() - region.getXa());
        double sizeY = Math.max(1.0D, region.getYb() - region.getYa());
        double sizeZ = Math.max(1.0D, region.getZb() - region.getZa());
        return sizeX * sizeY * sizeZ;
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
}
