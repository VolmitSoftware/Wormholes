package art.arcane.wormholes.door;

import java.util.Objects;
import java.util.UUID;

/**
 * One durable world mutation inside a pocket.
 *
 * <p>Every kind is written before the first block moves and cleared only after the result is
 * persisted, so a crash mid-operation is finished on the next start instead of leaving a half-built
 * room.</p>
 *
 * <p>A RESIZE changes the shell from {@code source} to {@code target}; a PASTE stamps
 * {@code templateName} into an unchanged room; a RESET wipes the interior and stamps it again.</p>
 */
public record PocketMutationIntent(
    Kind kind,
    UUID operationId,
    UUID spaceId,
    PocketShell source,
    PocketShell target,
    String templateName
) {
    /** No template involved: resizes carry this. */
    public static final String NO_TEMPLATE = "";

    public PocketMutationIntent {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(spaceId, "spaceId");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(templateName, "templateName");
        if (kind == Kind.RESIZE && source.equals(target)) {
            throw new IllegalArgumentException("resize source and target must differ");
        }
        if (kind != Kind.RESIZE && !source.equals(target)) {
            throw new IllegalArgumentException(kind + " must not reshape the room");
        }
    }

    static PocketMutationIntent resize(UUID operationId, UUID spaceId, PocketShell source, PocketShell target) {
        return new PocketMutationIntent(Kind.RESIZE, operationId, spaceId, source, target, NO_TEMPLATE);
    }

    static PocketMutationIntent paste(UUID operationId, UUID spaceId, PocketShell shell, String templateName) {
        return new PocketMutationIntent(Kind.PASTE, operationId, spaceId, shell, shell, templateName);
    }

    static PocketMutationIntent reset(UUID operationId, UUID spaceId, PocketShell shell, String templateName) {
        return new PocketMutationIntent(Kind.RESET, operationId, spaceId, shell, shell, templateName);
    }

    /**
     * The shell the operation started from, checked against the live pocket.
     *
     * @throws IllegalStateException when the pocket is at neither end of the interrupted operation
     */
    PocketSpace operationSource(PocketSpace current) {
        Objects.requireNonNull(current, "current");
        if (!spaceId.equals(current.spaceId())) {
            throw new IllegalArgumentException("mutation intent does not belong to pocket " + current.spaceId());
        }
        PocketShell currentShell = current.shell();
        if (!source.equals(currentShell) && !target.equals(currentShell)) {
            throw new IllegalStateException(
                "pocket " + spaceId + " is neither at the mutation source nor target shell");
        }
        return current.withShell(source);
    }

    public enum Kind {
        RESIZE,
        PASTE,
        RESET
    }
}
