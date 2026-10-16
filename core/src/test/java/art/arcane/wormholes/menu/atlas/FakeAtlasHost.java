package art.arcane.wormholes.menu.atlas;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import art.arcane.wormholes.atlas.AtlasModel;
import art.arcane.wormholes.atlas.AtlasPlayerState;
import art.arcane.wormholes.config.toml.AtlasConfig;
import art.arcane.wormholes.menu.MenuViewer;

final class FakeAtlasHost implements AtlasMenuHost {
    static final UUID ZENITH = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    static final UUID BEACON = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    static final UUID COVE = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    final AtlasConfig settings = new AtlasConfig();
    final AtlasPlayerState state = new AtlasPlayerState(UUID.fromString("00000000-0000-0000-0000-0000000000b0"));
    final List<AtlasModel.Row> candidates = new ArrayList<>();
    final Map<UUID, AtlasPortalLink> links = new HashMap<>();
    final List<String> calls = new ArrayList<>();

    FakeAtlasHost() {
        candidates.add(new AtlasModel.Row(ZENITH, "Zenith", "world_nether", "", "QRST", 25.0D, false, false, true));
        candidates.add(new AtlasModel.Row(BEACON, "Beacon", "world", "Spire", "", 100.0D, true, false, true));
        candidates.add(new AtlasModel.Row(COVE, "Cove", "world", "Dock", "", 400.0D, true, false, false));
        links.put(ZENITH, AtlasPortalLink.NETWORKED);
        links.put(BEACON, AtlasPortalLink.STANDALONE);
        state.toggleFavorite(ZENITH, settings.favoritesLimit);
    }

    @Override
    public AtlasConfig settings() {
        return settings;
    }

    @Override
    public AtlasMenuView atlas(MenuViewer viewer) {
        return new AtlasMenuView(state, candidates);
    }

    @Override
    public AtlasPortalLink link(UUID portalId) {
        return links.getOrDefault(portalId, AtlasPortalLink.MISSING);
    }

    @Override
    public String portalName(UUID portalId) {
        for (AtlasModel.Row row : candidates) {
            if (row.portalId().equals(portalId)) {
                return row.name();
            }
        }
        return "";
    }

    @Override
    public AtlasPlayerState.FavoriteResult toggleFavorite(MenuViewer viewer, UUID portalId) {
        calls.add("favorite:" + portalName(portalId));
        return state.toggleFavorite(portalId, settings.favoritesLimit);
    }

    @Override
    public void setGuideTarget(MenuViewer viewer, UUID portalId) {
        calls.add("guide:" + portalName(portalId));
        state.setGuideTarget(portalId);
    }

    @Override
    public void clearGuide(MenuViewer viewer) {
        calls.add("guide-off");
        state.setGuideTarget(null);
    }
}
