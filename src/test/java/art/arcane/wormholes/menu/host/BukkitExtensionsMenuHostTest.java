package art.arcane.wormholes.menu.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.wormholes.hook.WormholesHooks;
import art.arcane.wormholes.hook.WormholesRegistrar;
import art.arcane.wormholes.localization.FidelityMessages;
import art.arcane.wormholes.localization.TransitMessages;
import art.arcane.wormholes.menu.BukkitMenuViewer;
import art.arcane.wormholes.menu.MenuInteraction;
import art.arcane.wormholes.menu.MenuRoute;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.fidelity.FidelityRoutes;
import art.arcane.wormholes.menu.portal.MenuExtensionEntry;
import art.arcane.wormholes.menu.transit.TransitRoutes;

class BukkitExtensionsMenuHostTest {
    private final UUID portalId = UUID.randomUUID();
    private MenuViewer viewer;
    private BukkitExtensionsMenuHost host;

    @BeforeEach
    void setUp() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        viewer = new BukkitMenuViewer(player);
        host = new BukkitExtensionsMenuHost();
    }

    @AfterEach
    void tearDown() {
        WormholesHooks.clear();
    }

    @Test
    void noRegisteredEntriesLeavesTheListEmpty() {
        assertTrue(host.entries().isEmpty());
    }

    @Test
    void entriesAreTheRegisteredRecordsInRegistrationOrder() {
        MenuExtensionEntry transit = entry("transit", "minecraft:feather", "transit", TransitMessages.MENU_ENTRY, TransitRoutes::main);
        MenuExtensionEntry fidelity = entry("fidelity", "minecraft:spyglass", "projection", FidelityMessages.MENU_ENTRY, FidelityRoutes::main);
        WormholesHooks.install(new WormholesRegistrar().menuEntry(transit).menuEntry(fidelity));

        List<MenuExtensionEntry> entries = host.entries();

        assertEquals(2, entries.size());
        assertSame(transit, entries.getFirst());
        assertSame(fidelity, entries.getLast());
        assertEquals(TransitRoutes.main(portalId), entries.getFirst().route().apply(portalId));
    }

    @Test
    void noHookHandlesAnActivation() {
        WormholesHooks.install(new WormholesRegistrar().menuEntry(entry("transit", "minecraft:feather", "transit", TransitMessages.MENU_ENTRY, TransitRoutes::main)));

        assertFalse(host.activate(viewer, portalId, "transit", MenuInteraction.PRIMARY));
        assertFalse(host.activate(viewer, portalId, "custom", MenuInteraction.SECONDARY));
    }

    private static MenuExtensionEntry entry(String id, String item, String sprite, LinesKey label, Function<UUID, MenuRoute> route) {
        return new MenuExtensionEntry(id, item, sprite, label, 1, ignored -> MessageArgs.empty(), (viewer, portal) -> true,
            (viewer, portal) -> false, route);
    }
}
