package art.arcane.wormholes.portal;

import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.util.Direction;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PortalGeometryStateTest {
    @Test
    void geometricCenterUsesFullCellsWhileCollisionBoundsRemainInset() {
        PortalGeometry geometry = new PortalGeometry();
        geometry.setBlocks(List.of(new GeometryVector(-1, -64, 0)));
        assertEquals(new GeometryVector(-0.5D, -63.5D, 0.5D), geometry.getApertureCenter());
        assertEquals(-0.001D, geometry.getArea().getXb(), 1e-15D);
        geometry.setBlocks(List.of(new GeometryVector(-4, 80, 7), new GeometryVector(2, 80, 13)));
        assertEquals(new GeometryVector(-0.5D, 80.5D, 10.5D), geometry.getApertureCenter());
        geometry.setBlocks(List.of(new GeometryVector(1001, 200, 0), new GeometryVector(1001, 205, 0)));
        assertEquals(new GeometryVector(1001.5D, 203.0D, 0.5D), geometry.getApertureCenter());
        assertEquals(205.999D, geometry.getArea().getYb(), 0.0D);
        PortalGeometry restored = new PortalGeometry();
        PortalStateCodec.readGeometry(PortalStateCodec.writeGeometry("minecraft:overworld", geometry), restored);
        assertEquals(geometry.getApertureCenter(), restored.getApertureCenter());
        assertEquals(geometry.getArea().getYb(), restored.getArea().getYb(), 0.0D);
    }

    @Test
    void sparseApertureRoundTripPreservesHolesAndBounds() {
        PortalGeometry original = new PortalGeometry();
        original.setBlocks(List.of(new GeometryVector(-8, -64, 30), new GeometryVector(-8, -62, 30)));
        Map<String, Object> document = PortalStateCodec.writeGeometry("minecraft:overworld", original);
        PortalGeometry restored = new PortalGeometry();
        PortalStateCodec.readGeometry(document, restored);
        assertEquals(original.getApertureCenter(), restored.getApertureCenter());
        assertEquals(original.getBlockPositions(), restored.getBlockPositions());
        assertFalse(restored.contains(new GeometryVector(-7.5, -62.5, 30.5)));
        assertTrue(restored.contains(new GeometryVector(-7.5, -61.5, 30.5)));
        assertEquals(2, restored.getCachedApertureFaces(Direction.E).size());
        assertFalse(restored.isFullCuboid());
    }

    @Test
    void fullCuboidUsesOneApertureFaceAndChangingCellsInvalidatesCache() {
        PortalGeometry geometry = new PortalGeometry();
        geometry.setBlocks(List.of(new GeometryVector(0, 0, 0), new GeometryVector(0, 1, 0)));
        long revision = geometry.getRevision();
        assertTrue(geometry.isFullCuboid());
        assertEquals(1, geometry.getCachedApertureFaces(Direction.N).size());
        geometry.setBlocks(List.of(new GeometryVector(0, 0, 0), new GeometryVector(0, 2, 0)));
        assertTrue(geometry.getRevision() > revision);
        assertEquals(2, geometry.getCachedApertureFaces(Direction.N).size());
    }

    @Test
    void portalIdentityPreservesRolledFrameAndCanonicalStoragePath() {
        UUID id = UUID.fromString("12345678-1234-4234-8234-123456789012");
        Portal.State state = new Portal.State(id, new GeometryVector(1.5, 64.5, -4.5), "Rolled",
            PortalFrame.fromNormalUp(Direction.U, Direction.E), true);
        Map<String, Object> encoded = new LinkedHashMap<>();
        PortalStateCodec.write(encoded, state);
        Portal.State restored = PortalStateCodec.read(encoded);
        assertEquals(state.id(), restored.id());
        assertEquals(state.origin(), restored.origin());
        assertEquals(Direction.U, restored.frame().getNormal());
        assertEquals(Direction.E, restored.frame().getUp());
        assertEquals(Path.of("portals", "1234", "12345678", id + ".json"), PortalStateCodec.file(Path.of("portals"), id));
    }

    @Test
    void crossingRequiresPassingThroughThePlane() {
        PortalFrame frame = PortalFrame.canonical(Direction.N);
        GeometryVector origin = new GeometryVector(0, 0, 0);
        assertNull(PortalCrossing.intersection(frame, origin, new GeometryVector(1, 1, 2), new GeometryVector(3, 1, 2)));
        assertNull(PortalCrossing.intersection(frame, origin, new GeometryVector(1, 1, 2), new GeometryVector(3, 1, 1)));
        assertEquals(new GeometryVector(2, 1, 0), PortalCrossing.intersection(frame, origin,
            new GeometryVector(1, 1, -2), new GeometryVector(3, 1, 2)));
    }

    @Test
    void crossingMapsBacksideEntryAndVelocityThroughDestinationFrame() {
        PortalFrame source = PortalFrame.canonical(Direction.N);
        GeometryVector origin = new GeometryVector(0, 0, 0);
        PortalCrossing crossing = PortalCrossing.create(source, origin,
            new PortalCrossing.Motion(new GeometryVector(0, 0, 1), new GeometryVector(0, 0, -1),
                new GeometryVector(0, 0, -2), new GeometryVector(0, 0, -1)));
        assertFalse(crossing.frontSide());
        assertEquals(new GeometryVector(0, 0, -2), crossing.outVelocity(source));
        assertNotNull(crossing.outPoint(PortalFrame.canonical(Direction.U), new GeometryVector(10, 20, 30)));
    }
}
