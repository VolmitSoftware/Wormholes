package art.arcane.wormholes.menu.fidelity;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import art.arcane.optics.fidelity.AcousticsProfile;
import art.arcane.optics.fidelity.AtmosphereMode;
import art.arcane.optics.volume.LodProfile;
import art.arcane.wormholes.menu.FakePortalAccess;
import art.arcane.wormholes.menu.MenuViewer;

final class FakeFidelityMenuHost implements FidelityMenuHost {
    final List<String> calls = new ArrayList<>();
    final List<FidelityEdit> edits = new ArrayList<>();
    boolean present = true;
    int bedrockViewers;
    AtmosphereMode atmosphere;
    AcousticsProfile acoustics;
    LodProfile lod;
    Boolean blockEntities;

    @Override
    public Optional<FidelityMenuView> fidelity(UUID portalId) {
        if (!present || !portalId.equals(FakePortalAccess.PORTAL)) {
            return Optional.empty();
        }
        return Optional.of(new FidelityMenuView(atmosphere, acoustics, lod, blockEntities,
            atmosphere == null ? AtmosphereMode.OFF : atmosphere,
            acoustics == null ? AcousticsProfile.AMBIENT : acoustics,
            lod == null ? LodProfile.BALANCED : lod,
            blockEntities == null || blockEntities.booleanValue()));
    }

    @Override
    public int bedrockViewers() {
        return bedrockViewers;
    }

    @Override
    public void apply(MenuViewer viewer, UUID portalId, FidelityEdit edit) {
        calls.add("apply");
        edits.add(edit);
        switch (edit) {
            case FidelityEdit.Atmosphere change -> atmosphere = change.mode();
            case FidelityEdit.Acoustics change -> acoustics = change.profile();
            case FidelityEdit.Lod change -> lod = change.profile();
            case FidelityEdit.BlockEntities change -> blockEntities = change.enabled();
        }
    }
}
