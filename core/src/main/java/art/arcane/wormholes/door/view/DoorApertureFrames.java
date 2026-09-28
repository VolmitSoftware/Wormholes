package art.arcane.wormholes.door.view;

import art.arcane.wormholes.door.DoorForm;
import art.arcane.wormholes.door.DoorHalf;
import art.arcane.wormholes.door.DoorPlanePairing;
import art.arcane.wormholes.door.DoorwayPlane;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.util.Direction;

import java.util.Objects;

/** Frame and cell geometry shared by the door aperture facade and the destinations that feed it. */
public final class DoorApertureFrames {
    private DoorApertureFrames() {
    }

    /**
     * A hinged door looks out along its facing with world up; a trapdoor looks out of the face its
     * plate covers, so a bottom-half plate opens downward and a top-half one upward.
     */
    public static PortalFrame of(DoorwayPlane plane) {
        Objects.requireNonNull(plane, "plane");
        if (plane.form() == DoorForm.TRAPDOOR) {
            return PortalFrame.canonical(plane.half() == DoorHalf.TOP ? Direction.U : Direction.D);
        }
        return PortalFrame.fromNormalUp(plane.facing(), Direction.U);
    }

    /**
     * Where the view through {@code source} comes out.
     *
     * <p>Two hinged doors are mirrored: a traveler leaves the far door on the side it entered the
     * near one, turned around, so the camera looks back out of the mate. Anything involving a
     * trapdoor is a straight-through route, so the camera keeps the mate's own facing.</p>
     */
    public static PortalFrame destinationFrame(DoorwayPlane source, DoorwayPlane mate) {
        PortalFrame frame = of(Objects.requireNonNull(mate, "mate"));
        return DoorPlanePairing.mirrored(Objects.requireNonNull(source, "source"), mate)
            ? frame.flipNormal()
            : frame;
    }

    /** How tall the aperture is, in blocks. */
    public static double height(DoorwayPlane plane) {
        return Objects.requireNonNull(plane, "plane").form() == DoorForm.TRAPDOOR ? 1.0D : 2.0D;
    }

}
