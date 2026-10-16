package art.arcane.wormholes.menu.fidelity;

import art.arcane.optics.fidelity.AcousticsProfile;
import art.arcane.optics.fidelity.AtmosphereMode;
import art.arcane.optics.volume.LodProfile;

public sealed interface FidelityEdit permits FidelityEdit.Atmosphere, FidelityEdit.Acoustics, FidelityEdit.Lod, FidelityEdit.BlockEntities {
    record Atmosphere(AtmosphereMode mode) implements FidelityEdit {
    }

    record Acoustics(AcousticsProfile profile) implements FidelityEdit {
    }

    record Lod(LodProfile profile) implements FidelityEdit {
    }

    record BlockEntities(Boolean enabled) implements FidelityEdit {
    }
}
