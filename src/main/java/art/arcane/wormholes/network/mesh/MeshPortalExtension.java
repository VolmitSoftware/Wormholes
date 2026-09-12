package art.arcane.wormholes.network.mesh;

import art.arcane.volmlib.util.json.JSONObject;
import art.arcane.wormholes.hook.PortalExtension;
import art.arcane.wormholes.portal.LocalPortal;

import java.util.Map;

/**
 * Per-portal mesh state: the gateway's destination policy, saved as {@code mesh.policy} in the
 * portal JSON and mirrored to linked portals through the settings bag under the same key.
 */
public final class MeshPortalExtension implements PortalExtension {
    public static final String KEY = "mesh";
    static final String POLICY_KEY = KEY + ".policy";

    private final LocalPortal portal;
    private volatile DestinationPolicy policy;

    public MeshPortalExtension(LocalPortal portal) {
        this.portal = portal;
    }

    @Override
    public String key() {
        return KEY;
    }

    public DestinationPolicy policy() {
        return policy;
    }

    public void setPolicy(DestinationPolicy policy) {
        this.policy = policy;
        if (portal != null) {
            portal.save();
        }
    }

    @Override
    public void save(JSONObject portalJson) {
        DestinationPolicy active = policy;
        if (active != null) {
            portalJson.put(POLICY_KEY, active.encode());
        }
    }

    @Override
    public void load(JSONObject portalJson) {
        policy = DestinationPolicy.decode(portalJson.optString(POLICY_KEY, ""));
    }

    @Override
    public void collectSync(Map<String, String> settings) {
        DestinationPolicy active = policy;
        if (active != null) {
            settings.put(POLICY_KEY, active.encode());
        }
    }

    @Override
    public void applySync(Map<String, String> settings) {
        String encoded = settings.get(POLICY_KEY);
        if (encoded != null && !encoded.isBlank()) {
            DestinationPolicy decoded = DestinationPolicy.decode(encoded);
            if (decoded != null) {
                policy = decoded;
            }
        }
    }
}
