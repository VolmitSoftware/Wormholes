package art.arcane.optics.frame;

import java.util.Objects;

import art.arcane.optics.math.Vec3d;

public record ViewWindow(OpticTransform transform, boolean mirror, Vec3d localOrigin, Frame localFrame, Vec3d remoteOrigin,
                         Frame remoteFrame, boolean frontSide, double depth) {
    public ViewWindow {
        Objects.requireNonNull(transform, "transform");
        Objects.requireNonNull(localOrigin, "localOrigin");
        Objects.requireNonNull(localFrame, "localFrame");
        Objects.requireNonNull(remoteOrigin, "remoteOrigin");
        Objects.requireNonNull(remoteFrame, "remoteFrame");
    }

    public static ViewWindow of(boolean mirror, QuarterTurn mirrorTurns, Vec3d localOrigin, Frame localFrame, Vec3d remoteOrigin,
                                Frame remoteFrame, boolean frontSide, double depth) {
        return mirror ? mirror(localOrigin, localFrame, mirrorTurns, frontSide, depth)
            : between(localOrigin, localFrame, remoteOrigin, remoteFrame, frontSide, depth);
    }

    public static ViewWindow between(Vec3d localOrigin, Frame localFrame, Vec3d remoteOrigin, Frame remoteFrame, boolean frontSide,
                                     double depth) {
        OpticTransform transform = OpticTransform.between(remoteFrame.view(frontSide), remoteOrigin, localFrame.view(frontSide), localOrigin);
        return new ViewWindow(transform, false, localOrigin, localFrame, remoteOrigin, remoteFrame, frontSide, depth);
    }

    public static ViewWindow mirror(Vec3d origin, Frame frame, QuarterTurn turns, boolean frontSide, double depth) {
        return new ViewWindow(OpticTransform.mirror(frame, origin, turns), true, origin, frame, origin, frame.flipNormal(), frontSide, depth);
    }

    public OpticTransform toward() {
        return transform.inverse();
    }

    public ViewWindow withDepth(double value) {
        return new ViewWindow(transform, mirror, localOrigin, localFrame, remoteOrigin, remoteFrame, frontSide, value);
    }
}
