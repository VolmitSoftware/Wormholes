package art.arcane.optics.aperture;

public interface PointFactory<R> {
    R create(double x, double y, double z);
}
