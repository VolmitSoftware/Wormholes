package art.arcane.wormholes.portal.vanilla;

import art.arcane.optics.aperture.ApertureCells;
import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.shape.ShapeDescriptor;
import art.arcane.wormholes.portal.ApertureKind;
import art.arcane.wormholes.portal.DimensionalPortalKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EndPortalContactTest {
    private static final int FLOOR = 64;
    private static final double HALF_WIDTH = 0.3D;
    private static final double HEIGHT = 1.8D;

    @Test
    void feetRestingOnTheFloorInsideAnOpenCellAreAdmitted() {
        EndPortalContact.Hit hit = contact(window(), player(11.5D, FLOOR, 11.5D));

        assertNotNull(hit);
        assertEquals(11, hit.cellX());
        assertEquals(FLOOR, hit.cellY());
        assertEquals(11, hit.cellZ());
        assertVec(new Vec3d(11.5D, FLOOR, 11.5D), hit.point());
    }

    @Test
    void feetAtOrAboveThePortalTopAreNotAdmitted() {
        assertNull(contact(window(), player(11.5D, FLOOR + EndPortalContact.PORTAL_HEIGHT, 11.5D)));
        assertNull(contact(window(), player(11.5D, FLOOR + 0.8125D, 11.5D)));
        assertNull(contact(window(), player(11.5D, FLOOR + 2.0D, 11.5D)));
        assertNotNull(contact(window(), player(11.5D, FLOOR + EndPortalContact.PORTAL_HEIGHT - 1.0E-4D, 11.5D)));
    }

    @Test
    void aBoxThatOnlyTouchesTheCellBoxIsNotAdmitted() {
        assertNull(contact(window(), new Box(13.0D, 13.6D, FLOOR, FLOOR + HEIGHT, 11.2D, 11.8D)));
        assertNull(contact(window(), new Box(9.4D, 10.0D, FLOOR, FLOOR + HEIGHT, 11.2D, 11.8D)));
        assertNull(contact(window(), new Box(11.2D, 11.8D, FLOOR, FLOOR + HEIGHT, 13.0D, 13.6D)));
        assertNull(contact(window(), new Box(11.2D, 11.8D, FLOOR - HEIGHT, FLOOR, 11.2D, 11.8D)));
    }

    @Test
    void aBoxOutsideTheCellFootprintIsNotAdmitted() {
        assertNull(contact(window(), player(15.5D, FLOOR, 11.5D)));
        assertNull(contact(window(), player(11.5D, FLOOR, 7.5D)));
        assertNull(contact(window(), player(9.5D, FLOOR + 0.8125D, 11.5D)));
    }

    @Test
    void aBoxBelowTheCellStillTouchingThePortalIsAdmittedOnTheFloorOfTheCell() {
        EndPortalContact.Hit hit = contact(window(), player(11.5D, FLOOR - 1.5D, 11.5D));

        assertNotNull(hit);
        assertEquals(FLOOR - 1.5D, hit.point().y(), 1.0E-9D);
        assertEquals(FLOOR - 1.5D, hit.exit().y(), 1.0E-9D);
    }

    @Test
    void anEdgeOverlapIsAdmittedAndTheContactPointIsClampedIntoTheTouchedCell() {
        EndPortalContact.Hit hit = contact(window(), player(9.8D, FLOOR, 11.5D));

        assertNotNull(hit);
        assertEquals(10, hit.cellX());
        assertEquals(11, hit.cellZ());
        assertEquals(10.0D, hit.point().x(), 1.0E-9D);
        assertEquals(11.5D, hit.point().z(), 1.0E-9D);
    }

    @Test
    void aStraddlingTravellerReportsTheOpenCellNearestItsCentre() {
        EndPortalContact.Hit hit = contact(window(), player(11.1D, FLOOR, 12.95D));

        assertNotNull(hit);
        assertEquals(11, hit.cellX());
        assertEquals(12, hit.cellZ());
        assertEquals(11.1D, hit.point().x(), 1.0E-9D);
        assertEquals(12.95D, hit.point().z(), 1.0E-9D);
    }

    @Test
    void closedCellsOfAShapedApertureAreNotAdmitted() {
        ApertureCells centreOnly = new ApertureCells();
        centreOnly.restore(window().getArea(), List.of(new Vec3d(11, FLOOR, 11)));

        assertNull(contact(centreOnly, player(10.5D, FLOOR, 10.5D)));
        assertNull(contact(centreOnly, player(12.5D, FLOOR, 11.5D)));
        EndPortalContact.Hit hit = contact(centreOnly, player(10.75D, FLOOR, 11.5D));
        assertNotNull(hit);
        assertEquals(11, hit.cellX());
        assertEquals(11.0D, hit.point().x(), 1.0E-9D);
    }

    @Test
    void entryAndExitStraddleTheCrossingPlaneOnTheEntrySideAboveAndTheExitSideBelow() {
        double plane = FLOOR + 0.5D;
        for (double feet : new double[] {FLOOR, FLOOR + 0.25D, FLOOR + 0.5D, FLOOR + 0.7D}) {
            EndPortalContact.Hit hit = contact(window(), player(11.5D, feet, 11.5D));
            assertNotNull(hit);
            assertTrue(hit.entry().y() > plane, "entry above the plane for feet " + feet);
            assertTrue(hit.exit().y() < plane, "exit below the plane for feet " + feet);
            assertEquals(Math.min(feet, plane), hit.exit().y(), 1.0E-2D);
            assertEquals(hit.point().x(), hit.exit().x(), 0.0D);
            assertEquals(hit.point().z(), hit.exit().z(), 0.0D);
            assertEquals(hit.point().x(), hit.entry().x(), 0.0D);
            assertEquals(hit.point().z(), hit.entry().z(), 0.0D);
        }
    }

    @Test
    void wallAperturesAndOtherPortalKindsNeverUseTheContactRule() {
        ApertureCells wall = new ApertureCells();
        wall.setBlocks(List.of(new Vec3d(11, FLOOR, 11), new Vec3d(11, FLOOR + 1, 11), new Vec3d(11, FLOOR, 12), new Vec3d(11, FLOOR + 1, 12)));
        for (Face normal : new Face[] {Face.N, Face.S, Face.E, Face.W}) {
            assertFalse(EndPortalContact.applies(DimensionalPortalKind.END_SOURCE, normal));
            assertNull(EndPortalContact.contact(DimensionalPortalKind.END_SOURCE, normal, wall, player(11.5D, FLOOR, 11.5D)));
        }
        for (DimensionalPortalKind kind : DimensionalPortalKind.values()) {
            if (kind == DimensionalPortalKind.END_SOURCE) {
                continue;
            }
            for (Face normal : new Face[] {Face.U, Face.D}) {
                assertFalse(EndPortalContact.applies(kind, normal));
                assertNull(EndPortalContact.contact(kind, normal, window(), player(11.5D, FLOOR, 11.5D)));
            }
        }
        assertTrue(EndPortalContact.applies(DimensionalPortalKind.END_SOURCE, Face.U));
        assertTrue(EndPortalContact.applies(DimensionalPortalKind.END_SOURCE, Face.D));
    }

    @Test
    void onlyFloorAndCeilingVanillaReplacementAperturesAreCrossedOnContact() {
        assertTrue(EndPortalContact.contactAperture(descriptor(Face.D, ApertureKind.VANILLA_REPLACEMENT)));
        assertTrue(EndPortalContact.contactAperture(descriptor(Face.U, ApertureKind.VANILLA_REPLACEMENT)));
        for (Face wall : new Face[] {Face.N, Face.S, Face.E, Face.W}) {
            assertFalse(EndPortalContact.contactAperture(descriptor(wall, ApertureKind.VANILLA_REPLACEMENT)));
        }
        for (int kind : new int[] {ApertureKind.FRAME, ApertureKind.RTP, ApertureKind.DOOR}) {
            for (Face normal : new Face[] {Face.U, Face.D, Face.N}) {
                assertFalse(EndPortalContact.contactAperture(descriptor(normal, kind)));
            }
        }
        assertFalse(EndPortalContact.contactAperture(null));
    }

    @Test
    void onlyArmsIntoTheEndUseTheContactRule() {
        assertTrue(EndPortalContact.contactArm(descriptor(Face.D, ApertureKind.VANILLA_REPLACEMENT), EndPortalContact.END));
        assertTrue(EndPortalContact.contactArm(descriptor(Face.U, ApertureKind.VANILLA_REPLACEMENT), EndPortalContact.END));
        assertFalse(EndPortalContact.contactArm(descriptor(Face.D, ApertureKind.VANILLA_REPLACEMENT), "minecraft:overworld"));
        assertFalse(EndPortalContact.contactArm(descriptor(Face.D, ApertureKind.FRAME), EndPortalContact.END));
        assertFalse(EndPortalContact.contactArm(descriptor(Face.N, ApertureKind.VANILLA_REPLACEMENT), EndPortalContact.END));
        assertFalse(EndPortalContact.contactArm(null, EndPortalContact.END));
    }

    @Test
    void anArmIntoTheEndIsCrossedWhenTheTravellerTouchesThePortalFromAbove() {
        for (ApertureDescriptor arm : new ApertureDescriptor[] {descriptor(Face.D, false, ApertureKind.VANILLA_REPLACEMENT),
            descriptor(Face.U, true, ApertureKind.VANILLA_REPLACEMENT)}) {
            EndPortalContact.Hit hit = EndPortalContact.contact(arm, EndPortalContact.END, player(11.5D, FLOOR, 11.5D));

            assertNotNull(hit);
            assertEquals(11, hit.cellX());
            assertEquals(FLOOR, hit.cellY());
            assertEquals(11, hit.cellZ());
            assertEquals(FLOOR, hit.exit().y(), 1.0E-9D);
            assertEquals(FLOOR + 0.5D - 1.0E-3D, EndPortalContact.contact(arm, EndPortalContact.END, player(11.5D, FLOOR + 0.7D, 11.5D)).exit().y(),
                1.0E-9D);
            assertNull(EndPortalContact.contact(arm, EndPortalContact.END, player(11.5D, FLOOR + 0.8125D, 11.5D)));
            assertNull(EndPortalContact.contact(arm, EndPortalContact.END, player(15.5D, FLOOR, 11.5D)));
        }
    }

    @Test
    void theEndExitAndArmsArmedFromBelowAreNotCrossedOnContact() {
        Box standing = player(11.5D, FLOOR, 11.5D);

        assertNull(EndPortalContact.contact(descriptor(Face.D, false, ApertureKind.VANILLA_REPLACEMENT), "minecraft:overworld", standing));
        assertNull(EndPortalContact.contact(descriptor(Face.D, true, ApertureKind.VANILLA_REPLACEMENT), EndPortalContact.END, standing));
        assertNull(EndPortalContact.contact(descriptor(Face.U, false, ApertureKind.VANILLA_REPLACEMENT), EndPortalContact.END, standing));
        assertNull(EndPortalContact.contact(descriptor(Face.D, false, ApertureKind.FRAME), EndPortalContact.END, standing));
        assertNull(EndPortalContact.contact(descriptor(Face.D, false, ApertureKind.VANILLA_REPLACEMENT), EndPortalContact.END, null));
    }

    @Test
    void aTravellerLandingAtTheEndPortalStandsOnTheNearestFrameRowAboveTheContactBand() {
        Vec3d offset = EndPortalContact.landing(window().getArea(), player(10.4D, FLOOR - 1.12D, 11.5D), box -> false);

        assertNotNull(offset);
        Box landed = shifted(player(10.4D, FLOOR - 1.12D, 11.5D), offset);
        assertEquals(9.5D, (landed.getXa() + landed.getXb()) * 0.5D, 1.0E-9D);
        assertEquals(11.5D, (landed.getZa() + landed.getZb()) * 0.5D, 1.0E-9D);
        assertEquals(FLOOR + 1.0D, landed.getYa(), 1.0E-9D);
        assertNull(contact(window(), landed));
    }

    @Test
    void theLandingKeepsTheTravellerOverItsFrameRowAlongTheOtherAxis() {
        Box landed = shifted(player(12.95D, FLOOR + 0.6D, 12.85D), EndPortalContact.landing(window().getArea(), player(12.95D, FLOOR + 0.6D, 12.85D),
            box -> false));

        assertEquals(13.5D, (landed.getXa() + landed.getXb()) * 0.5D, 1.0E-9D);
        assertEquals(12.7D, (landed.getZa() + landed.getZb()) * 0.5D, 1.0E-9D);
        assertTrue(landed.getXa() >= 13.0D);
        assertNull(contact(window(), landed));
    }

    @Test
    void anObstructedFrameRowFallsBackToTheNextNearestRow() {
        Box traveller = player(10.4D, FLOOR - 1.12D, 11.5D);

        Vec3d offset = EndPortalContact.landing(window().getArea(), traveller, box -> box.getXb() <= 10.0D);

        assertNotNull(offset);
        Box landed = shifted(traveller, offset);
        assertEquals(10.4D, (landed.getXa() + landed.getXb()) * 0.5D, 1.0E-9D);
        assertEquals(9.5D, (landed.getZa() + landed.getZb()) * 0.5D, 1.0E-9D);
        assertNull(EndPortalContact.landing(window().getArea(), traveller, box -> true));
    }

    @Test
    void theLandingMatchesForCellAreasAndMappedApertureAreas() {
        Box traveller = player(11.2D, FLOOR - 1.12D, 10.3D);
        Box cells = window().getArea();
        Box aperture = descriptor(Face.U, ApertureKind.VANILLA_REPLACEMENT).apertureArea();

        Vec3d fromCells = EndPortalContact.landing(cells, traveller, box -> false);
        Vec3d fromAperture = EndPortalContact.landing(aperture, traveller, box -> false);

        assertVec(fromCells, fromAperture);
        assertEquals(9.5D, shifted(traveller, fromCells).getZa() + HALF_WIDTH, 1.0E-9D);
    }

    @Test
    void theEndArrivalIsOnlyEnteredWhileRisingThroughItsPlane() {
        for (Face normal : new Face[] {Face.U, Face.D}) {
            boolean below = normal == Face.D;
            assertTrue(EndPortalContact.admitsCrossing(DimensionalPortalKind.END_ARRIVAL, normal, below));
            assertFalse(EndPortalContact.admitsCrossing(DimensionalPortalKind.END_ARRIVAL, normal, !below));
            assertEquals(below, EndPortalContact.underside(normal));
            for (DimensionalPortalKind kind : DimensionalPortalKind.values()) {
                if (kind != DimensionalPortalKind.END_ARRIVAL) {
                    assertTrue(EndPortalContact.admitsCrossing(kind, normal, true));
                    assertTrue(EndPortalContact.admitsCrossing(kind, normal, false));
                }
            }
        }
        assertTrue(EndPortalContact.admitsCrossing(DimensionalPortalKind.END_ARRIVAL, Face.N, true));
        assertTrue(EndPortalContact.admitsCrossing(DimensionalPortalKind.END_ARRIVAL, Face.N, false));
    }

    @Test
    void onlyArmsOutOfTheEndThroughAHorizontalVanillaApertureAreEndReturns() {
        assertTrue(EndPortalContact.returnArm(descriptor(Face.D, ApertureKind.VANILLA_REPLACEMENT), "minecraft:overworld"));
        assertTrue(EndPortalContact.returnArm(descriptor(Face.U, ApertureKind.VANILLA_REPLACEMENT), "minecraft:overworld"));
        assertFalse(EndPortalContact.returnArm(descriptor(Face.D, ApertureKind.VANILLA_REPLACEMENT), EndPortalContact.END));
        assertFalse(EndPortalContact.returnArm(descriptor(Face.D, ApertureKind.FRAME), "minecraft:overworld"));
        assertFalse(EndPortalContact.returnArm(descriptor(Face.N, ApertureKind.VANILLA_REPLACEMENT), "minecraft:overworld"));
        assertFalse(EndPortalContact.returnArm(null, "minecraft:overworld"));
    }

    private static ApertureDescriptor descriptor(Face facing, int kind) {
        return descriptor(facing, false, kind);
    }

    private static ApertureDescriptor descriptor(Face facing, boolean front, int kind) {
        return new ApertureDescriptor(10, FLOOR, 10, facing.ordinal(), front, 0, false, 3, 3, new long[] {511L}, ShapeDescriptor.FULL,
            0.0F, 0.0F, 1.0F, 64, 0, 0, 0, 0, 0, 0, kind, 0.0D, 0, 0L, List.of());
    }

    private static EndPortalContact.Hit contact(ApertureCells cells, Box traveller) {
        return EndPortalContact.contact(DimensionalPortalKind.END_SOURCE, Face.U, cells, traveller);
    }

    private static ApertureCells window() {
        List<Vec3d> cells = new ArrayList<Vec3d>(9);
        for (int x = 10; x <= 12; x++) {
            for (int z = 10; z <= 12; z++) {
                cells.add(new Vec3d(x, FLOOR, z));
            }
        }
        ApertureCells window = new ApertureCells();
        window.setBlocks(cells);
        return window;
    }

    private static Box player(double x, double feet, double z) {
        return new Box(x - HALF_WIDTH, x + HALF_WIDTH, feet, feet + HEIGHT, z - HALF_WIDTH, z + HALF_WIDTH);
    }

    private static Box shifted(Box box, Vec3d offset) {
        return new Box(box.getXa() + offset.x(), box.getXb() + offset.x(), box.getYa() + offset.y(), box.getYb() + offset.y(),
            box.getZa() + offset.z(), box.getZb() + offset.z());
    }

    private static void assertVec(Vec3d expected, Vec3d actual) {
        assertEquals(expected.x(), actual.x(), 1.0E-9D);
        assertEquals(expected.y(), actual.y(), 1.0E-9D);
        assertEquals(expected.z(), actual.z(), 1.0E-9D);
    }
}
