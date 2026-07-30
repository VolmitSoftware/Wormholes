package art.arcane.wormholes.render;

import art.arcane.wormholes.portal.PortalFrame;

final class ProjectorFrameTransform {
    private double fromRightX;
    private double fromRightY;
    private double fromRightZ;
    private double fromUpX;
    private double fromUpY;
    private double fromUpZ;
    private double fromNormalX;
    private double fromNormalY;
    private double fromNormalZ;
    private double toRightX;
    private double toRightY;
    private double toRightZ;
    private double toUpX;
    private double toUpY;
    private double toUpZ;
    private double toNormalX;
    private double toNormalY;
    private double toNormalZ;
    private double fromOriginX;
    private double fromOriginY;
    private double fromOriginZ;
    private double toOriginX;
    private double toOriginY;
    private double toOriginZ;
    private boolean mirror;
    private double mirrorXX;
    private double mirrorXY;
    private double mirrorXZ;
    private double mirrorYX;
    private double mirrorYY;
    private double mirrorYZ;
    private double mirrorZX;
    private double mirrorZY;
    private double mirrorZZ;
    private double coordinateSnapTolerance;
    boolean signedPermutation;
    private int permutationSourceX;
    private int permutationSourceY;
    private int permutationSourceZ;
    private double permutationScaleX;
    private double permutationScaleY;
    private double permutationScaleZ;
    private final double[] scratchOffset = new double[3];
    private final double[] scratchMatrix = new double[9];

    void configure(PortalFrame from, PortalFrame to,
                   double fromOriginX, double fromOriginY, double fromOriginZ,
                   double toOriginX, double toOriginY, double toOriginZ) {
        this.mirror = false;
        this.fromRightX = from.getRight().x();
        this.fromRightY = from.getRight().y();
        this.fromRightZ = from.getRight().z();
        this.fromUpX = from.getUp().x();
        this.fromUpY = from.getUp().y();
        this.fromUpZ = from.getUp().z();
        this.fromNormalX = from.getNormal().x();
        this.fromNormalY = from.getNormal().y();
        this.fromNormalZ = from.getNormal().z();
        this.toRightX = to.getRight().x();
        this.toRightY = to.getRight().y();
        this.toRightZ = to.getRight().z();
        this.toUpX = to.getUp().x();
        this.toUpY = to.getUp().y();
        this.toUpZ = to.getUp().z();
        this.toNormalX = to.getNormal().x();
        this.toNormalY = to.getNormal().y();
        this.toNormalZ = to.getNormal().z();
        this.fromOriginX = fromOriginX;
        this.fromOriginY = fromOriginY;
        this.fromOriginZ = fromOriginZ;
        this.toOriginX = toOriginX;
        this.toOriginY = toOriginY;
        this.toOriginZ = toOriginZ;
        this.coordinateSnapTolerance = coordinateSnapTolerance(
            fromOriginX, fromOriginY, fromOriginZ, toOriginX, toOriginY, toOriginZ);
        double[] matrix = scratchMatrix;
        matrix[0] = (fromRightX * toRightX) + (fromUpX * toUpX) + (fromNormalX * toNormalX);
        matrix[1] = (fromRightY * toRightX) + (fromUpY * toUpX) + (fromNormalY * toNormalX);
        matrix[2] = (fromRightZ * toRightX) + (fromUpZ * toUpX) + (fromNormalZ * toNormalX);
        matrix[3] = (fromRightX * toRightY) + (fromUpX * toUpY) + (fromNormalX * toNormalY);
        matrix[4] = (fromRightY * toRightY) + (fromUpY * toUpY) + (fromNormalY * toNormalY);
        matrix[5] = (fromRightZ * toRightY) + (fromUpZ * toUpY) + (fromNormalZ * toNormalY);
        matrix[6] = (fromRightX * toRightZ) + (fromUpX * toUpZ) + (fromNormalX * toNormalZ);
        matrix[7] = (fromRightY * toRightZ) + (fromUpY * toUpZ) + (fromNormalY * toNormalZ);
        matrix[8] = (fromRightZ * toRightZ) + (fromUpZ * toUpZ) + (fromNormalZ * toNormalZ);
        detectSignedPermutation(matrix);
    }

    void configureMirror(PortalFrame frame, int quarterTurns,
                         double originX, double originY, double originZ,
                         double[] scratch3) {
        this.mirror = true;
        this.fromOriginX = originX;
        this.fromOriginY = originY;
        this.fromOriginZ = originZ;
        this.toOriginX = originX;
        this.toOriginY = originY;
        this.toOriginZ = originZ;
        this.coordinateSnapTolerance = coordinateSnapTolerance(
            originX, originY, originZ, originX, originY, originZ);
        PortalCoordMap.mirrorDisplayToSourceVectorInto(1.0D, 0.0D, 0.0D, frame, quarterTurns, scratch3);
        mirrorXX = scratch3[0];
        mirrorYX = scratch3[1];
        mirrorZX = scratch3[2];
        PortalCoordMap.mirrorDisplayToSourceVectorInto(0.0D, 1.0D, 0.0D, frame, quarterTurns, scratch3);
        mirrorXY = scratch3[0];
        mirrorYY = scratch3[1];
        mirrorZY = scratch3[2];
        PortalCoordMap.mirrorDisplayToSourceVectorInto(0.0D, 0.0D, 1.0D, frame, quarterTurns, scratch3);
        mirrorXZ = scratch3[0];
        mirrorYZ = scratch3[1];
        mirrorZZ = scratch3[2];
        double[] matrix = scratchMatrix;
        matrix[0] = mirrorXX;
        matrix[1] = mirrorXY;
        matrix[2] = mirrorXZ;
        matrix[3] = mirrorYX;
        matrix[4] = mirrorYY;
        matrix[5] = mirrorYZ;
        matrix[6] = mirrorZX;
        matrix[7] = mirrorZY;
        matrix[8] = mirrorZZ;
        detectSignedPermutation(matrix);
    }

    private void detectSignedPermutation(double[] matrix) {
        signedPermutation = false;
        int sourceX = permutationRowSource(matrix, 0);
        int sourceY = permutationRowSource(matrix, 1);
        int sourceZ = permutationRowSource(matrix, 2);
        if (sourceX < 0 || sourceY < 0 || sourceZ < 0) {
            return;
        }
        permutationSourceX = sourceX;
        permutationSourceY = sourceY;
        permutationSourceZ = sourceZ;
        permutationScaleX = matrix[sourceX];
        permutationScaleY = matrix[3 + sourceY];
        permutationScaleZ = matrix[6 + sourceZ];
        signedPermutation = true;
    }

    private static int permutationRowSource(double[] matrix, int row) {
        int source = -1;
        for (int column = 0; column < 3; column++) {
            double value = matrix[(row * 3) + column];
            if (value == 0.0D) {
                continue;
            }
            if (source >= 0 || (value != 1.0D && value != -1.0D)) {
                return -1;
            }
            source = column;
        }
        return source;
    }

    void apply(double x, double y, double z, double[] out3) {
        double offsetX = x - fromOriginX;
        double offsetY = y - fromOriginY;
        double offsetZ = z - fromOriginZ;
        if (signedPermutation) {
            double[] offsets = scratchOffset;
            offsets[0] = offsetX;
            offsets[1] = offsetY;
            offsets[2] = offsetZ;
            out3[0] = snapNearInteger(toOriginX + (permutationScaleX * offsets[permutationSourceX]), coordinateSnapTolerance);
            out3[1] = snapNearInteger(toOriginY + (permutationScaleY * offsets[permutationSourceY]), coordinateSnapTolerance);
            out3[2] = snapNearInteger(toOriginZ + (permutationScaleZ * offsets[permutationSourceZ]), coordinateSnapTolerance);
            return;
        }
        if (mirror) {
            out3[0] = snapNearInteger(toOriginX + (offsetX * mirrorXX) + (offsetY * mirrorXY) + (offsetZ * mirrorXZ), coordinateSnapTolerance);
            out3[1] = snapNearInteger(toOriginY + (offsetX * mirrorYX) + (offsetY * mirrorYY) + (offsetZ * mirrorYZ), coordinateSnapTolerance);
            out3[2] = snapNearInteger(toOriginZ + (offsetX * mirrorZX) + (offsetY * mirrorZY) + (offsetZ * mirrorZZ), coordinateSnapTolerance);
            return;
        }
        double frameRight = offsetX * fromRightX + offsetY * fromRightY + offsetZ * fromRightZ;
        double frameUp = offsetX * fromUpX + offsetY * fromUpY + offsetZ * fromUpZ;
        double frameNormal = offsetX * fromNormalX + offsetY * fromNormalY + offsetZ * fromNormalZ;
        out3[0] = snapNearInteger(toOriginX + frameRight * toRightX + frameUp * toUpX + frameNormal * toNormalX, coordinateSnapTolerance);
        out3[1] = snapNearInteger(toOriginY + frameRight * toRightY + frameUp * toUpY + frameNormal * toNormalY, coordinateSnapTolerance);
        out3[2] = snapNearInteger(toOriginZ + frameRight * toRightZ + frameUp * toUpZ + frameNormal * toNormalZ, coordinateSnapTolerance);
    }

    private static double coordinateSnapTolerance(double fromX,
                                                  double fromY,
                                                  double fromZ,
                                                  double toX,
                                                  double toY,
                                                  double toZ) {
        double largestUlp = Math.max(Math.ulp(fromX), Math.ulp(fromY));
        largestUlp = Math.max(largestUlp, Math.ulp(fromZ));
        largestUlp = Math.max(largestUlp, Math.ulp(toX));
        largestUlp = Math.max(largestUlp, Math.ulp(toY));
        largestUlp = Math.max(largestUlp, Math.ulp(toZ));
        return Math.max(1.0E-10D, largestUlp * 8.0D);
    }

    private static double snapNearInteger(double value, double tolerance) {
        double nearestInteger = Math.rint(value);
        return Math.abs(value - nearestInteger) <= tolerance ? nearestInteger : value;
    }
}
