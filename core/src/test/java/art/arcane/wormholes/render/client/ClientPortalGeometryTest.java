package art.arcane.wormholes.render.client;

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

import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.portal.PortalCellAperture;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalGeometry;
import art.arcane.wormholes.render.ProjectedBlockClaim;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;
import org.junit.jupiter.api.Test;

final class ClientPortalGeometryTest {
    @Test
    void everyFrameAndApertureSurvivesTheRoundTrip() {
        for (Direction normal : Direction.values()) {
            PortalFrame frame = PortalFrame.canonical(normal);
            for (int turn = 0; turn < 4; turn++) {
                PortalGeometry aperture = flatAperture(normal, 3, 5);
                ClientPortalGeometry geometry = ClientPortalGeometry.fromPortal(source(aperture, frame, false, 0)).orElseThrow();
                assertTrue(geometry.valid());
                assertSame(normal, geometry.facingDirection());
                assertEquals(turn, geometry.frameQuarterTurns());
                assertSame(frame.getNormal(), geometry.frame().getNormal());
                assertSame(frame.getRight(), geometry.frame().getRight());
                assertSame(frame.getUp(), geometry.frame().getUp());
                assertEquals(15, geometry.openCellCount());
                assertAperturesMatch(aperture, geometry.aperture());
                frame = frame.rotateClockwise();
            }
        }
    }

    @Test
    void irregularAperturesKeepTheirHoles() {
        List<GeometryVector> cells = new ArrayList<GeometryVector>();
        for (int y = 64; y <= 67; y++) {
            for (int z = -2; z <= 2; z++) {
                if ((y == 67 && Math.abs(z) == 2) || (y == 65 && z == 0)) {
                    continue;
                }
                cells.add(new GeometryVector(10, y, z));
            }
        }
        PortalGeometry aperture = new PortalGeometry();
        aperture.setBlocks(cells);
        ClientPortalGeometry geometry = ClientPortalGeometry.fromPortal(source(aperture, PortalFrame.canonical(Direction.W), false, 0))
            .orElseThrow();
        assertEquals(cells.size(), geometry.openCellCount());
        PortalGeometry rebuilt = geometry.aperture();
        assertFalse(rebuilt.isFullCuboid());
        assertAperturesMatch(aperture, rebuilt);
        assertEquals(10, geometry.originX());
        assertEquals(64, geometry.originY());
        assertEquals(-2, geometry.originZ());
    }

    @Test
    void mirrorRotationPacksBesideTheFrameRotation() {
        PortalFrame frame = PortalFrame.canonical(Direction.U).rotateClockwise().rotateClockwise();
        ClientPortalGeometry geometry = ClientPortalGeometry.fromPortal(source(flatAperture(Direction.U, 5, 5), frame, true, 3)).orElseThrow();
        assertTrue(geometry.mirror());
        assertEquals(2, geometry.frameQuarterTurns());
        assertEquals(3, geometry.mirrorQuarterTurns());
        ClientPortalGeometry linked = ClientPortalGeometry.fromPortal(source(flatAperture(Direction.U, 5, 5), frame, false, 3)).orElseThrow();
        assertEquals(0, linked.mirrorQuarterTurns());
    }

    @Test
    void aperturesTheClientCannotRepresentStayVanilla() {
        PortalGeometry thick = new PortalGeometry();
        thick.setArea(new AxisAlignedBB(0.0D, 1.999D, 64.0D, 66.999D, 0.0D, 2.999D));
        assertEquals(Optional.empty(), ClientPortalGeometry.fromPortal(source(thick, PortalFrame.canonical(Direction.E), false, 0)));
        PortalGeometry flat = flatAperture(Direction.E, 3, 3);
        assertEquals(Optional.empty(), ClientPortalGeometry.fromPortal(source(flat, PortalFrame.canonical(Direction.E), true, 4)));
    }

    @Test
    void valueSemanticsCoverTheMask() {
        ClientPortalGeometry first = ClientPortalGeometry.fromPortal(source(flatAperture(Direction.N, 4, 4),
            PortalFrame.canonical(Direction.N), false, 0)).orElseThrow();
        ClientPortalGeometry second = ClientPortalGeometry.fromPortal(source(flatAperture(Direction.N, 4, 4),
            PortalFrame.canonical(Direction.N), false, 0)).orElseThrow();
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        long[] mask = first.apertureMask();
        assertNotSame(mask, first.apertureMask());
        mask[0] = 0L;
        assertEquals(first, second);
        assertArrayEquals(second.apertureMask(), first.apertureMask());
        ClientPortalGeometry holed = new ClientPortalGeometry(first.originX(), first.originY(), first.originZ(), first.facing(),
            first.frontSide(), first.quarterTurns(), first.mirror(), first.apertureWidth(), first.apertureHeight(),
            new long[] {0x7FFEL}, first.nearPlanePadding(), first.aperturePadding(), first.frustumCullingRatio(), first.depthBlocks(),
            first.recursionDepth(), first.blackoutPolicy(), first.blackoutState(), first.maskAirPolicy(), first.lightingPolicy(),
            first.fidelityFlags(), first.kind(), first.parentPortalKey(), first.targetIdentity(), first.nested());
        assertNotEquals(first, holed);
        assertFalse(holed.apertureOpen(0, 0));
        assertTrue(holed.apertureOpen(1, 0));
        assertFalse(holed.apertureOpen(3, 3));
        assertEquals(14, holed.openCellCount());
    }

    @Test
    void validityGuardsEveryDecodedField() {
        ClientPortalGeometry valid = ClientPortalGeometry.fromPortal(source(flatAperture(Direction.S, 2, 2),
            PortalFrame.canonical(Direction.S), false, 0)).orElseThrow();
        assertTrue(valid.valid());
        assertFalse(copy(valid, 6, valid.apertureMask(), 0).valid());
        assertFalse(copy(valid, valid.facing(), new long[] {0L}, 0).valid());
        assertFalse(copy(valid, valid.facing(), new long[] {0xFL, 0L}, 0).valid());
        assertFalse(copy(valid, valid.facing(), valid.apertureMask(), 7).valid());
        assertEquals(ProjectedBlockClaim.LightingPolicy.SOURCE, valid.lightingPolicyType());
        assertTrue(valid.hasFidelity(ClientPortalGeometry.FIDELITY_LIGHTING));
        assertFalse(valid.hasFidelity(ClientPortalGeometry.FIDELITY_SOUNDS));
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

    private static void assertAperturesMatch(PortalCellAperture expected, PortalGeometry actual) {
        AxisAlignedBB area = expected.getArea();
        AxisAlignedBB rebuilt = actual.getArea();
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

    private static PortalGeometry flatAperture(Direction normal, int width, int height) {
        PortalFrame frame = PortalFrame.canonical(normal);
        int[] min = {7, 64, -3};
        int[] max = {7, 64, -3};
        max[ClientPortalGeometry.axisOf(frame.getRight())] += width - 1;
        max[ClientPortalGeometry.axisOf(frame.getUp())] += height - 1;
        PortalGeometry aperture = new PortalGeometry();
        aperture.setArea(new AxisAlignedBB(min[0], max[0] + 0.999D, min[1], max[1] + 0.999D, min[2], max[2] + 0.999D));
        return aperture;
    }

    private static ClientPortalGeometry.Source source(PortalCellAperture aperture, PortalFrame frame, boolean mirror, int mirrorTurns) {
        return new ClientPortalGeometry.Source(aperture, frame, true, mirror, mirrorTurns, 2.0D, 0.75D, 0.2D, 64, 3,
            ClientPortalGeometry.BLACKOUT_SHELL, 7, ClientPortalGeometry.MASK_AIR_PROJECT, ProjectedBlockClaim.LightingPolicy.SOURCE,
            ClientPortalGeometry.FIDELITY_LIGHTING | ClientPortalGeometry.FIDELITY_WEATHER, ClientPortalGeometry.KIND_FRAME, 0, 0L,
            List.of());
    }

    private static ClientPortalGeometry copy(ClientPortalGeometry base, int facing, long[] mask, int kindOffset) {
        return new ClientPortalGeometry(base.originX(), base.originY(), base.originZ(), facing, base.frontSide(), base.quarterTurns(),
            base.mirror(), base.apertureWidth(), base.apertureHeight(), mask, base.nearPlanePadding(), base.aperturePadding(),
            base.frustumCullingRatio(), base.depthBlocks(), base.recursionDepth(), base.blackoutPolicy(), base.blackoutState(),
            base.maskAirPolicy(), base.lightingPolicy(), base.fidelityFlags(), base.kind() + kindOffset, base.parentPortalKey(),
            base.targetIdentity(), base.nested());
    }
}
