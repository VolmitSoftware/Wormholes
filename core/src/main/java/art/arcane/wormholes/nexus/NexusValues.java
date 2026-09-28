package art.arcane.wormholes.nexus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class NexusValues {
    private NexusValues() {
    }

    static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> source)) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() instanceof String key) {
                result.put(key, entry.getValue());
            }
        }
        return result;
    }

    static List<?> list(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    static String string(Map<String, Object> values, String key, String fallback) {
        return values.get(key) instanceof String value ? value : fallback;
    }

    static int integer(Map<String, Object> values, String key, int fallback) {
        return values.get(key) instanceof Number value ? value.intValue() : fallback;
    }

    static long number(Map<String, Object> values, String key, long fallback) {
        return values.get(key) instanceof Number value ? value.longValue() : fallback;
    }

    static boolean bool(Map<String, Object> values, String key, boolean fallback) {
        return values.get(key) instanceof Boolean value ? value : fallback;
    }
}
