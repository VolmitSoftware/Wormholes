package art.arcane.wormholes.menu.transit;

import java.util.Objects;

import art.arcane.optics.crossing.ScaleRule;
import art.arcane.wormholes.transit.MomentumPolicy;
import art.arcane.wormholes.transit.OrientationPolicy;
import art.arcane.wormholes.transit.TransitionProfile;

public record TransitMenuView(
    MomentumPolicy momentum,
    MomentumPolicy effectiveMomentum,
    OrientationPolicy orientation,
    OrientationPolicy effectiveOrientation,
    boolean membrane,
    boolean bounce,
    TransitionProfile profile,
    ScaleRule scaleRule,
    boolean gravityRotation
) {
    public TransitMenuView {
        Objects.requireNonNull(effectiveMomentum, "effectiveMomentum");
        Objects.requireNonNull(effectiveOrientation, "effectiveOrientation");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(scaleRule, "scaleRule");
    }
}
