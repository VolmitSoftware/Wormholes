package art.arcane.wormholes.door;

import java.util.Objects;
import java.util.UUID;

/** Immutable allocation of one logical pocket within the shared void world. */
public record PocketSpace(
    UUID spaceId,
    PocketBinding binding,
    long slot,
    int centerX,
    int centerY,
    int centerZ,
    PocketShell shell
) {
    public PocketSpace {
        Objects.requireNonNull(spaceId, "spaceId");
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(shell, "shell");
        if (slot < 0) {
            throw new IllegalArgumentException("slot cannot be negative");
        }
    }

    public PocketSpace withShell(PocketShell updated) {
        Objects.requireNonNull(updated, "updated");
        return updated.equals(shell)
            ? this
            : new PocketSpace(spaceId, binding, slot, centerX, centerY, centerZ, updated);
    }
}
