package art.arcane.wormholes.door;

/**
 * Per-door override for the through-door view.
 *
 * <p>{@code [doors] projection-enabled} is the master switch: while it is off no door projects at
 * all, whatever this says, so existing worlds behave exactly as they did before projection existed.
 * With the flag on, {@link #INHERIT} follows it and {@link #OFF} opts a single door back out.</p>
 */
public enum DoorProjectionState {
    INHERIT,
    ON,
    OFF;

    public boolean projects(boolean globalEnabled) {
        return globalEnabled && this != OFF;
    }

    /** The next state in the menu cycle: inherit, on, off, inherit. */
    public DoorProjectionState next() {
        return switch (this) {
            case INHERIT -> ON;
            case ON -> OFF;
            case OFF -> INHERIT;
        };
    }
}
