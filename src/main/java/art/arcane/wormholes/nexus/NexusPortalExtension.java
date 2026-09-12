package art.arcane.wormholes.nexus;

import art.arcane.volmlib.util.json.JSONObject;
import art.arcane.wormholes.hook.PortalExtension;
import art.arcane.wormholes.portal.ITunnel;
import art.arcane.wormholes.portal.LocalPortal;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Per-portal nexus state: network membership, dial address, destination policy, return-link flag and
 * redstone wiring. Persisted under {@code nexus.} keys in the portal JSON; four of those keys ride the
 * settings bag so a linked remote portal can show its address.
 */
public final class NexusPortalExtension implements PortalExtension {
    public static final String KEY = "nexus";
    public static final String SYNC_NETWORK_ID = KEY + ".networkId";
    public static final String SYNC_ADDRESS = KEY + ".address";
    public static final String SYNC_DIAL = KEY + ".dial";
    public static final String SYNC_LABEL = KEY + ".label";

    private final LocalPortal portal;
    private final NexusPortalListener listener;
    private final ConcurrentHashMap<String, ITunnel> resolvedTunnels = new ConcurrentHashMap<>();

    private volatile UUID networkId;
    private volatile String address = "";
    private volatile String label = "";
    private volatile DialState dial = DialState.idle();
    private volatile DestinationPolicy policy = DestinationPolicy.empty();
    private volatile boolean reciprocal;
    private volatile FrameIo frameIo = FrameIo.none();

    NexusPortalExtension(LocalPortal portal, NexusPortalListener listener) {
        this.portal = portal;
        this.listener = listener;
    }

    @Override
    public String key() {
        return KEY;
    }

    public LocalPortal portal() {
        return portal;
    }

    public UUID networkId() {
        return networkId;
    }

    public void setNetworkId(UUID newNetworkId) {
        networkId = newNetworkId;
        clearResolvedTunnels();
    }

    public String address() {
        return address;
    }

    public void setAddress(String newAddress) {
        address = newAddress == null || newAddress.isBlank() ? "" : NetworkMember.normalizeAddress(newAddress);
    }

    public String label() {
        return label;
    }

    public void setLabel(String newLabel) {
        label = newLabel == null ? "" : newLabel;
    }

    public DialState dial() {
        return dial;
    }

    public void setDial(DialState newDial) {
        dial = newDial == null ? DialState.idle() : newDial;
    }

    public DestinationPolicy policy() {
        return policy;
    }

    public void setPolicy(DestinationPolicy newPolicy) {
        policy = newPolicy == null ? DestinationPolicy.empty() : newPolicy;
        clearResolvedTunnels();
    }

    /**
     * One built tunnel per destination this portal resolves to, so a per-player or return policy does
     * not rebuild a tunnel on every traversal. Cleared whenever the policy or the network changes.
     */
    public ITunnel cachedTunnel(String cacheKey, Supplier<ITunnel> factory) {
        ITunnel cached = resolvedTunnels.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        ITunnel built = factory.get();
        if (built == null) {
            return null;
        }
        ITunnel raced = resolvedTunnels.putIfAbsent(cacheKey, built);
        return raced == null ? built : raced;
    }

    public void clearResolvedTunnels() {
        resolvedTunnels.clear();
    }

    public boolean reciprocal() {
        return reciprocal;
    }

    public void setReciprocal(boolean newReciprocal) {
        reciprocal = newReciprocal;
    }

    public FrameIo frameIo() {
        return frameIo;
    }

    public void setFrameIo(FrameIo newFrameIo) {
        frameIo = newFrameIo == null ? FrameIo.none() : newFrameIo;
    }

    public boolean isOnNetwork() {
        return networkId != null && !address.isEmpty();
    }

    /** The address this portal falls back to when a manual dial expires. */
    public String defaultAddress() {
        return address;
    }

    @Override
    public void save(JSONObject portalJson) {
        if (networkId != null) {
            portalJson.put(SYNC_NETWORK_ID, networkId.toString());
        }
        if (!address.isEmpty()) {
            portalJson.put(SYNC_ADDRESS, address);
        }
        if (!label.isEmpty()) {
            portalJson.put(SYNC_LABEL, label);
        }
        if (dial.isDialed()) {
            portalJson.put(KEY + ".dial", dial.toJSON());
        }
        if (policy.isActive()) {
            portalJson.put(KEY + ".policy", policy.toJSON());
        }
        if (reciprocal) {
            portalJson.put(KEY + ".reciprocal", true);
        }
        if (frameIo.isWired()) {
            portalJson.put(KEY + ".frameIo", frameIo.toJSON());
        }
    }

    @Override
    public void load(JSONObject portalJson) {
        String encodedNetwork = portalJson.optString(SYNC_NETWORK_ID, "");
        networkId = encodedNetwork.isBlank() ? null : parseUuid(encodedNetwork);
        setAddress(portalJson.optString(SYNC_ADDRESS, ""));
        setLabel(portalJson.optString(SYNC_LABEL, ""));
        dial = DialState.fromJSON(portalJson.optJSONObject(KEY + ".dial"));
        policy = DestinationPolicy.fromJSON(portalJson.optJSONObject(KEY + ".policy"));
        reciprocal = portalJson.optBoolean(KEY + ".reciprocal", false);
        frameIo = FrameIo.fromJSON(portalJson.optJSONObject(KEY + ".frameIo"));
    }

    @Override
    public void collectSync(Map<String, String> settings) {
        settings.put(SYNC_NETWORK_ID, networkId == null ? "" : networkId.toString());
        settings.put(SYNC_ADDRESS, address);
        settings.put(SYNC_DIAL, dial.currentAddress());
        settings.put(SYNC_LABEL, label);
    }

    @Override
    public void applySync(Map<String, String> settings) {
        if (settings.containsKey(SYNC_NETWORK_ID)) {
            String encoded = settings.get(SYNC_NETWORK_ID);
            networkId = encoded == null || encoded.isBlank() ? null : parseUuid(encoded);
        }
        if (settings.containsKey(SYNC_ADDRESS)) {
            setAddress(settings.get(SYNC_ADDRESS));
        }
        if (settings.containsKey(SYNC_LABEL)) {
            setLabel(settings.get(SYNC_LABEL));
        }
        if (settings.containsKey(SYNC_DIAL)) {
            String dialed = settings.get(SYNC_DIAL);
            dial = dialed == null || dialed.isBlank() ? DialState.idle() : new DialState(dialed, dial.dialedAtMillis(), dial.dialedBy(), dial.sticky());
        }
    }

    @Override
    public void onPortalDestroyed() {
        if (listener != null && portal != null) {
            listener.onPortalDestroyed(portal, this);
        }
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }
}
