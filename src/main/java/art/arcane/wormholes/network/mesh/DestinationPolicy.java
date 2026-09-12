package art.arcane.wormholes.network.mesh;

import art.arcane.volmlib.util.json.JSONArray;
import art.arcane.volmlib.util.json.JSONException;
import art.arcane.volmlib.util.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * A gateway's destination policy: ordered candidates, a selection strategy, admission thresholds and
 * whether travelers wait when every candidate is full. Stored under the portal's {@code mesh.policy}
 * key as one compact JSON string ({@code {"s":..,"h":..,"t":..,"q":..,"c":["server|id32|w",..]}}),
 * which stays under 1 KiB for sixteen candidates.
 */
public record DestinationPolicy(List<DestinationCandidate> candidates, SelectionStrategy strategy, int minHeadroom, double minTps, boolean queue) {
    private static final String KEY_STRATEGY = "s";
    private static final String KEY_HEADROOM = "h";
    private static final String KEY_TPS = "t";
    private static final String KEY_QUEUE = "q";
    private static final String KEY_CANDIDATES = "c";
    private static final char SEPARATOR = '|';
    private static final char TAG_MARK = '#';
    private static final int MAX_CANDIDATES = 64;

    public DestinationPolicy {
        candidates = List.copyOf(candidates);
        if (strategy == null) {
            throw new IllegalArgumentException("strategy is required");
        }
        minHeadroom = Math.max(0, minHeadroom);
        minTps = Math.max(0.0D, Math.min(20.0D, minTps));
    }

    public String encode() {
        JSONObject json = new JSONObject();
        json.put(KEY_STRATEGY, strategy.name());
        json.put(KEY_HEADROOM, minHeadroom);
        json.put(KEY_TPS, minTps);
        json.put(KEY_QUEUE, queue);
        JSONArray list = new JSONArray();
        for (DestinationCandidate candidate : candidates) {
            String target = candidate.portalId() != null ? compactUuid(candidate.portalId()) : TAG_MARK + candidate.tag();
            list.put(candidate.server() + SEPARATOR + target + SEPARATOR + candidate.weight());
        }
        json.put(KEY_CANDIDATES, list);
        return json.toString();
    }

    /** Null for blank or malformed input. */
    public static DestinationPolicy decode(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            JSONObject json = new JSONObject(text);
            SelectionStrategy strategy = SelectionStrategy.valueOf(json.getString(KEY_STRATEGY).toUpperCase(Locale.ROOT));
            JSONArray list = json.optJSONArray(KEY_CANDIDATES);
            List<DestinationCandidate> candidates = new ArrayList<>();
            if (list != null) {
                if (list.length() > MAX_CANDIDATES) {
                    return null;
                }
                for (int index = 0; index < list.length(); index++) {
                    DestinationCandidate candidate = decodeCandidate(list.getString(index));
                    if (candidate == null) {
                        return null;
                    }
                    candidates.add(candidate);
                }
            }
            return new DestinationPolicy(candidates, strategy, json.optInt(KEY_HEADROOM, 0), json.optDouble(KEY_TPS, 0.0D), json.optBoolean(KEY_QUEUE, true));
        } catch (JSONException | IllegalArgumentException e) {
            return null;
        }
    }

    private static DestinationCandidate decodeCandidate(String text) {
        String[] parts = text.split("\\|");
        if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank()) {
            return null;
        }
        int weight;
        try {
            weight = Integer.parseInt(parts[2]);
        } catch (NumberFormatException e) {
            return null;
        }
        if (weight < 1) {
            return null;
        }
        if (parts[1].charAt(0) == TAG_MARK) {
            String tag = parts[1].substring(1);
            return tag.isBlank() ? null : new DestinationCandidate(parts[0], null, tag, weight);
        }
        UUID portalId = expandUuid(parts[1]);
        return portalId == null ? null : new DestinationCandidate(parts[0], portalId, null, weight);
    }

    private static String compactUuid(UUID id) {
        return id.toString().replace("-", "");
    }

    private static UUID expandUuid(String compact) {
        if (compact.length() != 32) {
            return null;
        }
        try {
            return new UUID(Long.parseUnsignedLong(compact.substring(0, 16), 16), Long.parseUnsignedLong(compact.substring(16), 16));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
