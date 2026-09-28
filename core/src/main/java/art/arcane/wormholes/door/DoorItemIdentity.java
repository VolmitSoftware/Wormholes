package art.arcane.wormholes.door;

import java.util.Objects;
import java.util.UUID;

/**
 * Durable identity written to a dimensional-door item. Runtime state such as
 * placement or whether the physical door is open deliberately does not belong
 * here.
 */
public record DoorItemIdentity(
    UUID itemId,
    DoorKind kind,
    DoorForm form,
    UUID pairId,
    PairEndpoint pairEndpoint,
    UUID spaceId
) {
    public DoorItemIdentity {
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(form, "form");

        switch (kind) {
            case PAIR -> {
                Objects.requireNonNull(pairId, "paired doors require pairId");
                Objects.requireNonNull(pairEndpoint, "paired doors require pairEndpoint");
                requireNull(spaceId, "paired doors cannot carry spaceId");
            }
            case PERSONAL, PUBLIC -> {
                requireNull(pairId, kind + " doors cannot carry pairId");
                requireNull(pairEndpoint, kind + " doors cannot carry pairEndpoint");
                requireNull(spaceId, kind + " doors cannot carry spaceId");
            }
            case RETURN -> {
                requireNull(pairId, "return doors cannot carry pairId");
                requireNull(pairEndpoint, "return doors cannot carry pairEndpoint");
                Objects.requireNonNull(spaceId, "return doors require spaceId");
                // pocket exits are built by the pocket structure and are never trapdoors
                if (form != DoorForm.DOOR) {
                    throw new IllegalArgumentException("return doors are always DOOR form");
                }
            }
        }
    }

    /** Identities written before trapdoors existed are always {@link DoorForm#DOOR}. */
    public DoorItemIdentity(UUID itemId, DoorKind kind, UUID pairId, PairEndpoint pairEndpoint, UUID spaceId) {
        this(itemId, kind, DoorForm.DOOR, pairId, pairEndpoint, spaceId);
    }

    public static DoorItemIdentity paired(UUID itemId, UUID pairId, PairEndpoint endpoint) {
        return paired(itemId, pairId, endpoint, DoorForm.DOOR);
    }

    public static DoorItemIdentity paired(UUID itemId, UUID pairId, PairEndpoint endpoint, DoorForm form) {
        return new DoorItemIdentity(itemId, DoorKind.PAIR, form, pairId, endpoint, null);
    }

    public static DoorItemIdentity personal(UUID itemId) {
        return personal(itemId, DoorForm.DOOR);
    }

    public static DoorItemIdentity personal(UUID itemId, DoorForm form) {
        return new DoorItemIdentity(itemId, DoorKind.PERSONAL, form, null, null, null);
    }

    public static DoorItemIdentity publicDoor(UUID itemId) {
        return publicDoor(itemId, DoorForm.DOOR);
    }

    public static DoorItemIdentity publicDoor(UUID itemId, DoorForm form) {
        return new DoorItemIdentity(itemId, DoorKind.PUBLIC, form, null, null, null);
    }

    public static DoorItemIdentity returnDoor(UUID itemId, UUID spaceId) {
        return new DoorItemIdentity(itemId, DoorKind.RETURN, DoorForm.DOOR, null, null, spaceId);
    }

    public static DoorItemIdentity newPersonal() {
        return newPersonal(DoorForm.DOOR);
    }

    public static DoorItemIdentity newPersonal(DoorForm form) {
        return personal(UUID.randomUUID(), form);
    }

    public static DoorItemIdentity newPublic() {
        return newPublic(DoorForm.DOOR);
    }

    public static DoorItemIdentity newPublic(DoorForm form) {
        return publicDoor(UUID.randomUUID(), form);
    }

    public static DoorItemIdentity newReturn(UUID spaceId) {
        return returnDoor(UUID.randomUUID(), spaceId);
    }

    public boolean isTrapdoor() {
        return form == DoorForm.TRAPDOOR;
    }

    private static void requireNull(Object value, String message) {
        if (value != null) {
            throw new IllegalArgumentException(message);
        }
    }
}
