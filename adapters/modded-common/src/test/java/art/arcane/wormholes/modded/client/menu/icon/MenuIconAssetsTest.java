package art.arcane.wormholes.modded.client.menu.icon;

import art.arcane.wormholes.modded.client.menu.icon.MenuIconMaps.MenuIconMap;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class MenuIconAssetsTest {
    private static final int MAX_COLORS = 8;
    private static final List<String> REQUIRED = List.of("portal", "wormhole", "gateway", "rtp", "door", "pocket", "settings",
        "access", "cost", "projection", "transit", "atlas", "rename", "delete", "back", "prev", "next", "confirm", "cancel",
        "toggle_on", "toggle_off", "sort", "search", "player", "network", "cosmetics", "language", "info", "warning",
        "mirror", "link", "rotate", "favorite", "reset");
    private static final List<String> SMALL_ONLY = List.of("chevron_right");
    private static final int[][] NEIGHBOURS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    @Test
    public void everyIconHasExactlyItsRequiredMaps() {
        Map<String, Integer> counts = new HashMap<>();
        for (MenuIconMap map : MenuIconMaps.all()) {
            counts.merge(map.name(), 1, Integer::sum);
        }
        Set<String> expected = new HashSet<>();
        for (String name : REQUIRED) {
            expected.add(name);
            expected.add(MenuIconMaps.SMALL_PREFIX + name);
        }
        for (String name : SMALL_ONLY) {
            expected.add(MenuIconMaps.SMALL_PREFIX + name);
        }
        assertEquals(expected, counts.keySet());
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            assertEquals("duplicate map " + entry.getKey(), 1, (int) entry.getValue());
        }
    }

    @Test
    public void smallVariantsAreSeparatelyDrawnMapsNotDownscales() {
        Map<String, MenuIconMap> byName = byName();
        for (String name : REQUIRED) {
            MenuIconMap large = byName.get(name);
            MenuIconMap small = byName.get(MenuIconMaps.SMALL_PREFIX + name);
            assertNotNull("missing 16x16 map for " + name, large);
            assertNotNull("missing separately drawn 12x12 map for " + name, small);
            assertEquals(name, MenuIconMaps.LARGE_SIZE, large.size());
            assertEquals(name, MenuIconMaps.SMALL_SIZE, small.size());
        }
        for (String name : SMALL_ONLY) {
            MenuIconMap small = byName.get(MenuIconMaps.SMALL_PREFIX + name);
            assertNotNull("missing 12x12 map for " + name, small);
            assertEquals(name, MenuIconMaps.SMALL_SIZE, small.size());
        }
    }

    @Test
    public void everyMapIsSquareAtItsDeclaredSize() {
        for (MenuIconMap map : MenuIconMaps.all()) {
            int size = map.small() ? MenuIconMaps.SMALL_SIZE : MenuIconMaps.LARGE_SIZE;
            assertEquals(map.name() + " row count", size, map.rows().size());
            for (int y = 0; y < size; y++) {
                assertEquals(map.name() + " row " + y + " width", size, map.rows().get(y).length());
            }
        }
    }

    @Test
    public void everyCellResolvesToThePaletteWithinTheColorCap() {
        for (MenuIconMap map : MenuIconMaps.all()) {
            Set<MenuIconPalette> used = EnumSet.noneOf(MenuIconPalette.class);
            for (int y = 0; y < map.size(); y++) {
                for (int x = 0; x < map.size(); x++) {
                    char cell = map.cell(x, y);
                    if (cell == MenuIconMaps.TRANSPARENT) {
                        continue;
                    }
                    MenuIconPalette color = map.legend().get(cell);
                    assertNotNull(map.name() + " undefined key '" + cell + "' at " + x + "," + y, color);
                    used.add(color);
                }
            }
            assertTrue(map.name() + " uses " + used.size() + " colors", used.size() <= MAX_COLORS);
            assertTrue(map.name() + " has no outline", used.contains(MenuIconPalette.OUTLINE));
        }
    }

    @Test
    public void everySilhouetteEdgeIsOutlined() {
        for (MenuIconMap map : MenuIconMaps.all()) {
            for (int y = 0; y < map.size(); y++) {
                for (int x = 0; x < map.size(); x++) {
                    char cell = map.cell(x, y);
                    if (cell == MenuIconMaps.TRANSPARENT || map.legend().get(cell) == MenuIconPalette.OUTLINE) {
                        continue;
                    }
                    for (int[] step : NEIGHBOURS) {
                        int nx = x + step[0];
                        int ny = y + step[1];
                        boolean open = nx < 0 || ny < 0 || nx >= map.size() || ny >= map.size() || map.cell(nx, ny) == MenuIconMaps.TRANSPARENT;
                        assertFalse(map.name() + " fill at " + x + "," + y + " touches transparency without outline", open);
                    }
                }
            }
        }
    }

    @Test
    public void largeIconsKeepATransparentOnePixelMargin() {
        for (MenuIconMap map : MenuIconMaps.all()) {
            if (map.small()) {
                continue;
            }
            int last = map.size() - 1;
            for (int i = 0; i <= last; i++) {
                assertEquals(map.name() + " top margin", MenuIconMaps.TRANSPARENT, map.cell(i, 0));
                assertEquals(map.name() + " bottom margin", MenuIconMaps.TRANSPARENT, map.cell(i, last));
                assertEquals(map.name() + " left margin", MenuIconMaps.TRANSPARENT, map.cell(0, i));
                assertEquals(map.name() + " right margin", MenuIconMaps.TRANSPARENT, map.cell(last, i));
            }
        }
    }

    @Test
    public void committedPngsMatchTheirMapsPixelForPixel() throws IOException {
        Path directory = MenuIconGenerator.iconDirectory();
        List<String> mismatches = new ArrayList<>();
        for (MenuIconMap map : MenuIconMaps.all()) {
            Path file = directory.resolve(map.name() + ".png");
            if (!Files.isRegularFile(file)) {
                mismatches.add(map.name() + " missing");
                continue;
            }
            BufferedImage actual = ImageIO.read(file.toFile());
            if (actual == null || actual.getWidth() != map.size() || actual.getHeight() != map.size()) {
                mismatches.add(map.name() + " has the wrong dimensions");
                continue;
            }
            if (!samePixels(map, actual)) {
                mismatches.add(map.name() + " pixels differ");
            }
        }
        assertTrue("Committed icons are out of date " + mismatches + ". Regenerate from the repository root: " + MenuIconGenerator.COMMAND, mismatches.isEmpty());
    }

    @Test
    public void committedPngsUseOnlyOpaquePaletteColors() throws IOException {
        Set<Integer> palette = new HashSet<>();
        for (MenuIconPalette color : MenuIconPalette.values()) {
            palette.add(color.argb());
        }
        Path directory = MenuIconGenerator.iconDirectory();
        for (MenuIconMap map : MenuIconMaps.all()) {
            BufferedImage image = ImageIO.read(directory.resolve(map.name() + ".png").toFile());
            assertNotNull(map.name(), image);
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int argb = image.getRGB(x, y);
                    if (argb >>> 24 == 0) {
                        continue;
                    }
                    assertTrue(map.name() + " off-palette pixel at " + x + "," + y + ": " + Integer.toHexString(argb), palette.contains(argb));
                }
            }
        }
    }

    @Test
    public void iconDirectoryHoldsOnlyMappedPngs() throws IOException {
        Set<String> expected = new HashSet<>();
        for (MenuIconMap map : MenuIconMaps.all()) {
            expected.add(map.name() + ".png");
        }
        Path directory = MenuIconGenerator.iconDirectory();
        Set<String> actual = new HashSet<>();
        try (Stream<Path> files = Files.walk(directory)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                actual.add(relativeName(directory, file));
            }
        }
        assertEquals("Regenerate from the repository root: " + MenuIconGenerator.COMMAND, expected, actual);
    }

    private static Map<String, MenuIconMap> byName() {
        Map<String, MenuIconMap> maps = new HashMap<>();
        for (MenuIconMap map : MenuIconMaps.all()) {
            maps.put(map.name(), map);
        }
        return maps;
    }

    private static String relativeName(Path directory, Path file) {
        StringJoiner name = new StringJoiner("/");
        for (Path part : directory.relativize(file)) {
            name.add(part.toString());
        }
        return name.toString();
    }

    private static boolean samePixels(MenuIconMap map, BufferedImage actual) {
        for (int y = 0; y < map.size(); y++) {
            for (int x = 0; x < map.size(); x++) {
                int expected = map.argb(x, y);
                int found = actual.getRGB(x, y);
                boolean transparent = expected >>> 24 == 0;
                if (transparent ? found >>> 24 != 0 : found != expected) {
                    return false;
                }
            }
        }
        return true;
    }
}
