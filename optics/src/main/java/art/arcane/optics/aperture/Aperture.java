package art.arcane.optics.aperture;

import art.arcane.optics.math.Vec3d;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;

import java.util.List;

public interface Aperture {
    Box getArea();

    Vec3d getApertureCenter();

    List<Box> getCachedApertureFaces(Face face);
}
