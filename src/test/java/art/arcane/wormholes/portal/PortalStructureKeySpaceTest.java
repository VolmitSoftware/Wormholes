package art.arcane.wormholes.portal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.util.Cuboid;
import art.arcane.wormholes.util.Direction;

public final class PortalStructureKeySpaceTest {
    @Test
    public void theStructureIndexKeepsExtremeNegativeCellsDistinct() {
        PortalStructure structure = new PortalStructure();
        structure.setArea(cuboid(-30_000_000, -2032, -30_000_000, -29_999_998, -2030, -29_999_998));

        assertTrue(structure.containsBlock(-30_000_000, -2032, -30_000_000));
        assertTrue(structure.containsBlock(-29_999_999, -2031, -29_999_999));
        assertTrue(structure.containsBlock(-29_999_998, -2030, -29_999_998));
        assertFalse(structure.containsBlock(-30_000_001, -2032, -30_000_000));
        assertFalse(structure.containsBlock(-30_000_000, -2033, -30_000_000));
        assertFalse(structure.containsBlock(-30_000_000, -2032, -30_000_001));
        assertFalse(structure.containsBlock(30_000_000, -2032, -30_000_000));
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
