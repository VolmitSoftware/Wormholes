package art.arcane.wormholes.access;

import art.arcane.wormholes.PortalManager;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.hook.WormholesHooks;
import art.arcane.wormholes.hook.WormholesRegistrar;
import art.arcane.wormholes.localization.WormholesLocalization;
import art.arcane.wormholes.menu.access.AccessRoutes;
import art.arcane.wormholes.menu.portal.MenuExtensionEntry;
import art.arcane.wormholes.portal.LocalPortal;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class AccessMenuModelTest {
    private World world;

    @BeforeEach
    void registerExtension() {
        WormholesHooks.install(new WormholesRegistrar().portalExtension(AccessPortalExtension.class, AccessPortalExtension::new));
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
    void theMenuEntryIsTheAccessTagInTheMoreSettingsGrid() {
        LocalPortal portal = AccessTestPortals.portal(world);
        PortalManager previous = Wormholes.portalManager;
        PortalManager portals = mock(PortalManager.class);
        when(portals.getLocalPortal(portal.getId())).thenReturn(portal);
        Wormholes.portalManager = portals;
        try {
            WormholesRegistrar registrar = new WormholesRegistrar();
            new AccessSubsystem().register(registrar);
            WormholesHooks.install(registrar);
            MenuExtensionEntry entry = WormholesHooks.portalMenuEntries().getFirst();

            assertEquals("access", entry.id());
            assertEquals("minecraft:name_tag", entry.chestItemId());
            assertEquals("access", entry.nativeSprite());
            assertEquals(AccessRoutes.roles(portal.getId()), entry.route().apply(portal.getId()));
            assertTrue(entry.visible().test(null, portal.getId()));
            assertFalse(entry.enchanted().test(null, portal.getId()));
            portal.extension(AccessPortalExtension.class).setRole(UUID.randomUUID(), PortalRole.USER);
            assertTrue(entry.enchanted().test(null, portal.getId()));
            List<Component> lines = new WormholesLocalization().components(entry.label(), entry.arguments().apply(portal.getId()));
            assertEquals(2, lines.size());
            assertEquals("Access", PlainTextComponentSerializer.plainText().serialize(lines.getFirst()));
            assertEquals("Roles, groups, and the permission key.", PlainTextComponentSerializer.plainText().serialize(lines.get(1)));
        } finally {
            Wormholes.portalManager = previous;
        }
    }
}
