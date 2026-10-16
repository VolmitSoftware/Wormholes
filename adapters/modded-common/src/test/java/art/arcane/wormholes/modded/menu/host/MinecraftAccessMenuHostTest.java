package art.arcane.wormholes.modded.menu.host;

import art.arcane.wormholes.access.PortalRole;
import art.arcane.wormholes.menu.MenuScope;
import art.arcane.wormholes.menu.MenuService;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.access.AccessEdit;
import art.arcane.wormholes.menu.access.AccessKeyOutcome;
import art.arcane.wormholes.menu.access.AccessMenuView;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.MinecraftPortalRegistry;
import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.menu.MinecraftMenuViewer;
import art.arcane.wormholes.portal.PortalType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.Services;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.players.UserNameToIdResolver;
import org.junit.Before;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MinecraftAccessMenuHostTest extends MinecraftTestBase {
    private static final UUID PORTAL = UUID.fromString("acce0000-0000-4000-8000-0000000000aa");
    private static final UUID LISTED = UUID.fromString("acce0000-0000-4000-8000-0000000000ab");

    private MinecraftServer server;
    private UserNameToIdResolver cache;
    private MinecraftPortalRegistry portals;
    private MenuService menus;
    private PlayerList players;
    private ServerPlayer player;
    private MinecraftPortal portal;
    private MenuViewer viewer;
    private MinecraftAccessMenuHost host;

    @Before
    public void setUp() {
        WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        server = mock(MinecraftServer.class);
        Services services = mock(Services.class);
        cache = mock(UserNameToIdResolver.class);
        when(server.services()).thenReturn(services);
        when(services.nameToIdCache()).thenReturn(cache);
        portals = mock(MinecraftPortalRegistry.class);
        menus = mock(MenuService.class);
        players = mock(PlayerList.class);
        player = mock(ServerPlayer.class);
        portal = mock(MinecraftPortal.class);
        when(runtime.portals()).thenReturn(portals);
        when(runtime.menus()).thenReturn(menus);
        when(runtime.server()).thenReturn(server);
        when(server.getPlayerList()).thenReturn(players);
        when(portals.get(PORTAL)).thenReturn(portal);
        when(portals.snapshot()).thenReturn(List.of(portal));
        when(portal.getId()).thenReturn(PORTAL);
        when(portal.getName()).thenReturn("Alpha");
        when(portal.getType()).thenReturn(PortalType.PORTAL);
        when(portal.getPermissionKey()).thenReturn("alpha");
        when(player.getUUID()).thenReturn(UUID.randomUUID());
        when(portals.update(eq(player), eq(PORTAL), any())).thenAnswer(invocation -> {
            Consumer<MinecraftPortal> mutation = invocation.getArgument(2);
            mutation.accept(portal);
            return true;
        });
        viewer = new MinecraftMenuViewer(runtime, player);
        host = new MinecraftAccessMenuHost(runtime);
    }

    @Test
    public void theViewListsTheStoredRoles() {
        Map<UUID, PortalRole> roles = new LinkedHashMap<>();
        roles.put(LISTED, PortalRole.USER);
        when(portal.getRoles()).thenReturn(roles);
        when(portal.getGroups()).thenReturn(List.of("group.vip"));
        when(portal.isListed()).thenReturn(true);

        AccessMenuView view = host.access(PORTAL).orElseThrow();

        assertEquals("Alpha", view.portalName());
        assertEquals("alpha", view.permissionKey());
        assertEquals(1, view.groupCount());
        assertEquals(List.of(new AccessMenuView.Role(LISTED, PortalRole.USER)), view.roles());
        assertFalse(host.access(UUID.randomUUID()).isPresent());
    }

    @Test
    public void roleEditsGoThroughTheRegistryAndRefreshThePortal() {
        when(portal.role(LISTED)).thenReturn(PortalRole.USER);

        assertTrue(host.edit(viewer, PORTAL, new AccessEdit.SetRole(LISTED, PortalRole.DENIED)));
        assertTrue(host.edit(viewer, PORTAL, new AccessEdit.RemoveRole(LISTED)));

        verify(portal).setRole(LISTED, PortalRole.DENIED);
        verify(portal).setRole(LISTED, null);
        verify(menus, times(2)).invalidate(new MenuScope.Portal(PORTAL));
    }

    @Test
    public void editsThatWouldChangeNothingAreRefused() {
        when(portal.getGroups()).thenReturn(List.of("group.vip"));

        assertFalse(host.edit(viewer, PORTAL, new AccessEdit.RemoveRole(LISTED)));
        assertFalse(host.edit(viewer, PORTAL, new AccessEdit.AddGroup("group.vip")));
        assertFalse(host.edit(viewer, PORTAL, new AccessEdit.AddGroup("")));
        assertTrue(host.edit(viewer, PORTAL, new AccessEdit.ClearGroups()));

        when(portal.getGroups()).thenReturn(List.of());

        assertFalse(host.edit(viewer, PORTAL, new AccessEdit.ClearGroups()));
        verify(portal, never()).addGroup(any());
    }

    @Test
    public void permissionKeysAreValidatedAndMustBeUnique() {
        MinecraftPortal other = mock(MinecraftPortal.class);
        when(other.getId()).thenReturn(UUID.randomUUID());
        when(other.getPermissionKey()).thenReturn("beta");
        when(portals.snapshot()).thenReturn(List.of(portal, other));

        assertEquals(AccessKeyOutcome.INVALID, host.setPermissionKey(viewer, PORTAL, "not a key!"));
        assertEquals(AccessKeyOutcome.UNCHANGED, host.setPermissionKey(viewer, PORTAL, "alpha"));
        assertEquals(AccessKeyOutcome.TAKEN, host.setPermissionKey(viewer, PORTAL, "beta"));
        assertEquals(AccessKeyOutcome.SET, host.setPermissionKey(viewer, PORTAL, "gamma"));

        verify(portal).setPermissionKey("gamma");
    }

    @Test
    public void aRefusedPermissionKeyEditIsInvalid() {
        when(portals.update(eq(player), eq(PORTAL), any())).thenReturn(false);

        assertEquals(AccessKeyOutcome.INVALID, host.setPermissionKey(viewer, PORTAL, "gamma"));
        verify(menus, never()).invalidate(any());
    }

    @Test
    public void offlineNamesResolveOnTheServerThread() throws Exception {
        List<Runnable> serverTasks = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> serverTasks.add(invocation.getArgument(0))).when(server).execute(any(Runnable.class));
        when(cache.get("Astra")).thenReturn(Optional.of(new NameAndId(LISTED, "Astra")));

        CompletableFuture<Optional<UUID>> resolved = host.resolvePlayer("Astra");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (serverTasks.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }

        assertFalse(resolved.isDone());
        serverTasks.forEach(Runnable::run);
        assertEquals(Optional.of(LISTED), resolved.getNow(Optional.empty()));
    }

    @Test
    public void aStoppingServerResolvesOfflineNamesToNobody() throws Exception {
        doThrow(new RejectedExecutionException("stopping")).when(server).execute(any(Runnable.class));
        when(cache.get("Astra")).thenReturn(Optional.of(new NameAndId(LISTED, "Astra")));

        assertEquals(Optional.empty(), host.resolvePlayer("Astra").get(5, TimeUnit.SECONDS));
    }

    @Test
    public void onlinePlayersResolveImmediately() {
        ServerPlayer online = mock(ServerPlayer.class);
        when(online.getUUID()).thenReturn(LISTED);
        when(players.getPlayerByName("Astra")).thenReturn(online);

        CompletableFuture<Optional<UUID>> resolved = host.resolvePlayer("Astra");

        assertTrue(resolved.isDone());
        assertEquals(Optional.of(LISTED), resolved.join());
    }
}
