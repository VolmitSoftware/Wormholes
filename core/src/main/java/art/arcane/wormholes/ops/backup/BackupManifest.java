package art.arcane.wormholes.ops.backup;

import com.google.gson.JsonObject;

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

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("pluginVersion", pluginVersion);
        json.addProperty("configSchema", configSchema);
        json.addProperty("doorsSchema", doorsSchema);
        json.addProperty("createdAtMillis", createdAtMillis);
        json.addProperty("serverName", serverName);
        json.addProperty("fingerprint", fingerprint);
        JsonObject worlds = new JsonObject();
        for (Map.Entry<String, String> world : new TreeMap<>(worldKeys).entrySet()) {
            worlds.addProperty(world.getKey(), world.getValue());
        }
        json.add("worldKeys", worlds);
        return json;
    }

    public static BackupManifest fromJson(JsonObject json) {
        Map<String, String> worlds = new LinkedHashMap<>();
        JsonObject worldKeys = json.has("worldKeys") && json.get("worldKeys").isJsonObject() ? json.getAsJsonObject("worldKeys") : null;
        if (worldKeys != null) {
            for (String name : worldKeys.keySet()) {
                worlds.put(name, worldKeys.get(name).getAsString());
            }
        }
        return new BackupManifest(
            json.has("pluginVersion") ? json.get("pluginVersion").getAsString() : "unknown",
            json.has("configSchema") ? json.get("configSchema").getAsInt() : 0,
            json.has("doorsSchema") ? json.get("doorsSchema").getAsInt() : 0,
            json.has("createdAtMillis") ? json.get("createdAtMillis").getAsLong() : 0L,
            worlds,
            json.has("serverName") ? json.get("serverName").getAsString() : "",
            json.has("fingerprint") ? json.get("fingerprint").getAsString() : "unsigned");
    }
}
