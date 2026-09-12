package art.arcane.wormholes.hook;

import art.arcane.volmlib.util.json.JSONObject;

import java.util.Map;

/**
 * Per-portal state owned by one lane. Persisted inside the portal JSON under keys that start with
 * {@link #key()} + "." and optionally replicated to linked portals through the settings-sync string bag
 * under the same prefix. One instance per {@code LocalPortal}.
 */
public interface PortalExtension {
    /** Stable JSON and sync key prefix, for example {@code "rules"}. */
    String key();

    default void save(JSONObject portalJson) {
    }

    default void load(JSONObject portalJson) {
    }

    /** Put replicated settings as {@code key() + "." + name} entries. Keep values under 1024 bytes. */
    default void collectSync(Map<String, String> settings) {
    }

    /** Apply replicated settings received from a linked portal; only entries with this key prefix are present. */
    default void applySync(Map<String, String> settings) {
    }

    default void onPortalDestroyed() {
    }
}
