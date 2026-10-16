package art.arcane.wormholes.menu;

public final class MenuLimits {
    public static final int MAX_STRING_BYTES = 1024;
    public static final int MAX_ELEMENTS = 256;
    public static final int MAX_DESCRIPTION_LINES = 16;
    public static final int MAX_CHEST_HINT_LINES = 16;
    public static final int MAX_PROMPT_LINES = 16;
    public static final int MAX_CYCLE_OPTIONS = 64;
    public static final int MAX_SIDE_ACTIONS = 3;
    public static final int MAX_CONFIRMATION_LINES = 16;
    public static final int MAX_TEXT_INPUT_LENGTH = 256;
    public static final int MAX_ITEM_COUNT = 99;
    public static final int MAX_CHEST_ROW = 26;
    public static final int MAX_CHEST_ROWS = 6;
    public static final int MAX_C2S_BYTES = 32767;
    public static final int MAX_S2C_BYTES = 1048576;
    public static final int MAX_FRAMES_PER_SECOND = 60;
    public static final int VIOLATION_LIMIT = 3;
    public static final long VIOLATION_WINDOW_MILLIS = 10_000L;
    public static final long PENDING_TIMEOUT_MILLIS = 10_000L;
    public static final int REVOCATION_INTERVAL_TICKS = 20;

    private MenuLimits() {
    }
}
