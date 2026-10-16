package art.arcane.wormholes.modded.menu.host;

import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.wormholes.localization.FidelityMessages;
import art.arcane.wormholes.localization.TransitMessages;
import art.arcane.wormholes.menu.MenuInteraction;
import art.arcane.wormholes.menu.fidelity.FidelityRoutes;
import art.arcane.wormholes.menu.portal.MenuExtensionEntry;
import art.arcane.wormholes.menu.transit.TransitRoutes;
import org.junit.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertEquals;

public class MinecraftExtensionsMenuHostTest {
    private static final UUID PORTAL = UUID.fromString("e1b2c3d4-0000-4000-8000-0000000000aa");

    @Test
    public void entriesAreTheGivenRecordsInOrder() {
        MenuExtensionEntry transit = new MenuExtensionEntry("transit", "minecraft:feather", "transit", TransitMessages.MENU_ENTRY, 1,
            portalId -> MessageArgs.empty(), (viewer, portalId) -> true, (viewer, portalId) -> false, TransitRoutes::main);
        MenuExtensionEntry fidelity = new MenuExtensionEntry("fidelity", "minecraft:spyglass", "projection", FidelityMessages.MENU_ENTRY, 1,
            portalId -> MessageArgs.empty(), (viewer, portalId) -> true, (viewer, portalId) -> false, FidelityRoutes::main);

        List<MenuExtensionEntry> entries = new MinecraftExtensionsMenuHost(List.of(transit, fidelity)).entries();

        assertEquals(2, entries.size());
        assertSame(transit, entries.getFirst());
        assertSame(fidelity, entries.getLast());
    }

    @Test
    public void noHookHandlesAnActivation() {
        MinecraftExtensionsMenuHost host = new MinecraftExtensionsMenuHost(List.of());

        assertFalse(host.activate(null, PORTAL, "transit", MenuInteraction.PRIMARY));
        assertFalse(host.activate(null, PORTAL, "custom", MenuInteraction.TERTIARY));
    }
}
