package art.arcane.wormholes.door.view;

public final class DoorProjectionProfile {
    /** Lateral padding around a one-block-wide aperture, in blocks. */
    public static final int VIEW_LATERAL_PAD = 4;
    public static final int VIEW_HEARTBEAT_TICKS = 60;
    public static final int VIEW_ENTITY_INTERVAL_TICKS = 10;
    public static final int VIEW_UNSUBSCRIBE_GRACE_SECONDS = 30;
    public static final String VIEW_FALLBACK_BLOCK = "minecraft:air";

    private DoorProjectionProfile() {
    }
}
