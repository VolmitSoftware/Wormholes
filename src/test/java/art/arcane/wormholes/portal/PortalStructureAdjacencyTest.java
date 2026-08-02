package art.arcane.wormholes.portal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.util.Cuboid;
import art.arcane.wormholes.util.Direction;

public final class PortalStructureAdjacencyTest {
    @Test
    public void theStructureCellsThemselvesCount() {
        PortalStructure structure = plane();

        assertTrue(structure.containsOrAdjoinsBlock(10, 64, 5));
        assertTrue(structure.containsOrAdjoinsBlock(10, 66, 7));
    }

    @Test
    public void theSurroundingFrameCountsIncludingItsCorners() {
        PortalStructure structure = plane();

        assertTrue(structure.containsOrAdjoinsBlock(10, 63, 5));
        assertTrue(structure.containsOrAdjoinsBlock(10, 67, 7));
        assertTrue(structure.containsOrAdjoinsBlock(10, 64, 4));
        assertTrue(structure.containsOrAdjoinsBlock(10, 66, 8));
        assertTrue(structure.containsOrAdjoinsBlock(10, 63, 4));
        assertTrue(structure.containsOrAdjoinsBlock(10, 67, 8));
        assertTrue(structure.containsOrAdjoinsBlock(9, 65, 6));
        assertTrue(structure.containsOrAdjoinsBlock(11, 65, 6));
    }

    @Test
    public void blocksBehindOrBesideThePortalAreNotItsFrame() {
        PortalStructure structure = plane();

        assertFalse(structure.containsOrAdjoinsBlock(12, 65, 6));
        assertFalse(structure.containsOrAdjoinsBlock(8, 65, 6));
        assertFalse(structure.containsOrAdjoinsBlock(10, 62, 6));
        assertFalse(structure.containsOrAdjoinsBlock(10, 68, 6));
        assertFalse(structure.containsOrAdjoinsBlock(10, 65, 3));
        assertFalse(structure.containsOrAdjoinsBlock(10, 65, 9));
        assertFalse(structure.containsOrAdjoinsBlock(40, 65, 6));
    }

    private static PortalStructure plane() {
        PortalStructure structure = new PortalStructure();
        structure.setArea(cuboid(10, 64, 5, 10, 66, 7));
        return structure;
    }

    private static Cuboid cuboid(int x1, int y1, int z1, int x2, int y2, int z2) {
        Map<String, Object> map = new HashMap<String, Object>();
        map.put("worldKey", "minecraft:overworld");
        map.put("x1", Integer.valueOf(x1));
        map.put("y1", Integer.valueOf(y1));
        map.put("z1", Integer.valueOf(z1));
        map.put("x2", Integer.valueOf(x2));
        map.put("y2", Integer.valueOf(y2));
        map.put("z2", Integer.valueOf(z2));
        return new AssertSafeCuboid(map);
    }

    private static final class AssertSafeCuboid extends Cuboid {
        private AssertSafeCuboid(Map<String, Object> map) {
            super(map);
        }

        @Override
        public Vector getCornerVector(Direction x, Direction y, Direction z) {
            double s = 0.999D;
            return new Vector(x.x() == 1 ? (x2 + s) : x1, y.y() == 1 ? (y2 + s) : y1, z.z() == 1 ? (z2 + s) : z1);
        }
    }
}
