package art.arcane.wormholes.door.view;

import art.arcane.wormholes.door.DoorForm;
import art.arcane.wormholes.door.DoorPlanePairing;
import art.arcane.wormholes.door.DoorwayPlane;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.util.Cuboid;
import art.arcane.wormholes.util.Direction;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;

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
            return PortalFrame.canonical(plane.half() == Bisected.Half.TOP ? Direction.U : Direction.D);
        }
        return PortalFrame.fromNormalUp(normalOf(plane.facing()), Direction.U);
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

    /** The cells a traveler passes through: both door halves, or the single trapdoor block. */
    public static Cuboid cells(World world, DoorwayPlane plane) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(plane, "plane");
        int topY = plane.form() == DoorForm.TRAPDOOR ? plane.blockY() : plane.blockY() + 1;
        return new Cuboid(world,
            plane.blockX(), plane.blockY(), plane.blockZ(),
            plane.blockX(), topY, plane.blockZ());
    }

    /** How tall the aperture is, in blocks. */
    public static double height(DoorwayPlane plane) {
        return Objects.requireNonNull(plane, "plane").form() == DoorForm.TRAPDOOR ? 1.0D : 2.0D;
    }

    public static Direction normalOf(BlockFace facing) {
        return switch (Objects.requireNonNull(facing, "facing")) {
            case NORTH -> Direction.N;
            case SOUTH -> Direction.S;
            case EAST -> Direction.E;
            case WEST -> Direction.W;
            default -> throw new IllegalArgumentException("A doorway must face north, south, east, or west");
        };
    }
}
