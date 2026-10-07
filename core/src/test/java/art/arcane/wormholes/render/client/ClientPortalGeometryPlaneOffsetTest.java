package art.arcane.wormholes.render.client;

import art.arcane.wormholes.door.DoorwayPlane;
import art.arcane.optics.stream.ViewStreamCodec;
import art.arcane.optics.stream.ViewStreamProtocolException;
import art.arcane.optics.stream.ViewStreamReader;
import art.arcane.optics.stream.ViewStreamWriter;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.aperture.ApertureCells;
import art.arcane.optics.claim.ProjectedBlockClaim;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.wormholes.portal.ApertureKind;

final class ClientPortalGeometryPlaneOffsetTest {
    @Test
    void planeOffsetSurvivesTheWireBitForBit() throws ViewStreamProtocolException {
        for (Face normal : Face.values()) {
            ApertureDescriptor child = geometry(normal, ApertureKind.FRAME, 0.0D, List.of());
            ApertureDescriptor door = geometry(normal, ApertureKind.DOOR, DoorwayPlane.planeOffset(normal), List.of(child));
            ViewStreamWriter out = new ViewStreamWriter();
            ViewStreamCodec.writeGeometry(out, door, 0);
            ViewStreamReader in = new ViewStreamReader(out.toByteArray());
            ApertureDescriptor decoded = ViewStreamCodec.readGeometry(in, 0);
            in.expectEnd();
            assertEquals(door, decoded);
            assertEquals(Double.doubleToRawLongBits(door.planeOffset()), Double.doubleToRawLongBits(decoded.planeOffset()));
        }
    }

    @Test
    void planeOffsetIsPartOfTheSurfaceIdentity() {
        ApertureDescriptor flush = geometry(Face.N, ApertureKind.DOOR, 0.0D, List.of());
        ApertureDescriptor recessed = geometry(Face.N, ApertureKind.DOOR, DoorwayPlane.planeOffset(Face.N), List.of());
        assertNotEquals(flush, recessed);
        assertNotEquals(flush.hashCode(), recessed.hashCode());
    }

    @Test
    void doorPlaneSitsAtTheDoorwayPlaneOffset() {
        for (Face normal : Face.values()) {
            ApertureDescriptor door = geometry(normal, ApertureKind.DOOR, DoorwayPlane.planeOffset(normal), List.of());
            int origin = normal.x() != 0 ? door.originX() : normal.y() != 0 ? door.originY() : door.originZ();
            double expected = origin + 0.5D + (normal.x() + normal.y() + normal.z()) * DoorwayPlane.planeOffset(normal);
            assertEquals(expected, door.planeCoordinate(), 0.0D, normal.name());
        }
    }

    @Test
    void framesSitOnTheBlockCentreRegardlessOfKind() {
        for (Face normal : Face.values()) {
            ApertureDescriptor frame = geometry(normal, ApertureKind.FRAME, 0.0D, List.of());
            ApertureDescriptor unshiftedDoor = geometry(normal, ApertureKind.DOOR, 0.0D, List.of());
            int origin = normal.x() != 0 ? frame.originX() : normal.y() != 0 ? frame.originY() : frame.originZ();
            assertEquals(origin + 0.5D, frame.planeCoordinate(), 0.0D, normal.name());
            assertEquals(origin + 0.5D, unshiftedDoor.planeCoordinate(), 0.0D, normal.name());
        }
    }

    private static ApertureDescriptor geometry(Face normal, int kind, double planeOffset, List<ApertureDescriptor> nested) {
        Frame frame = Frame.canonical(normal);
        int[] min = {-9, 70, 14};
        int[] max = {-9, 70, 14};
        max[frame.getRight().axisIndex()] += 1;
        max[frame.getUp().axisIndex()] += 2;
        ApertureCells aperture = new ApertureCells();
        aperture.setArea(new Box(min[0], max[0] + 0.999D, min[1], max[1] + 0.999D, min[2], max[2] + 0.999D));
        return ApertureDescriptor.fromPortal(new ApertureDescriptor.Source(aperture, frame, true, false, 0, 2.0D, 0.75D, 0.2D, 64, 3,
            ApertureDescriptor.BLACKOUT_OFF, 0, ApertureDescriptor.MASK_AIR_PROJECT, ProjectedBlockClaim.LightingPolicy.LOCAL, 0,
            kind, planeOffset, 0, 11L, nested)).orElseThrow();
    }
}
