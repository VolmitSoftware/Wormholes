package art.arcane.wormholes.ops.importers;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Reads another plugin's YAML without the Bukkit file helpers, so a parse error is thrown, not logged. */
final class YamlDocuments {
    private YamlDocuments() {
    }

    /** Null when the file is missing or unparseable. */
    static Map<String, Object> read(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return object(new Yaml(new SafeConstructor(new LoaderOptions())).load(Files.readString(file, StandardCharsets.UTF_8)));
        } catch (IOException | YAMLException unreadable) {
            return null;
        }
    }

    static Map<String, Object> section(Map<String, Object> document, String key) {
        return object(document.get(key));
    }

    static int intAt(Map<String, Object> document, String path, int fallback) {
        Object value = valueAt(document, path);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    static double doubleAt(Map<String, Object> document, String path, double fallback) {
        Object value = valueAt(document, path);
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

    static String stringAt(Map<String, Object> document, String path, String fallback) {
        Object value = valueAt(document, path);
        return value == null || value.toString().isBlank() ? fallback : value.toString().trim();
    }

    private static Object valueAt(Map<String, Object> document, String path) {
        Object value = document;
        for (String key : path.split("\\.")) {
            if (!(value instanceof Map<?, ?> section)) {
                return null;
            }
            value = section.get(key);
        }
        return value;
    }

    private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() instanceof String key) {
                result.put(key, entry.getValue());
            }
        }
        return result;
    }
}
