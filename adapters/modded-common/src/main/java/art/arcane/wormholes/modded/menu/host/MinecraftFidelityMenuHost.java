package art.arcane.wormholes.modded.menu.host;

import art.arcane.optics.fidelity.AcousticsProfile;
import art.arcane.optics.fidelity.AtmosphereMode;
import art.arcane.optics.volume.LodProfile;
import art.arcane.wormholes.menu.MenuScope;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.fidelity.FidelityEdit;
import art.arcane.wormholes.menu.fidelity.FidelityMenuHost;
import art.arcane.wormholes.menu.fidelity.FidelityMenuView;
import art.arcane.wormholes.modded.MinecraftClientProfiles;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.menu.MinecraftMenuViewer;
import art.arcane.wormholes.render.FidelitySettings;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

public final class MinecraftFidelityMenuHost implements FidelityMenuHost {
    private final WormholesModRuntime runtime;

    public MinecraftFidelityMenuHost(WormholesModRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    @Override
    public Optional<FidelityMenuView> fidelity(UUID portalId) {
        return Optional.ofNullable(runtime.portals().get(portalId)).map(MinecraftFidelityMenuHost::view);
    }

    @Override
    public int bedrockViewers() {
        return MinecraftClientProfiles.bedrockViewers();
    }

    @Override
    public void apply(MenuViewer viewer, UUID portalId, FidelityEdit edit) {
        Consumer<MinecraftPortal> change = switch (edit) {
            case FidelityEdit.Atmosphere atmosphere -> portal -> portal.setAtmosphereMode(atmosphere.mode());
            case FidelityEdit.Acoustics acoustics -> portal -> portal.setAcousticsProfile(acoustics.profile());
            case FidelityEdit.Lod lod -> portal -> portal.setLodProfile(lod.profile());
            case FidelityEdit.BlockEntities blockEntities -> portal -> portal.setBlockEntities(blockEntities.enabled());
        };
        if (runtime.portals().update(MinecraftMenuViewer.player(viewer), portalId, change)) {
            runtime.menus().invalidate(new MenuScope.Portal(portalId));
        }
    }

    private static FidelityMenuView view(MinecraftPortal portal) {
        AtmosphereMode atmosphere = portal.getAtmosphereMode();
        AcousticsProfile acoustics = portal.getAcousticsProfile();
        LodProfile lod = portal.getLodProfile();
        Boolean blockEntities = portal.getBlockEntities();
        return new FidelityMenuView(atmosphere, acoustics, lod, blockEntities,
            atmosphere == null ? FidelitySettings.atmosphereModeDefault : atmosphere,
            acoustics == null ? FidelitySettings.acousticsProfileDefault : acoustics,
            lod == null ? LodProfile.BALANCED : lod,
            blockEntities == null ? FidelitySettings.blockEntities : blockEntities.booleanValue());
    }
}
