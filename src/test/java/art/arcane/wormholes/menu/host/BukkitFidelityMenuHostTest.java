package art.arcane.wormholes.menu.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import art.arcane.optics.fidelity.AcousticsProfile;
import art.arcane.optics.fidelity.AtmosphereMode;
import art.arcane.optics.volume.LodProfile;
import art.arcane.wormholes.PortalManager;
import art.arcane.wormholes.ProjectionManager;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.menu.BukkitMenuViewer;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.fidelity.FidelityEdit;
import art.arcane.wormholes.menu.fidelity.FidelityMenuView;
import art.arcane.wormholes.network.PortalSyncService;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.render.FidelityPortalExtension;

class BukkitFidelityMenuHostTest {
    private final UUID portalId = UUID.randomUUID();
    private PortalSyncService<ILocalPortal> previousSync;
    private ProjectionManager previousProjection;
    private PortalSyncService<ILocalPortal> sync;
    private ProjectionManager projection;
    private LocalPortal portal;
    private FidelityPortalExtension fidelity;
    private MenuViewer viewer;
    private BukkitFidelityMenuHost host;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        previousSync = Wormholes.portalSyncService;
        previousProjection = Wormholes.projectionManager;
        sync = mock(PortalSyncService.class);
        projection = mock(ProjectionManager.class, RETURNS_DEEP_STUBS);
        Wormholes.portalSyncService = sync;
        Wormholes.projectionManager = projection;
        fidelity = new FidelityPortalExtension();
        portal = mock(LocalPortal.class);
        when(portal.getId()).thenReturn(portalId);
        when(portal.extension(FidelityPortalExtension.class)).thenReturn(fidelity);
        PortalManager portals = mock(PortalManager.class);
        when(portals.getLocalPortal(portalId)).thenReturn(portal);
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        viewer = new BukkitMenuViewer(player);
        host = new BukkitFidelityMenuHost(portals);
    }

    @AfterEach
    void tearDown() {
        Wormholes.portalSyncService = previousSync;
        Wormholes.projectionManager = previousProjection;
    }

    @Test
    void viewReadsOverridesAndEffectiveValues() {
        fidelity.setLodProfile(LodProfile.values()[0]);

        FidelityMenuView view = host.fidelity(portalId).orElseThrow();

        assertNull(view.atmosphere());
        assertNull(view.acoustics());
        assertEquals(LodProfile.values()[0], view.lod());
        assertNull(view.blockEntities());
        assertEquals(fidelity.effectiveAtmosphereMode(), view.effectiveAtmosphere());
        assertEquals(fidelity.effectiveAcousticsProfile(), view.effectiveAcoustics());
        assertEquals(LodProfile.values()[0], view.effectiveLod());
        assertEquals(fidelity.effectiveBlockEntities(), view.effectiveBlockEntities());
    }

    @Test
    void portalsWithoutFidelitySettingsHaveNoView() {
        when(portal.extension(FidelityPortalExtension.class)).thenReturn(null);

        assertTrue(host.fidelity(portalId).isEmpty());
        assertTrue(host.fidelity(UUID.randomUUID()).isEmpty());
    }

    @Test
    void editsPersistBroadcastAndDropCachedPlates() {
        host.apply(viewer, portalId, new FidelityEdit.Atmosphere(AtmosphereMode.values()[0]));
        host.apply(viewer, portalId, new FidelityEdit.Acoustics(AcousticsProfile.values()[0]));
        host.apply(viewer, portalId, new FidelityEdit.Lod(LodProfile.values()[0]));
        host.apply(viewer, portalId, new FidelityEdit.BlockEntities(Boolean.FALSE));

        assertEquals(AtmosphereMode.values()[0], fidelity.atmosphereMode());
        assertEquals(AcousticsProfile.values()[0], fidelity.acousticsProfile());
        assertEquals(LodProfile.values()[0], fidelity.lodProfile());
        assertEquals(Boolean.FALSE, fidelity.blockEntities());
        verify(portal, times(4)).save();
        verify(portal, times(4)).invalidateMenus();
        verify(sync, times(4)).broadcastSettings(portal);
        verify(projection.plateCache(), times(4)).invalidatePortal(portalId);
    }

    @Test
    void resetEditsClearTheOverride() {
        fidelity.setBlockEntities(Boolean.TRUE);

        host.apply(viewer, portalId, new FidelityEdit.BlockEntities(null));

        assertNull(fidelity.blockEntities());
        assertNull(host.fidelity(portalId).orElseThrow().blockEntities());
    }

    @Test
    void missingPortalsAreNeverEdited() {
        host.apply(viewer, UUID.randomUUID(), new FidelityEdit.Lod(LodProfile.values()[0]));

        assertNull(fidelity.lodProfile());
        verify(portal, never()).save();
    }
}
