package art.arcane.wormholes.ops.backup;

import art.arcane.volmlib.util.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** What a bundle was taken from: plugin build, schema versions, world-key table, and signer. */
public record BackupManifest(String pluginVersion, int configSchema, int doorsSchema, long createdAtMillis,
                             Map<String, String> worldKeys, String serverName, String fingerprint) {
    public BackupManifest {
        pluginVersion = pluginVersion == null ? "unknown" : pluginVersion;
        worldKeys = worldKeys == null ? Map.of() : Map.copyOf(worldKeys);
        serverName = serverName == null ? "" : serverName;
        fingerprint = fingerprint == null ? "unsigned" : fingerprint;
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        json.put("pluginVersion", pluginVersion);
        json.put("configSchema", configSchema);
        json.put("doorsSchema", doorsSchema);
        json.put("createdAtMillis", createdAtMillis);
        json.put("serverName", serverName);
        json.put("fingerprint", fingerprint);
        JSONObject worlds = new JSONObject();
        for (Map.Entry<String, String> world : new TreeMap<>(worldKeys).entrySet()) {
            worlds.put(world.getKey(), world.getValue());
        }
        json.put("worldKeys", worlds);
        return json;
    }

    public static BackupManifest fromJson(JSONObject json) {
        Map<String, String> worlds = new LinkedHashMap<>();
        JSONObject worldKeys = json.optJSONObject("worldKeys");
        if (worldKeys != null) {
            for (String name : worldKeys.keySet()) {
                worlds.put(name, worldKeys.getString(name));
            }
        }
        return new BackupManifest(
            json.optString("pluginVersion", "unknown"),
            json.optInt("configSchema", 0),
            json.optInt("doorsSchema", 0),
            json.optLong("createdAtMillis", 0L),
            worlds,
            json.optString("serverName", ""),
            json.optString("fingerprint", "unsigned"));
    }
}
