package art.arcane.wormholes.portal;

import art.arcane.optics.math.Vec3;
import art.arcane.optics.math.Face;
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
import art.arcane.optics.aperture.ApertureCells;
import art.arcane.optics.crossing.PlaneCrossing;
import art.arcane.optics.frame.Frame;

final class PortalGeometryStateTest {
    @Test
    void geometricCenterUsesFullCellsWhileCollisionBoundsRemainInset() {
        ApertureCells geometry = new ApertureCells();
        geometry.setBlocks(List.of(new Vec3(-1, -64, 0)));
        assertEquals(new Vec3(-0.5D, -63.5D, 0.5D), geometry.getApertureCenter());
        assertEquals(-0.001D, geometry.getArea().getXb(), 1e-15D);
        geometry.setBlocks(List.of(new Vec3(-4, 80, 7), new Vec3(2, 80, 13)));
        assertEquals(new Vec3(-0.5D, 80.5D, 10.5D), geometry.getApertureCenter());
        geometry.setBlocks(List.of(new Vec3(1001, 200, 0), new Vec3(1001, 205, 0)));
        assertEquals(new Vec3(1001.5D, 203.0D, 0.5D), geometry.getApertureCenter());
        assertEquals(205.999D, geometry.getArea().getYb(), 0.0D);
        ApertureCells restored = new ApertureCells();
        PortalStateCodec.readGeometry(PortalStateCodec.writeGeometry("minecraft:overworld", geometry), restored);
        assertEquals(geometry.getApertureCenter(), restored.getApertureCenter());
        assertEquals(geometry.getArea().getYb(), restored.getArea().getYb(), 0.0D);
    }

    @Test
    void sparseApertureRoundTripPreservesHolesAndBounds() {
        ApertureCells original = new ApertureCells();
        original.setBlocks(List.of(new Vec3(-8, -64, 30), new Vec3(-8, -62, 30)));
        Map<String, Object> document = PortalStateCodec.writeGeometry("minecraft:overworld", original);
        ApertureCells restored = new ApertureCells();
        PortalStateCodec.readGeometry(document, restored);
        assertEquals(original.getApertureCenter(), restored.getApertureCenter());
        assertEquals(original.getBlockPositions(), restored.getBlockPositions());
        assertFalse(restored.contains(new Vec3(-7.5, -62.5, 30.5)));
        assertTrue(restored.contains(new Vec3(-7.5, -61.5, 30.5)));
        assertEquals(2, restored.getCachedApertureFaces(Face.E).size());
        assertFalse(restored.isFullCuboid());
    }

    @Test
    void fullCuboidUsesOneApertureFaceAndChangingCellsInvalidatesCache() {
        ApertureCells geometry = new ApertureCells();
        geometry.setBlocks(List.of(new Vec3(0, 0, 0), new Vec3(0, 1, 0)));
        long revision = geometry.getRevision();
        assertTrue(geometry.isFullCuboid());
        assertEquals(1, geometry.getCachedApertureFaces(Face.N).size());
        geometry.setBlocks(List.of(new Vec3(0, 0, 0), new Vec3(0, 2, 0)));
        assertTrue(geometry.getRevision() > revision);
        assertEquals(2, geometry.getCachedApertureFaces(Face.N).size());
    }

    @Test
    void portalIdentityPreservesRolledFrameAndCanonicalStoragePath() {
        UUID id = UUID.fromString("12345678-1234-4234-8234-123456789012");
        Portal.State state = new Portal.State(id, new Vec3(1.5, 64.5, -4.5), "Rolled",
            Frame.fromNormalUp(Face.U, Face.E), true);
        Map<String, Object> encoded = new LinkedHashMap<>();
        PortalStateCodec.write(encoded, state);
        Portal.State restored = PortalStateCodec.read(encoded);
        assertEquals(state.id(), restored.id());
        assertEquals(state.origin(), restored.origin());
        assertEquals(Face.U, restored.frame().getNormal());
        assertEquals(Face.E, restored.frame().getUp());
        assertEquals(Path.of("portals", "1234", "12345678", id + ".json"), PortalStateCodec.file(Path.of("portals"), id));
    }

    @Test
    void crossingRequiresPassingThroughThePlane() {
        Frame frame = Frame.canonical(Face.N);
        Vec3 origin = new Vec3(0, 0, 0);
        assertNull(PlaneCrossing.intersection(frame, origin, new Vec3(1, 1, 2), new Vec3(3, 1, 2)));
        assertNull(PlaneCrossing.intersection(frame, origin, new Vec3(1, 1, 2), new Vec3(3, 1, 1)));
        assertEquals(new Vec3(2, 1, 0), PlaneCrossing.intersection(frame, origin,
            new Vec3(1, 1, -2), new Vec3(3, 1, 2)));
    }

    @Test
    void crossingMapsBacksideEntryAndVelocityThroughDestinationFrame() {
        Frame source = Frame.canonical(Face.N);
        Vec3 origin = new Vec3(0, 0, 0);
        PlaneCrossing crossing = PlaneCrossing.create(source, origin,
            new PlaneCrossing.Motion(new Vec3(0, 0, 1), new Vec3(0, 0, -1),
                new Vec3(0, 0, -2), new Vec3(0, 0, -1)));
        assertFalse(crossing.frontSide());
        assertEquals(new Vec3(0, 0, -2), crossing.outVelocity(source));
        assertNotNull(crossing.outPoint(Frame.canonical(Face.U), new Vec3(10, 20, 30)));
    }
}
