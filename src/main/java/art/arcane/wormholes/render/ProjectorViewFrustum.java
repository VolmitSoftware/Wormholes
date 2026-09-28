package art.arcane.wormholes.render;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.render.lod.LodPolicy;
import art.arcane.wormholes.util.BukkitGeometry;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.logging.Level;

final class ProjectorViewFrustum {
    private static final Method CLIENT_VIEW_DISTANCE_METHOD = resolveClientViewDistanceMethod();

    private final Method clientViewDistanceMethod;
    private final ProjectorFrustumFit fitting = new ProjectorFrustumFit(options());
    private boolean clientViewDistanceFailed;

    ProjectorViewFrustum() {
        this(CLIENT_VIEW_DISTANCE_METHOD);
    }

    ProjectorViewFrustum(Method clientViewDistanceMethod) {
        this.clientViewDistanceMethod = clientViewDistanceMethod;
    }

    Frustum4D fit(Player observer, PortalStructure structure, PortalFrame frame, Location eye,
                  double portalDepth, double lateralPadBlocks) {
        fitting.setOptions(options());
        return fitting.fit(structure, frame, BukkitGeometry.vector(eye),
            capProjectionDistance(observer, portalDepth), lateralPadBlocks);
    }

    void setLodPolicy(LodPolicy policy) {
        fitting.setLodPolicy(policy);
    }

    LodPolicy lodPolicy() {
        return fitting.lodPolicy();
    }

    double fittedDepth() {
        return fitting.fittedDepth();
    }

    boolean fittedCoarse() {
        return fitting.fittedCoarse();
    }

    double fittedLateral() {
        return fitting.fittedLateral();
    }

    long fittedCandidateWork() {
        return fitting.fittedCandidateWork();
    }

    long fitRecalculationCount() {
        return fitting.fitRecalculationCount();
    }

    Frustum4D frustumFor(Location eye, PortalStructure structure, double axial, double lateral) {
        fitting.setOptions(options());
        return fitting.frustumFor(BukkitGeometry.vector(eye), structure, axial, lateral);
    }

    long estimateCandidateWork(PortalStructure structure, PortalFrame frame, Location eye,
                               Frustum4D frustum, double depthBlocks, long limit) {
        fitting.setOptions(options());
        return fitting.estimateCandidateWork(structure, frame, BukkitGeometry.vector(eye), frustum, depthBlocks, limit);
    }

    private static ProjectorFrustumFit.Options options() {
        return new ProjectorFrustumFit.Options(Settings.PROJECTION_MAX_PROJECTED_CELLS,
            Settings.NEAR_PLANE_PADDING, Settings.FRUSTUM_CULLING_RATIO, Settings.PROJECTION_APERTURE_PADDING_BLOCKS);
    }

    private double capProjectionDistance(Player observer, double requestedBlocks) {
        if (!Settings.PROJECTION_CLIENT_VIEW_DISTANCE_CAP || observer == null) {
            return requestedBlocks;
        }
        int serverChunks = Wormholes.instance == null ? 8 : Wormholes.instance.getServer().getViewDistance();
        return ProjectorFrustumFit.capDistance(requestedBlocks, serverChunks, clientViewDistance(observer));
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
