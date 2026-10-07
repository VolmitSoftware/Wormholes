package art.arcane.optics.volume;

public final class LocalEntityEnvelope {
    private LocalEntityEnvelope() {
    }

    public static boolean envelopeFullyProjected(double minX,
                                          double minY,
                                          double minZ,
                                          double maxX,
                                          double maxY,
                                          double maxZ,
                                          ProjectionVolume volume,
                                          ViewVolume frustum) {
        return volume.encloses(volume.signedDistance(minX, minY, minZ), volume.signedDistance(maxX, maxY, maxZ))
            && frustum.containsBox(minX, minY, minZ, maxX, maxY, maxZ);
    }
}
