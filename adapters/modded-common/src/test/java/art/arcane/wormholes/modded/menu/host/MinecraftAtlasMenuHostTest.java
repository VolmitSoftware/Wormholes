package art.arcane.wormholes.modded.menu.host;

import art.arcane.wormholes.atlas.AtlasModel;
import art.arcane.wormholes.atlas.AtlasPlayerState;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.atlas.AtlasMenuView;
import art.arcane.wormholes.menu.atlas.AtlasPortalLink;
import art.arcane.wormholes.modded.MinecraftAtlasService;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.MinecraftPortalRegistry;
import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.menu.MinecraftMenuViewer;
import net.minecraft.server.level.ServerPlayer;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MinecraftAtlasMenuHostTest extends MinecraftTestBase {
    private static final UUID STANDALONE = UUID.fromString("a7a50000-0000-4000-8000-0000000000aa");
    private static final UUID NETWORKED = UUID.fromString("a7a50000-0000-4000-8000-0000000000ab");

    private MinecraftAtlasService atlas;
    private ServerPlayer player;
    private AtlasPlayerState state;
    private MenuViewer viewer;
    private MinecraftAtlasMenuHost host;

    @Before
    public void setUp() {
        WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        MinecraftPortalRegistry portals = mock(MinecraftPortalRegistry.class);
        atlas = mock(MinecraftAtlasService.class);
        player = mock(ServerPlayer.class);
        MinecraftPortal standalone = mock(MinecraftPortal.class);
        MinecraftPortal networked = mock(MinecraftPortal.class);
        when(standalone.getName()).thenReturn("Garden");
        when(networked.setting("nexus.networkId")).thenReturn(UUID.randomUUID().toString());
        when(runtime.portals()).thenReturn(portals);
        when(runtime.atlas()).thenReturn(atlas);
        when(portals.get(STANDALONE)).thenReturn(standalone);
        when(portals.get(NETWORKED)).thenReturn(networked);
        when(player.getUUID()).thenReturn(UUID.randomUUID());
        state = new AtlasPlayerState(UUID.randomUUID());
        when(atlas.state(player)).thenReturn(state);
        viewer = new MinecraftMenuViewer(runtime, player);
        host = new MinecraftAtlasMenuHost(runtime);
    }

    @Test
    public void theViewIsTheLoadedStateWithTheDepartableRows() {
        AtlasModel.Row row = new AtlasModel.Row(STANDALONE, "Garden", "minecraft:overworld", "", "", 1.0D, true, false, true);
        when(atlas.candidates(player)).thenReturn(List.of(row));

        AtlasMenuView view = host.atlas(viewer);

        assertSame(state, view.state());
        assertEquals(List.of(row), view.candidates());
    }

    @Test
    public void networkedPortalsDialAndOthersSummarise() {
        assertEquals(AtlasPortalLink.STANDALONE, host.link(STANDALONE));
        assertEquals(AtlasPortalLink.NETWORKED, host.link(NETWORKED));
        assertEquals(AtlasPortalLink.MISSING, host.link(UUID.randomUUID()));
        assertEquals("Garden", host.portalName(STANDALONE));
        assertEquals("", host.portalName(UUID.randomUUID()));
    }

    @Test
    public void favouritesAndTheGuideUseTheLoadedState() {
        when(atlas.toggleFavorite(state, STANDALONE)).thenReturn(AtlasPlayerState.FavoriteResult.ADDED);

        assertEquals(AtlasPlayerState.FavoriteResult.ADDED, host.toggleFavorite(viewer, STANDALONE));
        host.setGuideTarget(viewer, STANDALONE);
        host.clearGuide(viewer);

        verify(atlas).setGuideTarget(player, state, STANDALONE);
        verify(atlas).setGuideTarget(player, state, null);
    }

    @Test
    public void anUnloadedAtlasRefusesToBuild() {
        when(atlas.state(player)).thenReturn(null);

        assertThrows(NullPointerException.class, () -> host.atlas(viewer));
    }
}
