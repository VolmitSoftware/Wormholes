package art.arcane.wormholes.portal;

import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.util.AxisAlignedBB;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PortalGeometryCellCentreTest {
    @Test
    void randomCellCentreLandsOnTheCentreOfAnApertureCell() {
        PortalGeometry geometry = new PortalGeometry();
        geometry.setBlocks(List.of(new GeometryVector(-8, -64, 30), new GeometryVector(-8, -62, 30), new GeometryVector(-7, -62, 30)));
        Set<GeometryVector> expected = Set.of(new GeometryVector(-7.5D, -63.5D, 30.5D), new GeometryVector(-7.5D, -61.5D, 30.5D),
            new GeometryVector(-6.5D, -61.5D, 30.5D));
        Set<GeometryVector> seen = new HashSet<>();

        for (int draw = 0; draw < 400; draw++) {
            GeometryVector centre = geometry.randomCellCentre();
            assertTrue(expected.contains(centre), "centre=" + centre);
            seen.add(centre);
        }

        assertEquals(expected, seen);
    }

    @Test
    void cellFreeGeometryPicksACellCentreInsideItsArea() {
        PortalGeometry geometry = new PortalGeometry();
        geometry.restore(new AxisAlignedBB(2.0D, 3.999D, 64.0D, 64.999D, 5.0D, 5.999D), List.of());

        for (int draw = 0; draw < 50; draw++) {
            GeometryVector centre = geometry.randomCellCentre();
            assertTrue(centre.x() == 2.5D || centre.x() == 3.5D, "x=" + centre.x());
            assertEquals(64.5D, centre.y());
            assertEquals(5.5D, centre.z());
        }
    }

    @Test
    void emptyGeometryHasNoCellCentre() {
        assertNull(new PortalGeometry().randomCellCentre());
    }
}
