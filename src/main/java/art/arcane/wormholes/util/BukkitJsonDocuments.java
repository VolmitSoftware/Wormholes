package art.arcane.wormholes.util;

import art.arcane.volmlib.util.json.JSONObject;
import art.arcane.volmlib.util.json.JSONArray;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ArrayList;

import java.util.Map;

public enum BukkitJsonDocuments implements JsonDocuments {
    INSTANCE;

    @Override
    public Map<String, Object> decode(String source) {
        return values(new JSONObject(source));
    }

    @Override
    public String encode(Map<String, Object> document) {
        return new JSONObject(document).toString(2);
    }
    public static Map<String, Object> values(JSONObject json) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (String key : json.keySet()) {
            values.put(key, value(json.get(key)));
        }
        return values;
    }

    private static Object value(Object value) {
        if (value instanceof JSONObject object) {
            return values(object);
        }
        if (value instanceof JSONArray array) {
            List<Object> values = new ArrayList<>(array.length());
            for (int i = 0; i < array.length(); i++) {
                values.add(value(array.get(i)));
            }
            return values;
        }
        return value == JSONObject.NULL ? null : value;
    }
}
