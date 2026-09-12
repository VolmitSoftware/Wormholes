package art.arcane.wormholes.door;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class DoorDestinationResolver {
    private DoorDestinationResolver() {
    }

    public static DoorDestination resolve(DoorItemIdentity door, UUID travelerId) {
        return resolve(door, travelerId, itemId -> Optional.empty());
    }

    /**
     * Resolves where one door leads for one traveler.
     *
     * <p>A public door whose pocket was built from an instanced template leads to that traveler's own
     * copy instead of the one room everybody shares.</p>
     *
     * @param instancedTemplate the template name a public door's pocket is instanced from, if any
     */
    public static DoorDestination resolve(
        DoorItemIdentity door,
        UUID travelerId,
        InstancedTemplateLookup instancedTemplate
    ) {
        Objects.requireNonNull(door, "door");
        Objects.requireNonNull(travelerId, "travelerId");
        Objects.requireNonNull(instancedTemplate, "instancedTemplate");

        return switch (door.kind()) {
            case PAIR -> new PairedDoorDestination(door.pairId(), door.pairEndpoint().other());
            case PERSONAL -> new PocketDoorDestination(PocketBinding.personal(travelerId));
            case PUBLIC -> instancedTemplate.templateOf(door.itemId())
                .map(template -> new PocketDoorDestination(
                    PocketInstances.bindingFor(template, travelerId), template))
                .orElseGet(() -> new PocketDoorDestination(PocketBinding.publicDoor(door.itemId())));
            case RETURN -> new ReturnDoorDestination(door.spaceId(), travelerId);
        };
    }

    /** The instanced template behind one public door, or empty when its pocket is shared. */
    @FunctionalInterface
    public interface InstancedTemplateLookup {
        Optional<String> templateOf(UUID doorItemId);
    }
}
