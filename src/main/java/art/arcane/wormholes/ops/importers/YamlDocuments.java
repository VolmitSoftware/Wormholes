package art.arcane.wormholes.ops.importers;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Reads another plugin's YAML without the Bukkit file helpers, so a parse error is thrown, not logged. */
final class YamlDocuments {
    private YamlDocuments() {
    }

    /** Null when the file is missing or unparseable. */
    static ConfigurationSection read(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        YamlConfiguration configuration = new YamlConfiguration();
        try {
            configuration.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | InvalidConfigurationException unreadable) {
            return null;
        }
        return configuration;
    }

    static int intAt(ConfigurationSection section, String path, int fallback) {
        return section == null ? fallback : section.getInt(path, fallback);
    }

    static String stringAt(ConfigurationSection section, String path, String fallback) {
        if (section == null) {
            return fallback;
        }
        String value = section.getString(path);
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
