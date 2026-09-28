package art.arcane.wormholes.nexus;

import java.util.Map;
import java.util.LinkedHashMap;

import java.util.Locale;

/**
 * Redstone wiring for one portal: the control block offset from the portal centre, what a rising
 * edge there does, and what an adjacent comparator reads.
 */
public record FrameIo(int offsetX, int offsetY, int offsetZ, RedstoneAction action, ComparatorOutput comparator) {
    private static final FrameIo NONE = new FrameIo(0, 0, 0, RedstoneAction.NONE, ComparatorOutput.NONE);
    private static final int MAX_OFFSET = 16;

    public FrameIo {
        offsetX = clamp(offsetX);
        offsetY = clamp(offsetY);
        offsetZ = clamp(offsetZ);
        action = action == null ? RedstoneAction.NONE : action;
        comparator = comparator == null ? ComparatorOutput.NONE : comparator;
    }

    public enum RedstoneAction {
        NONE,
        OPEN,
        CLOSE,
        LOCK,
        DIAL_NEXT,
        DIAL_PREV;

        public static RedstoneAction parse(String value, RedstoneAction fallback) {
            if (value == null || value.isBlank()) {
                return fallback;
            }
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException notAnAction) {
                return fallback;
            }
        }

        public RedstoneAction next() {
            RedstoneAction[] values = values();
            return values[(ordinal() + 1) % values.length];
        }
    }

    public enum ComparatorOutput {
        NONE,
        STATE,
        TRAVERSALS;

        public static ComparatorOutput parse(String value, ComparatorOutput fallback) {
            if (value == null || value.isBlank()) {
                return fallback;
            }
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException notAnOutput) {
                return fallback;
            }
        }

        public ComparatorOutput next() {
            ComparatorOutput[] values = values();
            return values[(ordinal() + 1) % values.length];
        }
    }

    public static FrameIo none() {
        return NONE;
    }

    public boolean isWired() {
        return action != RedstoneAction.NONE || comparator != ComparatorOutput.NONE;
    }

    public FrameIo withOffset(int x, int y, int z) {
        return new FrameIo(x, y, z, action, comparator);
    }

    public FrameIo withAction(RedstoneAction newAction) {
        return new FrameIo(offsetX, offsetY, offsetZ, newAction, comparator);
    }

    public FrameIo withComparator(ComparatorOutput newComparator) {
        return new FrameIo(offsetX, offsetY, offsetZ, action, newComparator);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("x", offsetX);
        json.put("y", offsetY);
        json.put("z", offsetZ);
        json.put("action", action.name());
        json.put("comparator", comparator.name());
        return json;
    }

    public static FrameIo fromMap(Map<String, Object> json) {
        if (json == null) {
            return NONE;
        }
        return new FrameIo(NexusValues.integer(json, "x", 0), NexusValues.integer(json, "y", 0), NexusValues.integer(json, "z", 0),
                RedstoneAction.parse(NexusValues.string(json, "action", ""), RedstoneAction.NONE),
                ComparatorOutput.parse(NexusValues.string(json, "comparator", ""), ComparatorOutput.NONE));
    }

    private static int clamp(int offset) {
        return Math.max(-MAX_OFFSET, Math.min(MAX_OFFSET, offset));
    }
}
