package art.arcane.wormholes.modded.menu.host;

import art.arcane.wormholes.atlas.AtlasPlayerState;
import art.arcane.wormholes.config.toml.AtlasConfig;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.atlas.AtlasMenuHost;
import art.arcane.wormholes.menu.atlas.AtlasMenuView;
import art.arcane.wormholes.menu.atlas.AtlasPortalLink;
import art.arcane.wormholes.modded.MinecraftAtlasService;
import art.arcane.wormholes.modded.MinecraftNexusValues;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.menu.MinecraftMenuViewer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Objects;
import java.util.UUID;

public final class MinecraftAtlasMenuHost implements AtlasMenuHost {
    private final WormholesModRuntime runtime;

    public MinecraftAtlasMenuHost(WormholesModRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    @Override
    public AtlasConfig settings() {
        return runtime.configuration().settings().getAtlas();
    }

    @Override
    public AtlasMenuView atlas(MenuViewer viewer) {
        ServerPlayer player = MinecraftMenuViewer.player(viewer);
        MinecraftAtlasService atlas = runtime.atlas();
        return new AtlasMenuView(state(atlas, player), atlas.candidates(player));
    }

    @Override
    public AtlasPortalLink link(UUID portalId) {
        MinecraftPortal portal = runtime.portals().get(portalId);
        if (portal == null) {
            return AtlasPortalLink.MISSING;
        }
        return MinecraftNexusValues.networkId(portal) == null ? AtlasPortalLink.STANDALONE : AtlasPortalLink.NETWORKED;
    }

    @Override
    public String portalName(UUID portalId) {
        MinecraftPortal portal = runtime.portals().get(portalId);
        return portal == null ? "" : portal.getName();
    }

    @Override
    public AtlasPlayerState.FavoriteResult toggleFavorite(MenuViewer viewer, UUID portalId) {
        MinecraftAtlasService atlas = runtime.atlas();
        return atlas.toggleFavorite(state(atlas, MinecraftMenuViewer.player(viewer)), portalId);
    }

    @Override
    public void setGuideTarget(MenuViewer viewer, UUID portalId) {
        guide(MinecraftMenuViewer.player(viewer), portalId);
    }

    @Override
    public void clearGuide(MenuViewer viewer) {
        guide(MinecraftMenuViewer.player(viewer), null);
    }

    private void guide(ServerPlayer player, UUID portalId) {
        MinecraftAtlasService atlas = runtime.atlas();
        atlas.setGuideTarget(player, state(atlas, player), portalId);
    }

    private static AtlasPlayerState state(MinecraftAtlasService atlas, ServerPlayer player) {
        return Objects.requireNonNull(atlas.state(player), "the atlas state of " + player.getUUID() + " is not loaded");
    }
}
