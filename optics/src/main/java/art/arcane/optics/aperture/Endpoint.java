package art.arcane.optics.aperture;

import art.arcane.optics.frame.Frame;
import art.arcane.optics.math.Vec3;

import java.util.UUID;

public interface Endpoint {
    UUID id();

    Frame frame();

    Vec3 origin();
}
