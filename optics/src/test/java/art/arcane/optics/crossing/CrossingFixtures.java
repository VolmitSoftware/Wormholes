package art.arcane.optics.crossing;

import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.math.Vec3d;

final class CrossingFixtures {
    private CrossingFixtures() {
    }

    static OpticTransform toward(PlaneCrossing crossing, Frame exit, Vec3d exitOrigin) {
        return OpticTransform.between(crossing.frame(), crossing.origin(), exit.view(crossing.frontSide()), exitOrigin);
    }
}
