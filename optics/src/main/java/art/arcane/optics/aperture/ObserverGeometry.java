package art.arcane.optics.aperture;

public final class ObserverGeometry {
    private ObserverGeometry() {
    }

    public static boolean isLookingTowardPortal(double eyeX, double eyeY, double eyeZ,
                                                double centerX, double centerY, double centerZ,
                                                double lookX, double lookY, double lookZ, double minimumDot) {
        double dx = centerX - eyeX;
        double dy = centerY - eyeY;
        double dz = centerZ - eyeZ;
        double distanceSquared = (dx * dx) + (dy * dy) + (dz * dz);
        if (distanceSquared <= 1.0E-6D) {
            return true;
        }
        double inverseDistance = 1.0D / Math.sqrt(distanceSquared);
        double dot = ((lookX * dx) + (lookY * dy) + (lookZ * dz)) * inverseDistance;
        return dot >= minimumDot;
    }

    public static boolean hasStablePortalSide(double eyeX,
                                       double eyeY,
                                       double eyeZ,
                                       double originX,
                                       double originY,
                                       double originZ,
                                       double normalX,
                                       double normalY,
                                       double normalZ,
                                       double minimumAbsoluteDot) {
        if (minimumAbsoluteDot <= 0.0D) {
            return true;
        }
        double dx = eyeX - originX;
        double dy = eyeY - originY;
        double dz = eyeZ - originZ;
        double distanceSquared = (dx * dx) + (dy * dy) + (dz * dz);
        if (distanceSquared <= 1.0E-6D) {
            return true;
        }
        double inverseDistance = 1.0D / Math.sqrt(distanceSquared);
        double dot = ((dx * normalX) + (dy * normalY) + (dz * normalZ)) * inverseDistance;
        return Math.abs(dot) >= minimumAbsoluteDot;
    }

}
