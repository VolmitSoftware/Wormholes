package art.arcane.wormholes.transit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.Material;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.hook.WormholesHooks;
import art.arcane.wormholes.hook.WormholesRegistrar;
import art.arcane.wormholes.localization.TransitMessages;
import art.arcane.wormholes.portal.LocalPortal;

final class TransitMenuEntryTest {
    @AfterEach
    void clearHooks() {
        WormholesHooks.clear();
    }

    @Test
    void entryIsTheFeatherTileAndLightsUpOncePortalStateDivergesFromTheDefaults() {
        TransitMenuEntry entry = new TransitMenuEntry();
        assertEquals("transit", entry.id());
        assertEquals(Material.FEATHER, entry.icon());
        assertSame(TransitMessages.MENU_ENTRY, entry.label());

        LocalPortal bare = TransitTestSupport.portal(TransitTestSupport.world("menu-bare"));
        assertFalse(entry.visible(bare, null), "no extension, no tile");

        WormholesHooks.install(new WormholesRegistrar().portalExtension(new TransitExtensionFactory()));
        LocalPortal portal = TransitTestSupport.portal(TransitTestSupport.world("menu"));
        assertTrue(entry.visible(portal, null));
        assertFalse(entry.enchanted(portal, null));
        portal.extension(TransitPortalExtension.class).setMembrane(true);
        assertTrue(entry.enchanted(portal, null));
    }

    @Test
    void chatAnswersAreParsedDefensively() {
        assertEquals(1.5D, TransitMenu.parseFactor(" 1.5 "));
        assertNull(TransitMenu.parseFactor("eleven"));
        assertNull(TransitMenu.parseFactor("-1"));
        assertNull(TransitMenu.parseFactor("11"));
        assertNull(TransitMenu.parseFactor(null));

        assertEquals(-1, TransitMenu.parseMaskTicks(""));
        assertEquals(-1, TransitMenu.parseMaskTicks("-1"));
        assertEquals(12, TransitMenu.parseMaskTicks("12"));
        assertNull(TransitMenu.parseMaskTicks("201"));
        assertNull(TransitMenu.parseMaskTicks("soon"));

        assertEquals("", TransitMenu.soundOrEmpty("-"));
        assertEquals("", TransitMenu.soundOrEmpty("default"));
        assertEquals("minecraft:block.bell.use", TransitMenu.soundOrEmpty(" Minecraft:Block.Bell.Use "));
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
