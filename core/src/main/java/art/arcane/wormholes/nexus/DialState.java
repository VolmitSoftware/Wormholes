package art.arcane.wormholes.nexus;

import java.util.Map;
import java.util.LinkedHashMap;

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

    public Map<String, Object> toMap() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("address", currentAddress);
        json.put("at", dialedAtMillis);
        if (dialedBy != null) {
            json.put("by", dialedBy.toString());
        }
        json.put("sticky", sticky);
        return json;
    }

    public static DialState fromMap(Map<String, Object> json) {
        if (json == null) {
            return IDLE;
        }
        UUID dialedBy = null;
        String encodedDialer = NexusValues.string(json, "by", "");
        if (!encodedDialer.isBlank()) {
            try {
                dialedBy = UUID.fromString(encodedDialer.trim());
            } catch (IllegalArgumentException notAUuid) {
                dialedBy = null;
            }
        }
        return new DialState(NexusValues.string(json, "address", ""), NexusValues.number(json, "at", 0L), dialedBy,
                NexusValues.bool(json, "sticky", false));
    }
}
