package art.arcane.wormholes.nexus;

import art.arcane.volmlib.util.json.JSONObject;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * One candidate destination inside a {@link DestinationPolicy}.
 *
 * <p>{@code target} depends on {@code kind}: a portal UUID for {@code LOCAL},
 * {@code server/portalUuid} for {@code REMOTE}, and a network dial address for {@code ADDRESS}.
 * A window of {@code 0..0} means the entry is always open; a start above the end wraps past midnight.
 */
public record DestinationEntry(TargetKind kind, String target, int weight, int windowStartTick, int windowEndTick,
                               String label) {
    public static final int TICKS_PER_DAY = 24000;

    public DestinationEntry {
        kind = kind == null ? TargetKind.ADDRESS : kind;
        target = Objects.requireNonNull(target, "target").trim();
        weight = Math.max(1, weight);
        windowStartTick = clampTick(windowStartTick);
        windowEndTick = clampTick(windowEndTick);
        label = label == null ? "" : label;
    }

    public enum TargetKind {
        LOCAL,
        REMOTE,
        ADDRESS;

        public static TargetKind parse(String value, TargetKind fallback) {
            if (value == null || value.isBlank()) {
                return fallback;
            }
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException notAKind) {
                return fallback;
            }
        }
    }

    public boolean hasWindow() {
        return windowStartTick != windowEndTick;
    }

    public boolean windowContains(long worldTime) {
        if (!hasWindow()) {
            return true;
        }
        int time = (int) Math.floorMod(worldTime, (long) TICKS_PER_DAY);
        if (windowStartTick < windowEndTick) {
            return time >= windowStartTick && time < windowEndTick;
        }
        return time >= windowStartTick || time < windowEndTick;
    }

    public UUID localPortalId() {
        return kind == TargetKind.LOCAL ? parseUuid(target) : null;
    }

    public String remoteServer() {
        if (kind != TargetKind.REMOTE) {
            return null;
        }
        int separator = target.indexOf('/');
        return separator <= 0 ? null : target.substring(0, separator);
    }

    public UUID remotePortalId() {
        if (kind != TargetKind.REMOTE) {
            return null;
        }
        int separator = target.indexOf('/');
        return separator < 0 ? null : parseUuid(target.substring(separator + 1));
    }

    public static DestinationEntry remote(String serverName, UUID portalId, int weight, String label) {
        return new DestinationEntry(TargetKind.REMOTE, serverName + "/" + portalId, weight, 0, 0, label);
    }

    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put("kind", kind.name());
        json.put("target", target);
        json.put("weight", weight);
        if (hasWindow()) {
            json.put("from", windowStartTick);
            json.put("to", windowEndTick);
        }
        if (!label.isEmpty()) {
            json.put("label", label);
        }
        return json;
    }

    public static DestinationEntry fromJSON(JSONObject json) {
        if (json == null) {
            return null;
        }
        String target = json.optString("target", "");
        if (target.isBlank()) {
            return null;
        }
        return new DestinationEntry(TargetKind.parse(json.optString("kind", ""), TargetKind.ADDRESS), target,
                json.optInt("weight", 1), json.optInt("from", 0), json.optInt("to", 0), json.optString("label", ""));
    }

    private static int clampTick(int tick) {
        return Math.max(0, Math.min(TICKS_PER_DAY, tick));
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException | NullPointerException notAUuid) {
            return null;
        }
    }
}
