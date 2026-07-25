package art.arcane.wormholes.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.util.Cuboid;
import art.arcane.wormholes.util.Direction;

public final class PortalStructureKeySpaceTest {
    private static final Path MAIN_SOURCES = Path.of("src/main/java");
    private static final Path STRUCTURE_SOURCE =
        Path.of("src/main/java/art/arcane/wormholes/portal/PortalStructure.java");
    private static final String[] KEY_SYMBOLS = new String[] {
        "packBlockKey", "unpackBlockX", "unpackBlockY", "unpackBlockZ"
    };

    @Test
    public void theStructureBlockKeyIsNeverProducedOrConsumedOutsideItsOwnClass() throws IOException {
        List<String> leaks = new ArrayList<String>();
        try (Stream<Path> sources = Files.walk(MAIN_SOURCES)) {
            List<Path> files = sources.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".java"))
                .filter(path -> !path.equals(STRUCTURE_SOURCE))
                .sorted()
                .toList();
            for (Path file : files) {
                String body = Files.readString(file, StandardCharsets.UTF_8);
                for (String symbol : KEY_SYMBOLS) {
                    if (body.contains(symbol)) {
                        leaks.add(file + " references " + symbol);
                    }
                }
            }
        }

        assertEquals(List.of(), leaks,
            "PortalStructure block keys are a private sorted index, not a shared key space; the day one of them is "
                + "handed to another subsystem it has to be unified with that subsystem's layout instead");
    }

    @Test
    public void theStructureBlockIndexDoesNotDependOnTheRenderKeySpace() throws IOException {
        String body = Files.readString(STRUCTURE_SOURCE, StandardCharsets.UTF_8);

        assertFalse(body.contains("art.arcane.wormholes.render"),
            "the portal model must not depend on the render package; sharing the render cell key here would invert "
                + "the package dependency and force a render internal to become public API");
        assertTrue(body.contains("private long[] blockKeys"),
            "the block key array must stay private so the key space cannot escape the class");
    }

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
