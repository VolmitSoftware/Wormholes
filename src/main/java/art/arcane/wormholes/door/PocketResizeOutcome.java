package art.arcane.wormholes.door;

import java.util.Objects;

/** Result of one attempt to reshape a pocket, delivered once the region work finishes. */
public record PocketResizeOutcome(
    Status status,
    PocketSpace space,
    PocketShell target,
    PocketResizeService.Impact impact
) {
    public PocketResizeOutcome {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(space, "space");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(impact, "impact");
    }

    public static PocketResizeOutcome resized(PocketSpace space, PocketShell target, PocketResizeService.Impact impact) {
        return new PocketResizeOutcome(Status.RESIZED, space, target, impact);
    }

    public static PocketResizeOutcome needsConfirmation(
        PocketSpace space,
        PocketShell target,
        PocketResizeService.Impact impact
    ) {
        return new PocketResizeOutcome(Status.NEEDS_CONFIRMATION, space, target, impact);
    }

    public static PocketResizeOutcome unchanged(PocketSpace space, PocketShell target) {
        return new PocketResizeOutcome(Status.UNCHANGED, space, target, PocketResizeService.Impact.none());
    }

    public static PocketResizeOutcome of(Status status, PocketSpace space, PocketShell target) {
        return new PocketResizeOutcome(status, space, target, PocketResizeService.Impact.none());
    }

    public boolean succeeded() {
        return status == Status.RESIZED;
    }

    public enum Status {
        /** The room was rebuilt and the new shell persisted. */
        RESIZED,
        /** The reshape would destroy or displace something and was not confirmed. */
        NEEDS_CONFIRMATION,
        /** The pocket already has exactly this shell. */
        UNCHANGED,
        /** The pocket dimension is not loaded. */
        WORLD_UNAVAILABLE,
        /** The requested room does not fit the pocket dimension's build height. */
        DOES_NOT_FIT,
        /** Chunk loading, region dispatch, or persistence failed; the log has the detail. */
        FAILED
    }
}
