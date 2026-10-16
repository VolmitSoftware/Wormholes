package art.arcane.wormholes.menu.atlas;

import java.util.UUID;

import art.arcane.wormholes.atlas.AtlasPlayerState;
import art.arcane.wormholes.config.toml.AtlasConfig;
import art.arcane.wormholes.menu.MenuViewer;

public interface AtlasMenuHost {
    AtlasConfig settings();

    AtlasMenuView atlas(MenuViewer viewer);

    AtlasPortalLink link(UUID portalId);

    String portalName(UUID portalId);

    AtlasPlayerState.FavoriteResult toggleFavorite(MenuViewer viewer, UUID portalId);

    void setGuideTarget(MenuViewer viewer, UUID portalId);

    void clearGuide(MenuViewer viewer);
}
