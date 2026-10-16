package art.arcane.wormholes.menu;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.config.toml.MenuConfig;
import art.arcane.wormholes.config.toml.WormholesConfigFile;
import art.arcane.wormholes.util.project.config.TomlCodec;

final class MenuConfigTest {
    @Test
    void nativeScreensDefaultOn() {
        assertTrue(new MenuConfig().nativeScreens);
        assertTrue(WormholesSettings.FeatureSections.defaults().menus().nativeScreens);
    }

    @Test
    void nativeScreensRoundTripThroughTheMenusTable() {
        WormholesConfigFile file = new WormholesConfigFile();
        file.menus.nativeScreens = false;

        String content = TomlCodec.canonicalContent(file);
        TomlCodec.LoadResult<WormholesConfigFile> loaded = TomlCodec.readContent(content, WormholesConfigFile.class);

        assertTrue(content.contains("[menus]"), content);
        assertTrue(content.contains("native-screens = false"), content);
        assertTrue(loaded.isSuccess(), () -> String.valueOf(loaded.error()));
        assertFalse(loaded.value().menus.nativeScreens);
    }

    @Test
    void missingMenusTableKeepsTheDefault() {
        TomlCodec.LoadResult<WormholesConfigFile> loaded = TomlCodec.readContent("schema = 3\n", WormholesConfigFile.class);

        assertTrue(loaded.isSuccess(), () -> String.valueOf(loaded.error()));
        assertTrue(loaded.value().menus.nativeScreens);
    }
}
