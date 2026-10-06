package art.arcane.wormholes.render.client;

import art.arcane.wormholes.door.DoorwayPlane;
import art.arcane.wormholes.network.client.ClientViewCodec;
import art.arcane.wormholes.network.client.ClientViewProtocolException;
import art.arcane.wormholes.network.client.ClientViewReader;
import art.arcane.wormholes.network.client.ClientViewWriter;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalGeometry;
import art.arcane.wormholes.render.ProjectedBlockClaim;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

final class ClientPortalGeometryPlaneOffsetTest {
    @Test
    void planeOffsetSurvivesTheWireBitForBit() throws ClientViewProtocolException {
        for (Direction normal : Direction.values()) {
            ClientPortalGeometry child = geometry(normal, ClientPortalGeometry.KIND_FRAME, 0.0D, List.of());
            ClientPortalGeometry door = geometry(normal, ClientPortalGeometry.KIND_DOOR, DoorwayPlane.planeOffset(normal), List.of(child));
            ClientViewWriter out = new ClientViewWriter();
            ClientViewCodec.writeGeometry(out, door, 0);
            ClientViewReader in = new ClientViewReader(out.toByteArray());
            ClientPortalGeometry decoded = ClientViewCodec.readGeometry(in, 0);
            in.expectEnd();
            assertEquals(door, decoded);
            assertEquals(Double.doubleToRawLongBits(door.planeOffset()), Double.doubleToRawLongBits(decoded.planeOffset()));
        }
    }

    @Test
    void planeOffsetIsPartOfTheSurfaceIdentity() {
        ClientPortalGeometry flush = geometry(Direction.N, ClientPortalGeometry.KIND_DOOR, 0.0D, List.of());
        ClientPortalGeometry recessed = geometry(Direction.N, ClientPortalGeometry.KIND_DOOR, DoorwayPlane.planeOffset(Direction.N), List.of());
        assertNotEquals(flush, recessed);
        assertNotEquals(flush.hashCode(), recessed.hashCode());
    }

    @Test
    void doorPlaneSitsAtTheDoorwayPlaneOffset() {
        for (Direction normal : Direction.values()) {
            ClientPortalGeometry door = geometry(normal, ClientPortalGeometry.KIND_DOOR, DoorwayPlane.planeOffset(normal), List.of());
            int origin = normal.x() != 0 ? door.originX() : normal.y() != 0 ? door.originY() : door.originZ();
            double expected = origin + 0.5D + (normal.x() + normal.y() + normal.z()) * DoorwayPlane.planeOffset(normal);
            assertEquals(expected, door.planeCoordinate(), 0.0D, normal.name());
        }
    }

    @Test
    void framesSitOnTheBlockCentreRegardlessOfKind() {
        for (Direction normal : Direction.values()) {
            ClientPortalGeometry frame = geometry(normal, ClientPortalGeometry.KIND_FRAME, 0.0D, List.of());
            ClientPortalGeometry unshiftedDoor = geometry(normal, ClientPortalGeometry.KIND_DOOR, 0.0D, List.of());
            int origin = normal.x() != 0 ? frame.originX() : normal.y() != 0 ? frame.originY() : frame.originZ();
            assertEquals(origin + 0.5D, frame.planeCoordinate(), 0.0D, normal.name());
            assertEquals(origin + 0.5D, unshiftedDoor.planeCoordinate(), 0.0D, normal.name());
        }
    }

    private static ClientPortalGeometry geometry(Direction normal, int kind, double planeOffset, List<ClientPortalGeometry> nested) {
        PortalFrame frame = PortalFrame.canonical(normal);
        int[] min = {-9, 70, 14};
        int[] max = {-9, 70, 14};
        max[ClientPortalGeometry.axisOf(frame.getRight())] += 1;
        max[ClientPortalGeometry.axisOf(frame.getUp())] += 2;
        PortalGeometry aperture = new PortalGeometry();
        aperture.setArea(new AxisAlignedBB(min[0], max[0] + 0.999D, min[1], max[1] + 0.999D, min[2], max[2] + 0.999D));
        return ClientPortalGeometry.fromPortal(new ClientPortalGeometry.Source(aperture, frame, true, false, 0, 2.0D, 0.75D, 0.2D, 64, 3,
            ClientPortalGeometry.BLACKOUT_OFF, 0, ClientPortalGeometry.MASK_AIR_PROJECT, ProjectedBlockClaim.LightingPolicy.LOCAL, 0,
            kind, planeOffset, 0, 11L, nested)).orElseThrow();
    }
}
