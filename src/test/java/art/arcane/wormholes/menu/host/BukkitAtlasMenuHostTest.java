package art.arcane.wormholes.menu.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.PortalManager;
import art.arcane.wormholes.atlas.AtlasModel;
import art.arcane.wormholes.atlas.AtlasPlayerState;
import art.arcane.wormholes.atlas.AtlasService;
import art.arcane.wormholes.menu.BukkitMenuViewer;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.atlas.AtlasMenuView;
import art.arcane.wormholes.menu.atlas.AtlasPortalLink;
import art.arcane.wormholes.nexus.NexusPortalExtension;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.LocalPortal;

class BukkitAtlasMenuHostTest {
    private final UUID standaloneId = UUID.randomUUID();
    private final UUID networkedId = UUID.randomUUID();
    private final UUID remoteId = UUID.randomUUID();
    private PortalManager portals;
    private AtlasService service;
    private Player player;
    private MenuViewer viewer;
    private BukkitAtlasMenuHost host;

    @BeforeEach
    void setUp() {
        portals = mock(PortalManager.class);
        LocalPortal standalone = mock(LocalPortal.class);
        when(standalone.getName()).thenReturn("Garden");
        NexusPortalExtension unjoined = mock(NexusPortalExtension.class);
        when(standalone.extension(NexusPortalExtension.class)).thenReturn(unjoined);
        LocalPortal networked = mock(LocalPortal.class);
        NexusPortalExtension joined = mock(NexusPortalExtension.class);
        when(joined.networkId()).thenReturn(UUID.randomUUID());
        when(networked.extension(NexusPortalExtension.class)).thenReturn(joined);
        ILocalPortal foreign = mock(ILocalPortal.class);
        when(portals.getLocalPortal(standaloneId)).thenReturn(standalone);
        when(portals.getLocalPortal(networkedId)).thenReturn(networked);
        when(portals.getLocalPortal(remoteId)).thenReturn(foreign);
        service = mock(AtlasService.class);
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        viewer = new BukkitMenuViewer(player);
        host = new BukkitAtlasMenuHost(portals, () -> service);
    }

    @Test
    void theViewIsTheLoadedStateWithTheDepartableRows() {
        AtlasPlayerState state = new AtlasPlayerState(player.getUniqueId());
        AtlasModel.Row row = new AtlasModel.Row(standaloneId, "Garden", "world", "", "", 4.0D, true, false, true);
        when(service.state(player)).thenReturn(state);
        when(service.candidates(player)).thenReturn(List.of(row));

        AtlasMenuView view = host.atlas(viewer);

        assertSame(state, view.state());
        assertEquals(List.of(row), view.candidates());
    }

    @Test
    void destroyedPortalsAreMissing() {
        LocalPortal destroyed = mock(LocalPortal.class);
        NexusPortalExtension joined = mock(NexusPortalExtension.class);
        when(joined.networkId()).thenReturn(UUID.randomUUID());
        when(destroyed.extension(NexusPortalExtension.class)).thenReturn(joined);
        when(destroyed.isDestroyed()).thenReturn(true);
        UUID destroyedId = UUID.randomUUID();
        when(portals.getLocalPortal(destroyedId)).thenReturn(destroyed);

        assertEquals(AtlasPortalLink.MISSING, host.link(destroyedId));
    }

    @Test
    void networkedPortalsDialAndOthersSummarise() {
        assertEquals(AtlasPortalLink.STANDALONE, host.link(standaloneId));
        assertEquals(AtlasPortalLink.NETWORKED, host.link(networkedId));
        assertEquals(AtlasPortalLink.MISSING, host.link(remoteId));
        assertEquals(AtlasPortalLink.MISSING, host.link(UUID.randomUUID()));
    }

    @Test
    void portalNamesFallBackToEmpty() {
        assertEquals("Garden", host.portalName(standaloneId));
        assertEquals("", host.portalName(UUID.randomUUID()));
    }

    @Test
    void favouritesAndTheGuideGoThroughTheService() {
        when(service.toggleFavorite(player, standaloneId)).thenReturn(AtlasPlayerState.FavoriteResult.ADDED);

        assertEquals(AtlasPlayerState.FavoriteResult.ADDED, host.toggleFavorite(viewer, standaloneId));
        host.setGuideTarget(viewer, standaloneId);
        host.clearGuide(viewer);

        verify(service).setGuideTarget(player, standaloneId);
        verify(service).setGuideTarget(player, null);
    }

    @Test
    void aStoppedAtlasRefusesToBuild() {
        service = null;

        assertThrows(NullPointerException.class, () -> host.atlas(viewer));
    }
}
