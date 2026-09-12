package art.arcane.wormholes.rules;

import art.arcane.volmlib.util.json.JSONArray;
import art.arcane.volmlib.util.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Flattens one condition, cost or effect between its JSON form and a single template line such as
 * {@code kind=PERMISSION;node=group.vip}. Nested item matchers become dotted fields and sets become
 * comma-separated values, so the JSON codec stays the only place that knows what the fields mean.
 *
 * <p>Every value stays a string on the way back in; the JSON codec's typed reads coerce it. Inferring a type
 * here would quietly reshape values the codec cares about, such as the scale of a Vault amount.</p>
 */
final class RuleLineCodec {
    private static final Set<String> ARRAY_FIELDS = Set.of("classes", "keys", "phases");

    private RuleLineCodec() {
    }

    static String toLine(JSONObject json) {
        StringBuilder line = new StringBuilder();
        append(line, "kind", json.optString("kind", ""));
        for (String field : json.keySet()) {
            if ("kind".equals(field)) {
                continue;
            }
            Object value = json.get(field);
            if (value instanceof JSONObject nested) {
                for (String nestedField : nested.keySet()) {
                    append(line, field + "." + nestedField, String.valueOf(nested.get(nestedField)));
                }
            } else if (value instanceof JSONArray array) {
                append(line, field, join(array));
            } else {
                append(line, field, String.valueOf(value));
            }
        }
        return line.toString();
    }

    static JSONObject fromLine(String line) {
        JSONObject json = new JSONObject();
        Map<String, JSONObject> nested = new LinkedHashMap<>();
        for (String pair : split(line, ';')) {
            int separator = indexOfUnescaped(pair, '=');
            if (separator < 0) {
                continue;
            }
            String field = unescape(pair.substring(0, separator)).trim();
            String value = unescape(pair.substring(separator + 1));
            if (field.isEmpty()) {
                continue;
            }
            int dot = field.indexOf('.');
            if (dot > 0) {
                nested.computeIfAbsent(field.substring(0, dot), ignored -> new JSONObject())
                    .put(field.substring(dot + 1), value);
                continue;
            }
            if (ARRAY_FIELDS.contains(field)) {
                json.put(field, array(value));
                continue;
            }
            json.put(field, value);
        }
        for (Map.Entry<String, JSONObject> entry : nested.entrySet()) {
            json.put(entry.getKey(), entry.getValue());
        }
        return json;
    }

    private static void append(StringBuilder line, String field, String value) {
        if (!line.isEmpty()) {
            line.append(';');
        }
        line.append(escape(field)).append('=').append(escape(value));
    }

    private static String join(JSONArray array) {
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < array.length(); i++) {
            if (i > 0) {
                joined.append(',');
            }
            joined.append(array.opt(i));
        }
        return joined.toString();
    }

    private static JSONArray array(String value) {
        JSONArray array = new JSONArray();
        if (value.isBlank()) {
            return array;
        }
        for (String element : value.split(",")) {
            array.put(element.trim());
        }
        return array;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace(";", "\\;").replace("=", "\\=");
    }

    private static String unescape(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                out.append(value.charAt(++i));
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    private static List<String> split(String value, char separator) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                current.append(c).append(value.charAt(++i));
                continue;
            }
            if (c == separator) {
                parts.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        parts.add(current.toString());
        return parts;
    }

    private static int indexOfUnescaped(String value, char target) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\') {
                i++;
                continue;
            }
            if (c == target) {
                return i;
            }
        }
        return -1;
    }
}
