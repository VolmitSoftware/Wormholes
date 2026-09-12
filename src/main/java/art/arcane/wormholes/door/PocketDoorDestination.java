package art.arcane.wormholes.door;

import java.util.Objects;

/**
 * A door that leads into a pocket.
 *
 * <p>{@code instancedTemplate} is set only when the binding is one traveler's own copy of an
 * instanced template, which is what tells the provisioning pass to stamp instance bookkeeping onto a
 * brand new pocket.</p>
 */
public record PocketDoorDestination(PocketBinding binding, String instancedTemplate) implements DoorDestination {
    /** A shared pocket: no instance bookkeeping. */
    public static final String SHARED = "";

    public PocketDoorDestination {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(instancedTemplate, "instancedTemplate");
    }

    public PocketDoorDestination(PocketBinding binding) {
        this(binding, SHARED);
    }

    public boolean isInstanced() {
        return !instancedTemplate.isEmpty();
    }
}
