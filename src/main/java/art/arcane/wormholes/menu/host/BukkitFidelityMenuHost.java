package art.arcane.wormholes.menu.host;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import art.arcane.wormholes.PortalManager;
import art.arcane.wormholes.ProjectionManager;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.fidelity.FidelityEdit;
import art.arcane.wormholes.menu.fidelity.FidelityMenuHost;
import art.arcane.wormholes.menu.fidelity.FidelityMenuView;
import art.arcane.wormholes.network.PortalSyncService;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.render.FidelityPortalExtension;
import art.arcane.wormholes.render.bedrock.ClientProfileService;

public final class BukkitFidelityMenuHost implements FidelityMenuHost {
    private final PortalManager portals;

    public BukkitFidelityMenuHost(PortalManager portals) {
        this.portals = Objects.requireNonNull(portals, "portals");
    }

    @Override
    public Optional<FidelityMenuView> fidelity(UUID portalId) {
        return extended(portalId).map(portal -> view(portal.extension(FidelityPortalExtension.class)));
    }

    @Override
    public int bedrockViewers() {
        return ClientProfileService.bedrockViewerCount();
    }

    @Override
    public void apply(MenuViewer viewer, UUID portalId, FidelityEdit edit) {
        Optional<LocalPortal> found = extended(portalId);
        if (found.isEmpty()) {
            return;
        }
        LocalPortal portal = found.get();
        FidelityPortalExtension fidelity = portal.extension(FidelityPortalExtension.class);
        switch (edit) {
            case FidelityEdit.Atmosphere atmosphere -> fidelity.setAtmosphereMode(atmosphere.mode());
            case FidelityEdit.Acoustics acoustics -> fidelity.setAcousticsProfile(acoustics.profile());
            case FidelityEdit.Lod lod -> fidelity.setLodProfile(lod.profile());
            case FidelityEdit.BlockEntities blockEntities -> fidelity.setBlockEntities(blockEntities.enabled());
        }
        portal.save();
        PortalSyncService<ILocalPortal> sync = Wormholes.portalSyncService;
        if (sync != null) {
            sync.broadcastSettings(portal);
        }
        ProjectionManager projection = Wormholes.projectionManager;
        if (projection != null) {
            projection.plateCache().invalidatePortal(portal.getId());
        }
        portal.invalidateMenus();
    }

    private Optional<LocalPortal> extended(UUID portalId) {
        return BukkitLocalPortals.local(portals, portalId)
            .filter(portal -> portal.extension(FidelityPortalExtension.class) != null);
    }

    private static FidelityMenuView view(FidelityPortalExtension fidelity) {
        return new FidelityMenuView(fidelity.atmosphereMode(), fidelity.acousticsProfile(), fidelity.lodProfile(), fidelity.blockEntities(),
            fidelity.effectiveAtmosphereMode(), fidelity.effectiveAcousticsProfile(), fidelity.effectiveLodProfile(), fidelity.effectiveBlockEntities());
    }
}
