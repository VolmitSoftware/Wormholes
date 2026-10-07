package art.arcane.optics.scan;

public final class ResampleReasons {
    public static final int INVALIDATED = 1;
    public static final int DEST_STALE_RECURSIVE = 1 << 1;
    public static final int DEST_STALE_DIRTY = 1 << 2;
    public static final int DEST_OVER_BUDGET = 1 << 3;
    public static final int LOCAL_DIRTY = 1 << 4;
    public static final int STABLE_CADENCE = 1 << 5;
    public static final int PRESENTATION = 1 << 6;
    public static final int REMOTE_PENDING = 1 << 7;
    public static final int LIGHTING = 1 << 8;
    public static final int UNRESOLVED_OCCLUSION = 1 << 9;
    public static final int CAMERA = 1 << 10;
    public static final int FULL_SEND = 1 << 11;
    public static final int HOLDS_EXPOSED = 1 << 12;
    public static final int REASON_COUNT = 13;

    private static final String[] NAMES = {
        "invalidated", "destStaleRecursive", "destStaleDirty", "destOverBudget", "localDirty", "stableCadence",
        "presentation", "remotePending", "lighting", "unresolvedOcclusion", "camera", "fullSend", "holdsExposed"
    };

    private final int[] counts = new int[REASON_COUNT];
    private int mask;
    private int other;

    public void record(int reasons) {
        mask |= reasons;
        if (reasons == 0) {
            other++;
            return;
        }
        for (int bit = 0; bit < REASON_COUNT; bit++) {
            if ((reasons & (1 << bit)) != 0) {
                counts[bit]++;
            }
        }
    }

    public int mask() {
        return mask;
    }

    public void reset() {
        mask = 0;
        other = 0;
        for (int bit = 0; bit < REASON_COUNT; bit++) {
            counts[bit] = 0;
        }
    }

    public String describe() {
        StringBuilder builder = new StringBuilder(64);
        for (int bit = 0; bit < REASON_COUNT; bit++) {
            if (counts[bit] > 0) {
                appendEntry(builder, NAMES[bit], counts[bit]);
            }
        }
        if (other > 0) {
            appendEntry(builder, "other", other);
        }
        return builder.isEmpty() ? "none" : builder.toString();
    }

    private static void appendEntry(StringBuilder builder, String name, int count) {
        if (!builder.isEmpty()) {
            builder.append(',');
        }
        builder.append(name).append(':').append(count);
    }
}
