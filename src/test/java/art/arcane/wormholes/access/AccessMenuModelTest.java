package art.arcane.wormholes.access;

import art.arcane.wormholes.hook.WormholesHooks;
import art.arcane.wormholes.hook.WormholesRegistrar;
import art.arcane.wormholes.localization.WormholesLocalization;
import art.arcane.wormholes.portal.LocalPortal;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

final class AccessMenuModelTest {
    private World world;

    @BeforeEach
    void registerExtension() {
        WormholesHooks.install(new WormholesRegistrar().portalExtension(new AccessExtensionFactory()));
        world = AccessTestPortals.world("access-menu");
    }

    @AfterEach
    void clearHooks() {
        WormholesHooks.clear();
    }

    @Test
    void leftClickWalksTheRolesForwardAndRightClickWalksThemBack() {
        assertSame(PortalRole.CO_OWNER, PortalRole.OWNER.next());
        assertSame(PortalRole.USER, PortalRole.CO_OWNER.next());
        assertSame(PortalRole.DENIED, PortalRole.USER.next());
        assertSame(PortalRole.OWNER, PortalRole.DENIED.next());

        assertSame(PortalRole.DENIED, PortalRole.OWNER.previous());
        assertSame(PortalRole.USER, PortalRole.DENIED.previous());
    }

    @Test
    void everyRoleHasItsOwnIcon() {
        Set<Material> icons = new HashSet<>();
        for (PortalRole role : PortalRole.values()) {
            icons.add(AccessMenu.roleIcon(role));
        }

        assertEquals(PortalRole.values().length, icons.size());
    }

    @Test
    void addingAPlayerReportsWhyItWasRefused() {
        LocalPortal portal = AccessTestPortals.portal(world);
        UUID ownerId = UUID.randomUUID();
        UUID listed = UUID.randomUUID();
        portal.setOwner(ownerId);
        AccessPortalExtension access = portal.extension(AccessPortalExtension.class);
        access.setRole(listed, PortalRole.USER);

        assertSame(AccessMenu.AddResolution.NOT_FOUND, AccessMenu.resolveAddition(portal, access, null));
        assertSame(AccessMenu.AddResolution.OWNER, AccessMenu.resolveAddition(portal, access, ownerId));
        assertSame(AccessMenu.AddResolution.ALREADY_LISTED, AccessMenu.resolveAddition(portal, access, listed));
        assertSame(AccessMenu.AddResolution.ADD, AccessMenu.resolveAddition(portal, access, UUID.randomUUID()));
    }

    @Test
    void theWindowGrowsWithTheRoleListAndStopsAtSixRows() {
        assertEquals(1, AccessMenu.viewportHeight(0));
        assertEquals(2, AccessMenu.viewportHeight(1));
        assertEquals(2, AccessMenu.viewportHeight(9));
        assertEquals(3, AccessMenu.viewportHeight(10));
        assertEquals(6, AccessMenu.viewportHeight(900));
    }

    @Test
    void roleEntriesFillLeftToRightUnderTheHeader() {
        assertEquals(1, AccessMenu.entryRow(0));
        assertEquals(1, AccessMenu.entryRow(8));
        assertEquals(2, AccessMenu.entryRow(9));
        assertEquals(-4, AccessMenu.entryPosition(0));
        assertEquals(4, AccessMenu.entryPosition(8));
        assertEquals(-4, AccessMenu.entryPosition(9));
    }

    @Test
    void theMenuEntryIsTheAccessTagInTheMoreSettingsGrid() {
        AccessMenuEntry entry = new AccessMenuEntry();
        LocalPortal portal = AccessTestPortals.portal(world);

        assertEquals("access", entry.id());
        assertSame(Material.NAME_TAG, entry.icon());
        List<Component> lines = new WormholesLocalization().components(entry.label(), entry.arguments(portal, null));
        assertEquals(2, lines.size());
        assertEquals("Access", PlainTextComponentSerializer.plainText().serialize(lines.getFirst()));
        assertEquals("Roles, groups, and the permission key.", PlainTextComponentSerializer.plainText().serialize(lines.get(1)));
    }
}
