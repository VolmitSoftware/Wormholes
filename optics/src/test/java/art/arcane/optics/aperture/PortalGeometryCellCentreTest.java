package art.arcane.optics.aperture;

import art.arcane.optics.math.Vec3;
import art.arcane.optics.math.Box;
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
        ApertureCells geometry = new ApertureCells();
        geometry.setBlocks(List.of(new Vec3(-8, -64, 30), new Vec3(-8, -62, 30), new Vec3(-7, -62, 30)));
        Set<Vec3> expected = Set.of(new Vec3(-7.5D, -63.5D, 30.5D), new Vec3(-7.5D, -61.5D, 30.5D),
            new Vec3(-6.5D, -61.5D, 30.5D));
        Set<Vec3> seen = new HashSet<>();

        for (int draw = 0; draw < 400; draw++) {
            Vec3 centre = geometry.randomCellCentre();
            assertTrue(expected.contains(centre), "centre=" + centre);
            seen.add(centre);
        }

        assertEquals(expected, seen);
    }

    @Test
    void cellFreeGeometryPicksACellCentreInsideItsArea() {
        ApertureCells geometry = new ApertureCells();
        geometry.restore(new Box(2.0D, 3.999D, 64.0D, 64.999D, 5.0D, 5.999D), List.of());

        for (int draw = 0; draw < 50; draw++) {
            Vec3 centre = geometry.randomCellCentre();
            assertTrue(centre.x() == 2.5D || centre.x() == 3.5D, "x=" + centre.x());
            assertEquals(64.5D, centre.y());
            assertEquals(5.5D, centre.z());
        }
    }

    @Test
    void emptyGeometryHasNoCellCentre() {
        assertNull(new ApertureCells().randomCellCentre());
    }
}
