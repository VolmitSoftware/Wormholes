package art.arcane.wormholes.ops.backup;

import art.arcane.volmlib.util.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** World-key and owner rewrites applied to portal JSON while a bundle is restored or imported. */
public record WorldKeyRemap(Map<String, String> worlds, Map<UUID, UUID> owners) {
    public WorldKeyRemap {
        worlds = worlds == null ? Map.of() : Map.copyOf(worlds);
        owners = owners == null ? Map.of() : Map.copyOf(owners);
    }

    public static WorldKeyRemap none() {
        return new WorldKeyRemap(Map.of(), Map.of());
    }

    /** Both arguments are {@code old=new,old=new} lists; blank means no rewrite. */
    public static WorldKeyRemap parse(String worldMap, String ownerMap) {
        Map<String, String> worlds = new LinkedHashMap<>();
        for (Map.Entry<String, String> pair : pairs(worldMap).entrySet()) {
            worlds.put(pair.getKey(), pair.getValue());
        }
        Map<UUID, UUID> owners = new LinkedHashMap<>();
        for (Map.Entry<String, String> pair : pairs(ownerMap).entrySet()) {
            owners.put(uuid(pair.getKey()), uuid(pair.getValue()));
        }
        return new WorldKeyRemap(worlds, owners);
    }

    public boolean isEmpty() {
        return worlds.isEmpty() && owners.isEmpty();
    }

    /** Rewrites {@code structure.worldKey} and {@code owner} in place and reports whether it changed. */
    public boolean applyTo(JSONObject portal) {
        boolean changed = false;
        JSONObject structure = portal.optJSONObject("structure");
        if (structure != null) {
            String worldKey = structure.optString("worldKey", "");
            String replacement = worlds.get(worldKey);
            if (replacement != null) {
                structure.put("worldKey", replacement);
                changed = true;
            }
        }
        String owner = portal.optString("owner", "");
        if (!owner.isEmpty() && !owners.isEmpty()) {
            UUID replacement = owners.get(uuidOrNull(owner));
            if (replacement != null) {
                portal.put("owner", replacement.toString());
                changed = true;
            }
        }
        return changed;
    }

    private static Map<String, String> pairs(String list) {
        Map<String, String> pairs = new LinkedHashMap<>();
        if (list == null || list.isBlank()) {
            return pairs;
        }
        for (String token : list.split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int split = trimmed.indexOf('=');
            if (split <= 0 || split == trimmed.length() - 1) {
                throw new IllegalArgumentException("Remap entries must be old=new, got: " + trimmed);
            }
            pairs.put(trimmed.substring(0, split).trim(), trimmed.substring(split + 1).trim());
        }
        return pairs;
    }

    private static UUID uuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException malformed) {
            throw new IllegalArgumentException("Owner remap entries must be uuids, got: " + value);
        }
    }

    private static UUID uuidOrNull(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }
}
