package art.arcane.wormholes.menu.host;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.bukkit.entity.Player;

import art.arcane.wormholes.PortalManager;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.atlas.AtlasPlayerState;
import art.arcane.wormholes.atlas.AtlasService;
import art.arcane.wormholes.config.toml.AtlasConfig;
import art.arcane.wormholes.menu.BukkitMenuViewer;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.atlas.AtlasMenuHost;
import art.arcane.wormholes.menu.atlas.AtlasMenuView;
import art.arcane.wormholes.menu.atlas.AtlasPortalLink;
import art.arcane.wormholes.nexus.NexusPortalExtension;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.LocalPortal;

public final class BukkitAtlasMenuHost implements AtlasMenuHost {
    private final PortalManager portals;
    private final Supplier<AtlasService> atlas;

    public BukkitAtlasMenuHost(PortalManager portals, Supplier<AtlasService> atlas) {
        this.portals = Objects.requireNonNull(portals, "portals");
        this.atlas = Objects.requireNonNull(atlas, "atlas");
    }

    @Override
    public AtlasConfig settings() {
        return Wormholes.settings == null ? new AtlasConfig() : Wormholes.settings.getAtlas();
    }

    @Override
    public AtlasMenuView atlas(MenuViewer viewer) {
        Player player = BukkitMenuViewer.player(viewer);
        AtlasService service = service();
        return new AtlasMenuView(service.state(player), service.candidates(player));
    }

    @Override
    public AtlasPortalLink link(UUID portalId) {
        Optional<LocalPortal> found = BukkitLocalPortals.local(portals, portalId);
        if (found.isEmpty()) {
            return AtlasPortalLink.MISSING;
        }
        NexusPortalExtension state = found.get().extension(NexusPortalExtension.class);
        return state == null || state.networkId() == null ? AtlasPortalLink.STANDALONE : AtlasPortalLink.NETWORKED;
    }

    @Override
    public String portalName(UUID portalId) {
        ILocalPortal portal = portals.getLocalPortal(portalId);
        return portal == null ? "" : portal.getName();
    }

    @Override
    public AtlasPlayerState.FavoriteResult toggleFavorite(MenuViewer viewer, UUID portalId) {
        return service().toggleFavorite(BukkitMenuViewer.player(viewer), portalId);
    }

    @Override
    public void setGuideTarget(MenuViewer viewer, UUID portalId) {
        service().setGuideTarget(BukkitMenuViewer.player(viewer), portalId);
    }

    @Override
    public void clearGuide(MenuViewer viewer) {
        service().setGuideTarget(BukkitMenuViewer.player(viewer), null);
    }

    private AtlasService service() {
        return Objects.requireNonNull(atlas.get(), "the atlas service is not running");
    }
}
