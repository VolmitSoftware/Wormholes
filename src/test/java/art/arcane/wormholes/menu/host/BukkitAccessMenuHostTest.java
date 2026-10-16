package art.arcane.wormholes.menu.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.PortalManager;
import art.arcane.wormholes.access.AccessPortalExtension;
import art.arcane.wormholes.access.AccessTestPortals;
import art.arcane.wormholes.access.PortalRole;
import art.arcane.wormholes.hook.WormholesHooks;
import art.arcane.wormholes.hook.WormholesRegistrar;
import art.arcane.wormholes.menu.BukkitMenuViewer;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.access.AccessEdit;
import art.arcane.wormholes.menu.access.AccessKeyOutcome;
import art.arcane.wormholes.menu.access.AccessMenuView;
import art.arcane.wormholes.portal.LocalPortal;

class BukkitAccessMenuHostTest {
    private final UUID first = UUID.randomUUID();
    private final UUID second = UUID.randomUUID();
    private LocalPortal portal;
    private AccessPortalExtension access;
    private Server server;
    private MenuViewer viewer;
    private BukkitAccessMenuHost host;

    @BeforeEach
    void setUp() {
        WormholesHooks.install(new WormholesRegistrar().portalExtension(AccessPortalExtension.class, AccessPortalExtension::new));
        portal = AccessTestPortals.portal(AccessTestPortals.world("access-host"));
        access = portal.extension(AccessPortalExtension.class);
        PortalManager portals = mock(PortalManager.class);
        when(portals.getLocalPortal(portal.getId())).thenReturn(portal);
        server = mock(Server.class);
        viewer = new BukkitMenuViewer(AccessTestPortals.player("Viewer", false, Set.of()));
        host = new BukkitAccessMenuHost(portals, server);
    }

    @AfterEach
    void clearHooks() {
        WormholesHooks.clear();
    }

    @Test
    void theViewListsRolesInTheOrderTheyWereAdded() {
        UUID owner = UUID.randomUUID();
        portal.setOwner(owner);
        access.setRole(first, PortalRole.USER);
        access.setRole(second, PortalRole.DENIED);
        access.addGroup("group.vip");

        AccessMenuView view = host.access(portal.getId()).orElseThrow();

        assertEquals(portal.getName(), view.portalName());
        assertEquals(owner, view.owner());
        assertEquals(access.permissionKey(), view.permissionKey());
        assertEquals(1, view.groupCount());
        assertTrue(view.listed());
        assertEquals(List.of(new AccessMenuView.Role(first, PortalRole.USER), new AccessMenuView.Role(second, PortalRole.DENIED)), view.roles());
        assertTrue(host.access(UUID.randomUUID()).isEmpty());
    }

    @Test
    void onlyTheOwnerCoOwnersAndAdministratorsCanManage() {
        portal.setOwner(UUID.randomUUID());
        assertFalse(host.canManage(viewer, portal.getId()));

        access.setRole(viewer.id(), PortalRole.CO_OWNER);
        assertTrue(host.canManage(viewer, portal.getId()));

        MenuViewer administrator = new BukkitMenuViewer(AccessTestPortals.player("Admin", true, Set.of()));
        assertTrue(host.canManage(administrator, portal.getId()));
        assertFalse(host.canManage(administrator, UUID.randomUUID()));
    }

    @Test
    void roleEditsReportWhetherTheyChangedAnything() {
        assertTrue(host.edit(viewer, portal.getId(), new AccessEdit.SetRole(first, PortalRole.CO_OWNER)));
        assertEquals(PortalRole.CO_OWNER, access.role(first));

        assertTrue(host.edit(viewer, portal.getId(), new AccessEdit.RemoveRole(first)));
        assertFalse(host.edit(viewer, portal.getId(), new AccessEdit.RemoveRole(first)));
        assertFalse(host.edit(viewer, UUID.randomUUID(), new AccessEdit.SetRole(first, PortalRole.USER)));
    }

    @Test
    void groupsAndTheDirectoryFlagAreEditable() {
        assertTrue(host.edit(viewer, portal.getId(), new AccessEdit.Listed(false)));
        assertFalse(access.listed());

        assertTrue(host.edit(viewer, portal.getId(), new AccessEdit.AddGroup("group.vip")));
        assertFalse(host.edit(viewer, portal.getId(), new AccessEdit.AddGroup("group.vip")));
        assertTrue(host.edit(viewer, portal.getId(), new AccessEdit.ClearGroups()));
        assertFalse(host.edit(viewer, portal.getId(), new AccessEdit.ClearGroups()));
    }

    @Test
    void thePermissionKeyOutcomeMirrorsTheExtension() {
        assertEquals(AccessKeyOutcome.INVALID, host.setPermissionKey(viewer, portal.getId(), "not a key!"));
        assertEquals(AccessKeyOutcome.SET, host.setPermissionKey(viewer, portal.getId(), "vault"));
        assertEquals(AccessKeyOutcome.UNCHANGED, host.setPermissionKey(viewer, portal.getId(), "vault"));
        assertEquals("vault", access.permissionKey());
    }

    @Test
    void onlinePlayersResolveByExactNameOnTheCallingThread() {
        Player online = mock(Player.class);
        when(online.getUniqueId()).thenReturn(first);
        when(server.getPlayerExact("Astra")).thenReturn(online);

        CompletableFuture<Optional<UUID>> resolved = host.resolvePlayer("Astra");

        assertTrue(resolved.isDone());
        assertEquals(Optional.of(first), resolved.join());
        assertEquals(Optional.empty(), host.resolvePlayer("Nobody").join());
    }

    @Test
    void playerNamesPreferOnlinePlayersThenTheOfflineCache() {
        Player online = mock(Player.class);
        when(online.getName()).thenReturn("Astra");
        when(server.getPlayer(first)).thenReturn(online);
        OfflinePlayer offline = mock(OfflinePlayer.class);
        when(offline.getName()).thenReturn("Borealis");
        when(server.getOfflinePlayer(second)).thenReturn(offline);
        UUID unknown = UUID.randomUUID();
        when(server.getOfflinePlayer(unknown)).thenReturn(mock(OfflinePlayer.class));

        assertEquals(Optional.of("Astra"), host.playerName(first));
        assertEquals(Optional.of("Borealis"), host.playerName(second));
        assertEquals(Optional.empty(), host.playerName(unknown));
    }
}
