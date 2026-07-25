package art.arcane.wormholes.render;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.portal.BlackoutColor;
import art.arcane.wormholes.util.AxisAlignedBB;

final class ProjectorBlackoutSeal {
    private static final double PERPENDICULAR_BAND_MARGIN = 1.0D;

    private BlockData blackoutData;
    private BlackoutColor blackoutColorCache;
    private boolean enabled;
    private int normalAxis;
    private int rightAxis;
    private int upAxis;
    private double facePlane;
    private double farSign;
    private double depthSealThreshold;
    private double rightSealLow;
    private double rightSealHigh;
    private double upSealLow;
    private double upSealHigh;

    void beginPass(BlackoutColor blackoutColor) {
        enabled = true;
        if (blackoutData == null || blackoutColor != blackoutColorCache) {
            blackoutData = parseBlackout(blackoutColor);
            blackoutColorCache = blackoutColor;
        }
    }

    void disable() {
        enabled = false;
    }

    boolean isEnabled() {
        return enabled;
    }

    BlockData data() {
        return blackoutData;
    }

    void configureBands(AxisAlignedBB blackoutArea,
                        int normalAxis,
                        int rightAxis,
                        int upAxis,
                        double originNormal,
                        double eyeNormal,
                        double depthBlocks) {
        this.normalAxis = normalAxis;
        this.rightAxis = rightAxis;
        this.upAxis = upAxis;
        double areaMinNormal = axisMin(blackoutArea, normalAxis);
        double areaMaxNormal = axisMax(blackoutArea, normalAxis);
        facePlane = eyeSideFacePlane(eyeNormal, originNormal, areaMinNormal, areaMaxNormal);
        farSign = eyeNormal >= originNormal ? -1.0D : 1.0D;
        depthSealThreshold = depthBlocks - PERPENDICULAR_BAND_MARGIN;
        double lateralMargin = PERPENDICULAR_BAND_MARGIN + Settings.PROJECTION_APERTURE_PADDING_BLOCKS;
        rightSealLow = axisMin(blackoutArea, rightAxis) - depthBlocks + lateralMargin;
        rightSealHigh = axisMax(blackoutArea, rightAxis) + depthBlocks - lateralMargin;
        upSealLow = axisMin(blackoutArea, upAxis) - depthBlocks + lateralMargin;
        upSealHigh = axisMax(blackoutArea, upAxis) + depthBlocks - lateralMargin;
    }

    boolean covers(ProjectorSample.Kind kind, boolean occluding) {
        return shouldBlackoutSample(kind, occluding);
    }

    boolean sealsCell(double cellX, double cellY, double cellZ) {
        double cellNormal = axisOf(cellX, cellY, cellZ, normalAxis);
        double depth = farSign * (cellNormal - facePlane);
        return isFarPlaneCell(depth, depthSealThreshold,
            axisOf(cellX, cellY, cellZ, rightAxis), rightSealLow, rightSealHigh,
            axisOf(cellX, cellY, cellZ, upAxis), upSealLow, upSealHigh);
    }

    private static double axisOf(double x, double y, double z, int axis) {
        return axis == 0 ? x : (axis == 1 ? y : z);
    }

    private static double axisMin(AxisAlignedBB area, int axis) {
        return axis == 0 ? area.getXa() : (axis == 1 ? area.getYa() : area.getZa());
    }

    private static double axisMax(AxisAlignedBB area, int axis) {
        return axis == 0 ? area.getXb() : (axis == 1 ? area.getYb() : area.getZb());
    }

    private static BlockData parseBlackout(BlackoutColor color) {
        try {
            return Bukkit.createBlockData(color.blockState());
        } catch (IllegalArgumentException e) {
            return Material.AIR.createBlockData();
        }
    }

    static boolean shouldBlackoutSample(ProjectorSample.Kind kind, boolean occluding) {
        return switch (kind) {
            case NO_SAMPLE, MASK_AIR, REMOTE_AIR -> true;
            case BLOCK -> !occluding;
            case OCCLUDED -> false;
        };
    }

    static boolean isFarPlaneCell(double depthBeyondFacePlane, double depthSealThreshold,
                                  double rightCoord, double rightSealLow, double rightSealHigh,
                                  double upCoord, double upSealLow, double upSealHigh) {
        if (depthBeyondFacePlane >= depthSealThreshold) {
            return true;
        }
        return rightCoord <= rightSealLow || rightCoord >= rightSealHigh
            || upCoord <= upSealLow || upCoord >= upSealHigh;
    }

    static double eyeSideFacePlane(double eyeNormal, double originNormal, double areaMinNormal, double areaMaxNormal) {
        return eyeNormal >= originNormal ? areaMaxNormal : areaMinNormal;
    }
}
