package art.arcane.wormholes.transit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.PortalManager;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.hook.WormholesHooks;
import art.arcane.wormholes.hook.WormholesRegistrar;
import art.arcane.wormholes.localization.TransitMessages;
import art.arcane.wormholes.menu.portal.MenuExtensionEntry;
import art.arcane.wormholes.menu.transit.TransitRoutes;
import art.arcane.wormholes.portal.LocalPortal;

final class TransitMenuEntryTest {
    @AfterEach
    void clearHooks() {
        WormholesHooks.clear();
    }

    @Test
    void entryIsTheFeatherTileAndLightsUpOncePortalStateDivergesFromTheDefaults() {
        PortalManager previous = Wormholes.portalManager;
        PortalManager portals = mock(PortalManager.class);
        Wormholes.portalManager = portals;
        try {
            MenuExtensionEntry entry = registeredEntry();
            assertEquals("transit", entry.id());
            assertEquals("minecraft:feather", entry.chestItemId());
            assertEquals("transit", entry.nativeSprite());
            assertSame(TransitMessages.MENU_ENTRY, entry.label());
            assertEquals(1, entry.chestHintLines());

            WormholesHooks.clear();
            LocalPortal bare = TransitTestSupport.portal(TransitTestSupport.world("menu-bare"));
            when(portals.getLocalPortal(bare.getId())).thenReturn(bare);
            assertFalse(entry.visible().test(null, bare.getId()), "no extension, no tile");

            WormholesHooks.install(new WormholesRegistrar().portalExtension(TransitPortalExtension.class, extensionPortal -> new TransitPortalExtension()));
            LocalPortal portal = TransitTestSupport.portal(TransitTestSupport.world("menu"));
            when(portals.getLocalPortal(portal.getId())).thenReturn(portal);
            assertEquals(TransitRoutes.main(portal.getId()), entry.route().apply(portal.getId()));
            assertTrue(entry.visible().test(null, portal.getId()));
            assertFalse(entry.enchanted().test(null, portal.getId()));
            portal.extension(TransitPortalExtension.class).setMembrane(true);
            assertTrue(entry.enchanted().test(null, portal.getId()));
            assertFalse(entry.visible().test(null, UUID.randomUUID()));
        } finally {
            Wormholes.portalManager = previous;
        }
    }

    private static MenuExtensionEntry registeredEntry() {
        WormholesRegistrar registrar = new WormholesRegistrar();
        new TransitSubsystem().register(registrar);
        WormholesHooks.install(registrar);
        return WormholesHooks.portalMenuEntries().getFirst();
    }

    @Test
    void subsystemRegistersTheMenuEntryGateObserverAndExtension() {
        WormholesRegistrar registrar = new WormholesRegistrar();
        new TransitSubsystem().register(registrar);
        WormholesHooks.install(registrar);
        assertEquals(1, WormholesHooks.portalMenuEntries().size());
        assertEquals("transit", WormholesHooks.portalMenuEntries().getFirst().id());
        assertEquals(1, WormholesHooks.traversalGates().size());
        assertEquals(1, WormholesHooks.traversalObservers().size());
        assertEquals(1, WormholesHooks.portalExtensionFactories().size());
    }
}
