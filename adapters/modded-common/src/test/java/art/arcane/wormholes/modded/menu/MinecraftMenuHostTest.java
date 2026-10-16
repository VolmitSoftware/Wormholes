package art.arcane.wormholes.modded.menu;

import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.menu.MenuCaptures;
import art.arcane.wormholes.menu.MenuRenderer;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.modded.MinecraftAccessService;
import art.arcane.wormholes.modded.MinecraftPortalRegistry;
import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.MinecraftTestSettings;
import art.arcane.wormholes.modded.WormholesModConfiguration;
import art.arcane.wormholes.modded.WormholesModRuntime;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.junit.Test;

import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MinecraftMenuHostTest extends MinecraftTestBase {
    private static final UUID ALEX = UUID.fromString("b1b2c3d4-0000-4000-8000-000000000001");
    private static final UUID OFFLINE = UUID.fromString("b1b2c3d4-0000-4000-8000-000000000002");

    @Test
    public void viewerResolvesOnlinePlayers() {
        Fixture fixture = fixture();

        MenuViewer viewer = fixture.host().viewer(ALEX).orElseThrow();

        assertEquals(ALEX, viewer.id());
        assertEquals("Alex", viewer.name());
        assertSame(fixture.player(), ((MinecraftMenuViewer) viewer).player());
        assertTrue(fixture.host().viewer(OFFLINE).isEmpty());
    }

    @Test
    public void stoppedRuntimeHasNoViewers() {
        Fixture fixture = fixture();
        when(fixture.runtime().running()).thenReturn(false);

        assertTrue(fixture.host().viewer(ALEX).isEmpty());
    }

    @Test
    public void viewerPermissionsUseTheModAccessService() {
        Fixture fixture = fixture();
        when(fixture.access().permission(fixture.player(), "wormholes.portal.edit")).thenReturn(true);
        when(fixture.access().administrator(fixture.player())).thenReturn(true);

        MenuViewer viewer = fixture.host().viewer(ALEX).orElseThrow();

        assertTrue(viewer.hasPermission("wormholes.portal.edit"));
        assertFalse(viewer.hasPermission("wormholes.portal.delete"));
        assertTrue(viewer.administrator());
    }

    @Test
    public void viewerWorkRunsOnTheServerThreadThroughTheRuntimeScheduler() {
        Fixture fixture = fixture();
        Runnable task = () -> {
        };
        when(fixture.runtime().schedule(task, 1L)).thenReturn(true);

        assertTrue(fixture.host().runForViewer(ALEX, task));

        verify(fixture.runtime()).schedule(task, 1L);
    }

    @Test
    public void rejectedSchedulingIsReported() {
        Fixture fixture = fixture();
        Runnable task = () -> {
        };
        when(fixture.runtime().schedule(task, 1L)).thenReturn(false);

        assertFalse(fixture.host().runForViewer(ALEX, task));
    }

    @Test
    public void nativeScreensFollowTheMenusSetting() {
        Fixture fixture = fixture();

        assertTrue(fixture.host().nativeScreensEnabled());

        fixture.settings().getMenus().nativeScreens = false;
        assertFalse(fixture.host().nativeScreensEnabled());

        fixture.settings().getMenus().nativeScreens = true;
        when(fixture.runtime().running()).thenReturn(false);
        assertFalse(fixture.host().nativeScreensEnabled());
    }

    @Test
    public void chestSideAndTransportComeFromTheRuntimeWiring() {
        WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        MinecraftMenuTransport transport = mock(MinecraftMenuTransport.class);
        MenuRenderer renderer = mock(MenuRenderer.class);
        MenuCaptures captures = mock(MenuCaptures.class);
        when(runtime.menuTransport()).thenReturn(transport);

        MinecraftMenuHost host = new MinecraftMenuHost(runtime, new MinecraftMenuHost.Options(renderer, captures));

        assertSame(renderer, host.chestRenderer());
        assertSame(captures, host.captures());
        assertSame(transport, host.transport());
    }

    private static Fixture fixture() {
        WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        MinecraftServer server = mock(MinecraftServer.class);
        PlayerList players = mock(PlayerList.class);
        ServerPlayer player = mock(ServerPlayer.class);
        MinecraftAccessService access = mock(MinecraftAccessService.class);
        MinecraftPortalRegistry portals = mock(MinecraftPortalRegistry.class);
        WormholesModConfiguration configuration = mock(WormholesModConfiguration.class);
        WormholesSettings settings = MinecraftTestSettings.defaults();
        when(player.getUUID()).thenReturn(ALEX);
        when(player.getGameProfile()).thenReturn(new GameProfile(ALEX, "Alex"));
        when(runtime.running()).thenReturn(true);
        when(runtime.server()).thenReturn(server);
        when(runtime.access()).thenReturn(access);
        when(runtime.portals()).thenReturn(portals);
        when(runtime.configuration()).thenReturn(configuration);
        when(configuration.settings()).thenReturn(settings);
        when(server.getPlayerList()).thenReturn(players);
        when(players.getPlayer(ALEX)).thenReturn(player);
        MinecraftMenuHost host = new MinecraftMenuHost(runtime, new MinecraftMenuHost.Options(mock(MenuRenderer.class), mock(MenuCaptures.class)));
        return new Fixture(host, runtime, players, player, access, portals, settings);
    }

    private record Fixture(MinecraftMenuHost host, WormholesModRuntime runtime, PlayerList players, ServerPlayer player,
                           MinecraftAccessService access, MinecraftPortalRegistry portals, WormholesSettings settings) {
    }
}
