package art.arcane.wormholes.door.view;

import art.arcane.wormholes.door.DoorForm;
import art.arcane.wormholes.door.DoorHalf;
import art.arcane.wormholes.door.DoorPlanePairing;
import art.arcane.wormholes.door.DoorwayPlane;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.math.Face;
import art.arcane.wormholes.portal.ApertureKind;

import java.util.Objects;

/** Frame and cell geometry shared by the door aperture facade and the destinations that feed it. */
public final class DoorApertureFrames {
    private DoorApertureFrames() {
    }

    /**
     * A hinged door looks out along its facing with world up; a trapdoor looks out of the face its
     * plate covers, so a bottom-half plate opens downward and a top-half one upward.
     */
    public static Frame of(DoorwayPlane plane) {
        Objects.requireNonNull(plane, "plane");
        if (plane.form() == DoorForm.TRAPDOOR) {
            return horizontalFrame(plane.half() == DoorHalf.TOP ? Face.U : Face.D, plane.facing());
        }
        return Frame.fromNormalUp(plane.facing(), Face.U);
    }

    /**
     * Where the view through {@code source} comes out.
     *
     * <p>Two hinged doors are mirrored: a traveler leaves the far door on the side it entered the
     * near one, turned around, so the camera looks back out of the mate. Anything involving a
     * trapdoor is a straight-through route, so the camera keeps the mate's own facing.</p>
     */
    public static Frame destinationFrame(DoorwayPlane source, DoorwayPlane mate) {
        Frame frame = of(Objects.requireNonNull(mate, "mate"));
        Objects.requireNonNull(source, "source");
        if (source.horizontal() && mate.horizontal()) {
            return horizontalFrame(of(source).getNormal(), mate.facing());
        }
        return DoorPlanePairing.mirrored(source, mate)
            ? frame.flipNormal()
            : frame;
    }

    /** How tall the aperture is, in blocks. */
    public static double height(DoorwayPlane plane) {
        return Objects.requireNonNull(plane, "plane").form() == DoorForm.TRAPDOOR ? 1.0D : 2.0D;
    }

    public static double geometryPlaneOffset(int kind, Frame frame) {
        return kind == ApertureKind.DOOR ? DoorwayPlane.planeOffset(frame.getNormal()) : 0.0D;
    }

    private static Frame horizontalFrame(Face normal, Face facing) {
        return Frame.fromNormalUp(normal, normal == Face.U ? facing.reverse() : facing);
    }

}
