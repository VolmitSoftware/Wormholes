package art.arcane.wormholes.commands;

import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.wormholes.access.AccessTestPortals;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.LocalPortal;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CommandAccessTest {
    @Test
    void theCommandTreeCarriesTransferKeyAndLimits() {
        Director group = CommandAccess.class.getAnnotation(Director.class);

        assertEquals("access", group.name());
        assertEquals(List.of("key", "limits", "transfer"), directorMethodNames().stream().sorted().toList());
    }

    @Test
    void theRootCommandOwnsTheAccessSlot() throws NoSuchFieldException {
        Field slot = CommandWormholes.class.getDeclaredField("access");

        assertSame(CommandAccess.class, slot.getType());
    }

    @Test
    void aPortalIsFoundByExactNameOrByIdPrefix() {
        World world = AccessTestPortals.world("commands");
        LocalPortal hub = AccessTestPortals.portal(world);
        LocalPortal outpost = AccessTestPortals.portal(world);
        hub.setName("Hub");
        outpost.setName("Outpost");
        List<ILocalPortal> portals = List.of(hub, outpost);

        assertSame(hub, CommandAccess.findPortal("hub", portals).portal());
        assertSame(hub, CommandAccess.findPortal("HUB", portals).portal());
        assertSame(outpost, CommandAccess.findPortal(outpost.getId().toString(), portals).portal());
        assertSame(outpost, CommandAccess.findPortal(outpost.getId().toString().substring(0, 8), portals).portal());
        assertNull(CommandAccess.findPortal("nothing", portals).portal());
        assertEquals(0, CommandAccess.findPortal("nothing", portals).matches());
    }

    @Test
    void twoPortalsWithTheSameNameReportAsAmbiguous() {
        World world = AccessTestPortals.world("commands-ambiguous");
        LocalPortal first = AccessTestPortals.portal(world);
        LocalPortal second = AccessTestPortals.portal(world);
        first.setName("Twin");
        second.setName("twin");

        CommandAccess.PortalMatch match = CommandAccess.findPortal("Twin", List.of(first, second));

        assertEquals(2, match.matches());
        assertNull(match.portal());
    }

    private static List<String> directorMethodNames() {
        List<String> names = new ArrayList<>();
        for (Method method : CommandAccess.class.getDeclaredMethods()) {
            Director director = method.getAnnotation(Director.class);
            if (director != null) {
                names.add(director.name());
            }
        }
        assertTrue(names.size() >= 3);
        return names;
    }
}
