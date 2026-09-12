package art.arcane.wormholes.nexus;

import art.arcane.volmlib.util.json.JSONObject;

import java.util.UUID;

/**
 * What a portal is currently dialed to. {@code sticky} keeps a manual dial past
 * {@code nexus.dial-hold-seconds}; otherwise the scheduler reverts to the portal's own address.
 */
public record DialState(String currentAddress, long dialedAtMillis, UUID dialedBy, boolean sticky) {
    private static final DialState IDLE = new DialState("", 0L, null, false);

    public DialState {
        currentAddress = currentAddress == null ? "" : NetworkMember.normalizeAddress(currentAddress);
    }

    public static DialState idle() {
        return IDLE;
    }

    public boolean isDialed() {
        return !currentAddress.isEmpty();
    }

    public boolean withinDebounce(long nowMillis, long debounceMillis) {
        return dialedAtMillis > 0L && nowMillis - dialedAtMillis < debounceMillis;
    }

    public boolean heldPast(long nowMillis, long holdMillis) {
        return !sticky && isDialed() && holdMillis > 0L && dialedAtMillis > 0L && nowMillis - dialedAtMillis >= holdMillis;
    }

    public DialState withSticky(boolean newSticky) {
        return new DialState(currentAddress, dialedAtMillis, dialedBy, newSticky);
    }

    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put("address", currentAddress);
        json.put("at", dialedAtMillis);
        if (dialedBy != null) {
            json.put("by", dialedBy.toString());
        }
        json.put("sticky", sticky);
        return json;
    }

    public static DialState fromJSON(JSONObject json) {
        if (json == null) {
            return IDLE;
        }
        UUID dialedBy = null;
        String encodedDialer = json.optString("by", "");
        if (!encodedDialer.isBlank()) {
            try {
                dialedBy = UUID.fromString(encodedDialer.trim());
            } catch (IllegalArgumentException notAUuid) {
                dialedBy = null;
            }
        }
        return new DialState(json.optString("address", ""), json.optLong("at", 0L), dialedBy,
                json.optBoolean("sticky", false));
    }
}
