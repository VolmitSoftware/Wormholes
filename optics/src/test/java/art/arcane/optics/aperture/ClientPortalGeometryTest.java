package art.arcane.optics.aperture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import art.arcane.optics.math.Vec3;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.claim.ProjectedBlockClaim;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;
import org.junit.jupiter.api.Test;

final class ClientPortalGeometryTest {
    @Test
    void everyFrameAndApertureSurvivesTheRoundTrip() {
        for (Face normal : Face.values()) {
            Frame frame = Frame.canonical(normal);
            for (int turn = 0; turn < 4; turn++) {
                ApertureCells aperture = flatAperture(normal, 3, 5);
                ApertureDescriptor geometry = ApertureDescriptor.fromPortal(source(aperture, frame, false, 0)).orElseThrow();
                assertTrue(geometry.valid());
                assertSame(normal, geometry.facingDirection());
                assertEquals(turn, geometry.frameQuarterTurns());
                assertSame(frame.getNormal(), geometry.frame().getNormal());
                assertSame(frame.getRight(), geometry.frame().getRight());
                assertSame(frame.getUp(), geometry.frame().getUp());
                assertEquals(15, geometry.openCellCount());
                assertAperturesMatch(aperture, geometry.aperture());
                assertCellMembership(aperture, geometry);
                frame = frame.rotateClockwise();
            }
        }
    }

    @Test
    void irregularAperturesKeepTheirHoles() {
        List<Vec3> cells = new ArrayList<Vec3>();
        for (int y = 64; y <= 67; y++) {
            for (int z = -2; z <= 2; z++) {
                if ((y == 67 && Math.abs(z) == 2) || (y == 65 && z == 0)) {
                    continue;
                }
                cells.add(new Vec3(10, y, z));
            }
        }
        ApertureCells aperture = new ApertureCells();
        aperture.setBlocks(cells);
        ApertureDescriptor geometry = ApertureDescriptor.fromPortal(source(aperture, Frame.canonical(Face.W), false, 0))
            .orElseThrow();
        assertEquals(cells.size(), geometry.openCellCount());
        ApertureCells rebuilt = geometry.aperture();
        assertFalse(rebuilt.isFullCuboid());
        assertAperturesMatch(aperture, rebuilt);
        assertCellMembership(aperture, geometry);
        assertEquals(10, geometry.originX());
        assertEquals(64, geometry.originY());
        assertEquals(-2, geometry.originZ());
    }

    @Test
    void mirrorRotationPacksBesideTheFrameRotation() {
        Frame frame = Frame.canonical(Face.U).rotateClockwise().rotateClockwise();
        ApertureDescriptor geometry = ApertureDescriptor.fromPortal(source(flatAperture(Face.U, 5, 5), frame, true, 3)).orElseThrow();
        assertTrue(geometry.mirror());
        assertEquals(2, geometry.frameQuarterTurns());
        assertEquals(3, geometry.mirrorQuarterTurns());
        ApertureDescriptor linked = ApertureDescriptor.fromPortal(source(flatAperture(Face.U, 5, 5), frame, false, 3)).orElseThrow();
        assertEquals(0, linked.mirrorQuarterTurns());
    }

    @Test
    void aperturesTheClientCannotRepresentStayVanilla() {
        ApertureCells thick = new ApertureCells();
        thick.setArea(new Box(0.0D, 1.999D, 64.0D, 66.999D, 0.0D, 2.999D));
        assertEquals(Optional.empty(), ApertureDescriptor.fromPortal(source(thick, Frame.canonical(Face.E), false, 0)));
        ApertureCells flat = flatAperture(Face.E, 3, 3);
        assertEquals(Optional.empty(), ApertureDescriptor.fromPortal(source(flat, Frame.canonical(Face.E), true, 4)));
    }

    @Test
    void valueSemanticsCoverTheMask() {
        ApertureDescriptor first = ApertureDescriptor.fromPortal(source(flatAperture(Face.N, 4, 4),
            Frame.canonical(Face.N), false, 0)).orElseThrow();
        ApertureDescriptor second = ApertureDescriptor.fromPortal(source(flatAperture(Face.N, 4, 4),
            Frame.canonical(Face.N), false, 0)).orElseThrow();
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        long[] mask = first.apertureMask();
        assertNotSame(mask, first.apertureMask());
        mask[0] = 0L;
        assertEquals(first, second);
        assertArrayEquals(second.apertureMask(), first.apertureMask());
        ApertureDescriptor holed = new ApertureDescriptor(first.originX(), first.originY(), first.originZ(), first.facing(),
            first.frontSide(), first.quarterTurns(), first.mirror(), first.apertureWidth(), first.apertureHeight(),
            new long[] {0x7FFEL}, first.nearPlanePadding(), first.aperturePadding(), first.frustumCullingRatio(), first.depthBlocks(),
            first.recursionDepth(), first.blackoutPolicy(), first.blackoutState(), first.maskAirPolicy(), first.lightingPolicy(),
            first.fidelityFlags(), first.kind(), first.planeOffset(), first.parentPortalKey(), first.targetIdentity(), first.nested());
        assertNotEquals(first, holed);
        assertFalse(holed.apertureOpen(0, 0));
        assertTrue(holed.apertureOpen(1, 0));
        assertFalse(holed.apertureOpen(3, 3));
        assertEquals(14, holed.openCellCount());
    }

    @Test
    void surfaceEqualityExcludesOnlyDescendantsAndKeepsDestinationAndApertureIdentity() {
        ApertureDescriptor base = ApertureDescriptor.fromPortal(source(flatAperture(Face.N, 4, 4),
            Frame.canonical(Face.N), true, 0)).orElseThrow();
        ApertureDescriptor child = base.withParent(7);
        ApertureDescriptor nested = base.withNested(List.of(child));
        assertNotEquals(base, nested);
        assertTrue(base.sameSurface(nested));
        assertTrue(nested.sameSurface(base));
        assertFalse(base.sameSurface(null));
        assertFalse(base.sameSurface(base.withDepth(32)));
        assertFalse(base.sameSurface(base.withParent(9)));
        assertFalse(base.sameSurface(copy(base, Face.S.ordinal(), base.apertureMask(), 0)));
        assertFalse(base.sameSurface(copy(base, base.facing(), new long[]{0x7FFEL}, 0)));
        ApertureDescriptor rotated = ApertureDescriptor.fromPortal(source(flatAperture(Face.N, 4, 4),
            Frame.canonical(Face.N), true, 1)).orElseThrow();
        assertFalse(base.sameSurface(rotated));
        ApertureDescriptor retargeted = new ApertureDescriptor(base.originX(), base.originY(), base.originZ(), base.facing(),
            base.frontSide(), base.quarterTurns(), base.mirror(), base.apertureWidth(), base.apertureHeight(), base.apertureMask(),
            base.nearPlanePadding(), base.aperturePadding(), base.frustumCullingRatio(), base.depthBlocks(), base.recursionDepth(),
            base.blackoutPolicy(), base.blackoutState(), base.maskAirPolicy(), base.lightingPolicy(), base.fidelityFlags(),
            base.kind(), base.planeOffset(), base.parentPortalKey(), 123L, base.nested());
        assertFalse(base.sameSurface(retargeted));
    }

    @Test
    void validityGuardsEveryDecodedField() {
        ApertureDescriptor valid = ApertureDescriptor.fromPortal(source(flatAperture(Face.S, 2, 2),
            Frame.canonical(Face.S), false, 0)).orElseThrow();
        assertTrue(valid.valid());
        assertFalse(copy(valid, 6, valid.apertureMask(), 0).valid());
        assertFalse(copy(valid, valid.facing(), new long[] {0L}, 0).valid());
        assertFalse(copy(valid, valid.facing(), new long[] {0xFL, 0L}, 0).valid());
        assertFalse(copy(valid, valid.facing(), valid.apertureMask(), 7).valid());
        assertEquals(ProjectedBlockClaim.LightingPolicy.SOURCE, valid.lightingPolicyType());
        assertTrue(valid.hasFidelity(ApertureDescriptor.FIDELITY_LIGHTING));
        assertFalse(valid.hasFidelity(ApertureDescriptor.FIDELITY_SOUNDS));
    }

    @Test
    void clientViewModeParsesAndCycles() {
        assertTrue(ClientViewMode.AUTO.allowsClientView());
        assertFalse(ClientViewMode.OFF.allowsClientView());
        assertSame(ClientViewMode.OFF, ClientViewMode.AUTO.next());
        assertSame(ClientViewMode.AUTO, ClientViewMode.OFF.next());
        assertSame(ClientViewMode.OFF, ClientViewMode.fromName(" off ", ClientViewMode.AUTO));
        assertSame(ClientViewMode.AUTO, ClientViewMode.fromName("unknown", ClientViewMode.AUTO));
        assertSame(ClientViewMode.OFF, ClientViewMode.fromName(null, ClientViewMode.OFF));
    }

    private static void assertCellMembership(CellAperture expected, ApertureDescriptor actual) {
        Box area = expected.getArea();
        for (int x = (int) Math.floor(area.getXa()) - 1; x <= (int) Math.floor(area.getXb()) + 1; x++) {
            for (int y = (int) Math.floor(area.getYa()) - 1; y <= (int) Math.floor(area.getYb()) + 1; y++) {
                for (int z = (int) Math.floor(area.getZa()) - 1; z <= (int) Math.floor(area.getZb()) + 1; z++) {
                    assertEquals(expected.containsBlock(x, y, z), actual.containsCell(x, y, z), x + "," + y + "," + z);
                }
            }
        }
    }

    private static void assertAperturesMatch(CellAperture expected, ApertureCells actual) {
        Box area = expected.getArea();
        Box rebuilt = actual.getArea();
        assertEquals(area.getXa(), rebuilt.getXa(), 1.0E-9D);
        assertEquals(area.getXb(), rebuilt.getXb(), 1.0E-9D);
        assertEquals(area.getYa(), rebuilt.getYa(), 1.0E-9D);
        assertEquals(area.getYb(), rebuilt.getYb(), 1.0E-9D);
        assertEquals(area.getZa(), rebuilt.getZa(), 1.0E-9D);
        assertEquals(area.getZb(), rebuilt.getZb(), 1.0E-9D);
        assertEquals(expected.isFullCuboid(), actual.isFullCuboid());
        for (int x = (int) Math.floor(area.getXa()) - 1; x <= (int) Math.floor(area.getXb()) + 1; x++) {
            for (int y = (int) Math.floor(area.getYa()) - 1; y <= (int) Math.floor(area.getYb()) + 1; y++) {
                for (int z = (int) Math.floor(area.getZa()) - 1; z <= (int) Math.floor(area.getZb()) + 1; z++) {
                    assertEquals(expected.containsBlock(x, y, z), actual.containsBlock(x, y, z), x + "," + y + "," + z);
                }
            }
        }
    }

    private static ApertureCells flatAperture(Face normal, int width, int height) {
        Frame frame = Frame.canonical(normal);
        int[] min = {7, 64, -3};
        int[] max = {7, 64, -3};
        max[ApertureDescriptor.axisOf(frame.getRight())] += width - 1;
        max[ApertureDescriptor.axisOf(frame.getUp())] += height - 1;
        ApertureCells aperture = new ApertureCells();
        aperture.setArea(new Box(min[0], max[0] + 0.999D, min[1], max[1] + 0.999D, min[2], max[2] + 0.999D));
        return aperture;
    }

    private static ApertureDescriptor.Source source(CellAperture aperture, Frame frame, boolean mirror, int mirrorTurns) {
        return new ApertureDescriptor.Source(aperture, frame, true, mirror, mirrorTurns, 2.0D, 0.75D, 0.2D, 64, 3,
            ApertureDescriptor.BLACKOUT_SHELL, 7, ApertureDescriptor.MASK_AIR_PROJECT, ProjectedBlockClaim.LightingPolicy.SOURCE,
            ApertureDescriptor.FIDELITY_LIGHTING | ApertureDescriptor.FIDELITY_WEATHER, ApertureDescriptor.KIND_FRAME, 0.0D, 0, 0L,
            List.of());
    }

    private static ApertureDescriptor copy(ApertureDescriptor base, int facing, long[] mask, int kindOffset) {
        return new ApertureDescriptor(base.originX(), base.originY(), base.originZ(), facing, base.frontSide(), base.quarterTurns(),
            base.mirror(), base.apertureWidth(), base.apertureHeight(), mask, base.nearPlanePadding(), base.aperturePadding(),
            base.frustumCullingRatio(), base.depthBlocks(), base.recursionDepth(), base.blackoutPolicy(), base.blackoutState(),
            base.maskAirPolicy(), base.lightingPolicy(), base.fidelityFlags(), base.kind() + kindOffset, base.planeOffset(), base.parentPortalKey(),
            base.targetIdentity(), base.nested());
    }
}
