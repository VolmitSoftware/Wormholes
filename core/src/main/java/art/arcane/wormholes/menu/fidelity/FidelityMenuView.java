package art.arcane.wormholes.menu.fidelity;

import java.util.Objects;

import art.arcane.optics.fidelity.AcousticsProfile;
import art.arcane.optics.fidelity.AtmosphereMode;
import art.arcane.optics.volume.LodProfile;

public record FidelityMenuView(
    AtmosphereMode atmosphere,
    AcousticsProfile acoustics,
    LodProfile lod,
    Boolean blockEntities,
    AtmosphereMode effectiveAtmosphere,
    AcousticsProfile effectiveAcoustics,
    LodProfile effectiveLod,
    boolean effectiveBlockEntities
) {
    public FidelityMenuView {
        Objects.requireNonNull(effectiveAtmosphere, "effectiveAtmosphere");
        Objects.requireNonNull(effectiveAcoustics, "effectiveAcoustics");
        Objects.requireNonNull(effectiveLod, "effectiveLod");
    }
}
