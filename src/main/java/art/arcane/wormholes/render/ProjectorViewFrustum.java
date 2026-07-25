package art.arcane.wormholes.render;

import java.lang.reflect.Method;
import java.util.function.DoubleUnaryOperator;
import java.util.logging.Level;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.util.AxisAlignedBB;

final class ProjectorViewFrustum {
    static final double CELL_BUDGET_MIN_RANGE = 4.0D;

    private static final int CELL_BUDGET_FIT_ATTEMPTS = 4;
    private static final double CELL_BUDGET_MIN_SHRINK = 0.35D;
    private static final double CELL_BUDGET_FIT_MARGIN = 0.95D;

    private static final Method CLIENT_VIEW_DISTANCE_METHOD = resolveClientViewDistanceMethod();

    private final Method clientViewDistanceMethod;
    private boolean clientViewDistanceFailed;
    private Frustum4D cachedFrustum;
    private PortalStructure cachedStructure;
    private long cachedStructureRevision;
    private double cachedEyeX;
    private double cachedEyeY;
    private double cachedEyeZ;
    private double cachedRange;
    private double cachedNearPlanePadding;
    private double cachedCullingRatio;
    private double cachedAperturePadding;

    ProjectorViewFrustum() {
        this(CLIENT_VIEW_DISTANCE_METHOD);
    }

    ProjectorViewFrustum(Method clientViewDistanceMethod) {
        this.clientViewDistanceMethod = clientViewDistanceMethod;
        this.clientViewDistanceFailed = false;
        this.cachedStructureRevision = Long.MIN_VALUE;
    }

    double fitRange(Player observer, PortalStructure structure, Location eye, double portalDepth) {
        double range = capProjectionDistance(observer, portalDepth);
        return fitRangeToCellBudget(range, Settings.PROJECTION_MAX_PROJECTED_CELLS,
            candidate -> regionCellCount(frustumFor(eye, structure, candidate).getRegion()));
    }

    Frustum4D frustumFor(Location eye, PortalStructure structure, double range) {
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
            && cachedRange == range
            && cachedNearPlanePadding == nearPlanePadding
            && cachedCullingRatio == cullingRatio
            && cachedAperturePadding == aperturePadding) {
            return cached;
        }
        Frustum4D built = new Frustum4D(eye, structure, range);
        cachedFrustum = built;
        cachedStructure = structure;
        cachedStructureRevision = structureRevision;
        cachedEyeX = eye.getX();
        cachedEyeY = eye.getY();
        cachedEyeZ = eye.getZ();
        cachedRange = range;
        cachedNearPlanePadding = nearPlanePadding;
        cachedCullingRatio = cullingRatio;
        cachedAperturePadding = aperturePadding;
        return built;
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
                return CELL_BUDGET_MIN_RANGE;
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
