package art.arcane.optics.plate;

import java.util.Objects;
import java.util.UUID;

/**
 * Identity of a shared view plate: the projecting portal, the destination view it was sampled from,
 * which face of the portal the observers stand on, the mirror rotation in force and the destination
 * target the view was resolved to (zero for linked and mirror portals).
 */
public record ViewPlateKey(UUID portalId, Object destinationViewIdentity, boolean frontSide, int mirrorQuarterTurns,
                           long targetIdentity) {
    public ViewPlateKey {
        Objects.requireNonNull(portalId, "portalId");
        Objects.requireNonNull(destinationViewIdentity, "destinationViewIdentity");
    }
}
