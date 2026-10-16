package art.arcane.wormholes.menu.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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

import art.arcane.optics.crossing.ScaleRule;
import art.arcane.wormholes.PortalManager;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.menu.BukkitMenuViewer;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.transit.TransitEdit;
import art.arcane.wormholes.menu.transit.TransitMenuView;
import art.arcane.wormholes.network.PortalSyncService;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.transit.MomentumPolicy;
import art.arcane.wormholes.transit.OrientationPolicy;
import art.arcane.wormholes.transit.ScaleRuleSettings;
import art.arcane.wormholes.transit.TransitPortalExtension;
import art.arcane.wormholes.transit.TransitSubsystem;
import art.arcane.wormholes.transit.TransitionProfile;

class BukkitTransitMenuHostTest {
    private final UUID portalId = UUID.randomUUID();
    private PortalSyncService<ILocalPortal> previousSync;
    private PortalSyncService<ILocalPortal> sync;
    private LocalPortal portal;
    private TransitPortalExtension transit;
    private MenuViewer viewer;
    private BukkitTransitMenuHost host;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        previousSync = Wormholes.portalSyncService;
        sync = mock(PortalSyncService.class);
        Wormholes.portalSyncService = sync;
        transit = new TransitPortalExtension();
        portal = mock(LocalPortal.class);
        when(portal.getId()).thenReturn(portalId);
        when(portal.extension(TransitPortalExtension.class)).thenReturn(transit);
        PortalManager portals = mock(PortalManager.class);
        when(portals.getLocalPortal(portalId)).thenReturn(portal);
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        viewer = new BukkitMenuViewer(player);
        host = new BukkitTransitMenuHost(portals);
    }

    @AfterEach
    void tearDown() {
        Wormholes.portalSyncService = previousSync;
    }

    @Test
    void viewReadsThePortalTransitSettingsWithoutGravity() {
        transit.setMembrane(true);
        transit.setProfile(TransitionProfile.NONE.withArrivalSound("minecraft:block.bell.use"));

        TransitMenuView view = host.transit(portalId).orElseThrow();

        assertNull(view.momentum());
        assertEquals(transit.effectiveMomentum(TransitSubsystem.config()), view.effectiveMomentum());
        assertNull(view.orientation());
        assertEquals(transit.effectiveOrientation(TransitSubsystem.config()), view.effectiveOrientation());
        assertTrue(view.membrane());
        assertFalse(view.bounce());
        assertEquals("minecraft:block.bell.use", view.profile().arrivalSound());
        assertEquals(transit.scaleRule(), view.scaleRule());
        assertFalse(view.gravityRotation());
        assertFalse(host.supportsGravityRotation());
    }

    @Test
    void portalsWithoutTransitSettingsHaveNoView() {
        when(portal.extension(TransitPortalExtension.class)).thenReturn(null);

        assertTrue(host.transit(portalId).isEmpty());
        assertTrue(host.transit(UUID.randomUUID()).isEmpty());
    }

    @Test
    void editsChangeTheExtensionSaveAndRefreshOpenMenus() {
        MomentumPolicy momentum = MomentumPolicy.of(MomentumPolicy.Mode.values()[1]).withFactor(2.5D);
        ScaleRule scale = ScaleRuleSettings.withMin(transit.scaleRule(), 0.5D);

        host.apply(viewer, portalId, new TransitEdit.Momentum(momentum));
        host.apply(viewer, portalId, new TransitEdit.Orientation(OrientationPolicy.values()[1]));
        host.apply(viewer, portalId, new TransitEdit.Membrane(true));
        host.apply(viewer, portalId, new TransitEdit.Bounce(true));
        host.apply(viewer, portalId, new TransitEdit.Profile(TransitionProfile.NONE.withMaskOverrideTicks(40)));
        host.apply(viewer, portalId, new TransitEdit.Scale(scale));

        assertEquals(momentum, transit.momentum());
        assertEquals(OrientationPolicy.values()[1], transit.orientation());
        assertTrue(transit.isMembrane());
        assertTrue(transit.isBounce());
        assertEquals(40, transit.profile().maskOverrideTicks());
        assertEquals(scale, transit.scaleRule());
        verify(portal, times(6)).save();
        verify(portal, times(6)).invalidateMenus();
        verify(sync, never()).broadcastSettings(any());
    }

    @Test
    void syncedPortalsBroadcastEveryEdit() {
        when(portal.isSettingsSyncEnabled()).thenReturn(true);

        host.apply(viewer, portalId, new TransitEdit.Bounce(true));

        verify(sync).broadcastSettings(portal);
    }

    @Test
    void gravityIsNotABukkitControl() {
        assertThrows(IllegalArgumentException.class, () -> host.apply(viewer, portalId, new TransitEdit.Gravity(true)));
        verify(portal, never()).save();
    }

    @Test
    void missingPortalsAreNeverEdited() {
        host.apply(viewer, UUID.randomUUID(), new TransitEdit.Bounce(true));

        assertFalse(transit.isBounce());
        verify(portal, never()).save();
    }
}
