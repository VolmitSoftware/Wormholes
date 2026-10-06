package art.arcane.optics.entity;

import java.util.List;
import java.util.UUID;

/** Vehicle, passenger and leash links of one projected entity, source ids as seen on the sending side. */
public record EntityRelationship(UUID entityId, UUID vehicleId, List<UUID> passengerIds, UUID leashHolderId) {
}
