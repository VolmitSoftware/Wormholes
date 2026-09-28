package art.arcane.wormholes.door;

import java.util.Objects;

/**
 * Bookkeeping for one live copy of an instanced template.
 *
 * <p>{@code resetPolicy} is the pocket's own copy of {@code [pockets] instance-reset} taken when the
 * instance was created, so changing the config never retunes instances that are already running.</p>
 *
 * <p>{@code lastOccupiedMillis} is stamped when a traveler enters and {@code lastResetMillis} when the
 * room is wiped back to its template. An instance is due for a reset only while the first is newer than
 * the second, which is what stops an idle room being cleared and re-stamped on every sweep forever.</p>
 */
public record PocketInstanceInfo(
    String templateName,
    PocketBinding ownerBinding,
    long createdAtMillis,
    String resetPolicy,
    long lastOccupiedMillis,
    long lastResetMillis
) {
    public PocketInstanceInfo {
        Objects.requireNonNull(templateName, "templateName");
        Objects.requireNonNull(ownerBinding, "ownerBinding");
        Objects.requireNonNull(resetPolicy, "resetPolicy");
        if (createdAtMillis < 0L) {
            throw new IllegalArgumentException("createdAtMillis cannot be negative");
        }
        if (lastOccupiedMillis < 0L) {
            throw new IllegalArgumentException("lastOccupiedMillis cannot be negative");
        }
        if (lastResetMillis < 0L) {
            throw new IllegalArgumentException("lastResetMillis cannot be negative");
        }
    }

    /** An instance that has never been reset since it was stamped. */
    public PocketInstanceInfo(String templateName, PocketBinding ownerBinding, long createdAtMillis,
                              String resetPolicy, long lastOccupiedMillis) {
        this(templateName, ownerBinding, createdAtMillis, resetPolicy, lastOccupiedMillis, 0L);
    }

    public PocketInstanceInfo withLastOccupied(long millis) {
        return millis == lastOccupiedMillis
            ? this
            : new PocketInstanceInfo(templateName, ownerBinding, createdAtMillis, resetPolicy, millis, lastResetMillis);
    }

    public PocketInstanceInfo withLastReset(long millis) {
        return millis == lastResetMillis
            ? this
            : new PocketInstanceInfo(templateName, ownerBinding, createdAtMillis, resetPolicy, lastOccupiedMillis, millis);
    }

    /** Whether anybody has been inside since the room was last wiped back to its template. */
    public boolean usedSinceReset() {
        return lastOccupiedMillis > lastResetMillis;
    }
}
