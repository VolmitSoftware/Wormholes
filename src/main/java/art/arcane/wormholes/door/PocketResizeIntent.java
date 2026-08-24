package art.arcane.wormholes.door;

import java.util.Objects;
import java.util.UUID;

record PocketResizeIntent(
    UUID operationId,
    UUID spaceId,
    PocketShell source,
    PocketShell target
) {
    PocketResizeIntent {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(spaceId, "spaceId");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        if (source.equals(target)) {
            throw new IllegalArgumentException("resize source and target must differ");
        }
    }

    PocketSpace operationSource(PocketSpace current) {
        Objects.requireNonNull(current, "current");
        if (!spaceId.equals(current.spaceId())) {
            throw new IllegalArgumentException("resize intent does not belong to pocket " + current.spaceId());
        }
        PocketShell currentShell = current.shell();
        if (!source.equals(currentShell) && !target.equals(currentShell)) {
            throw new IllegalStateException(
                "pocket " + spaceId + " is neither at the resize source nor target shell");
        }
        return current.withShell(source);
    }
}
