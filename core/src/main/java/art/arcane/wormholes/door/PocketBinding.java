package art.arcane.wormholes.door;

import java.util.Objects;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Stable lookup key for a pocket. PERSONAL keys are player UUIDs; public-door keys
 * are immutable door-item UUIDs.
 */
public record PocketBinding(PocketBindingKind kind, UUID bindingId) {
    public PocketBinding {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(bindingId, "bindingId");
    }

    public static PocketBinding personal(UUID travelerId) {
        return new PocketBinding(PocketBindingKind.PERSONAL, travelerId);
    }

    public static PocketBinding publicDoor(UUID doorItemId) {
        return new PocketBinding(PocketBindingKind.IRON, doorItemId);
    }
    public static PocketBinding instance(String templateName, UUID travelerId) {
        Objects.requireNonNull(templateName, "templateName");
        Objects.requireNonNull(travelerId, "travelerId");
        String seed = "wormholes:pocket-instance:v1:" + templateName + ':' + travelerId;
        return personal(UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)));
    }
}
