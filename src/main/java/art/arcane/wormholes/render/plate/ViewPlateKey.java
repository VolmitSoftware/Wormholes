package art.arcane.wormholes.render.plate;

import java.util.Objects;
import java.util.UUID;

/**
 * Identity of a shared view plate: the projecting portal, the destination view it was sampled from,
 * which face of the portal the observers stand on and the mirror rotation in force.
 */
public record ViewPlateKey(UUID portalId, Object destinationViewIdentity, boolean frontSide, int mirrorQuarterTurns) {
    public ViewPlateKey {
        Objects.requireNonNull(portalId, "portalId");
        Objects.requireNonNull(destinationViewIdentity, "destinationViewIdentity");
    }
}
