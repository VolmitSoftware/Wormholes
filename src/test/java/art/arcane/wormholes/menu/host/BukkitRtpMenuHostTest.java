package art.arcane.wormholes.menu.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.wormholes.PortalManager;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.menu.BukkitMenuViewer;
import art.arcane.wormholes.menu.MenuScope;
import art.arcane.wormholes.menu.MenuService;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.rtp.RtpOutcome;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.rtp.BukkitRtpRuntime;
import art.arcane.wormholes.portal.rtp.RtpDestination;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.EditorSnapshot;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.ManualAction;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.SoundMutation;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.StatusState;
import art.arcane.wormholes.portal.rtp.RtpSettings;
import art.arcane.wormholes.util.Cuboid;

class BukkitRtpMenuHostTest {
    private World overworld;
    private World pockets;
    private LocalPortal portal;
    private LocalPortal plain;
    private MenuViewer viewer;
    private BukkitRtpMenuHost host;
    private BukkitRtpRuntime previousRuntime;
    private MockedStatic<Bukkit> bukkit;

    @BeforeEach
    void setUp() {
        previousRuntime = Wormholes.rtpRuntime;
        Wormholes.rtpRuntime = null;
        overworld = world(NamespacedKey.minecraft("rtp-host"));
        pockets = world(new NamespacedKey("wormholes", "pockets"));
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getWorlds).thenReturn(List.of(overworld, pockets));
        portal = portal(PortalType.RTP);
        plain = portal(PortalType.PORTAL);
        PortalManager portals = mock(PortalManager.class);
        when(portals.getLocalPortal(portal.getId())).thenReturn(portal);
        when(portals.getLocalPortal(plain.getId())).thenReturn(plain);
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        viewer = new BukkitMenuViewer(player);
        host = new BukkitRtpMenuHost(portals, Logger.getAnonymousLogger());
    }

    @AfterEach
    void tearDown() {
        bukkit.close();
        Wormholes.rtpRuntime = previousRuntime;
    }

    @Test
    void snapshotsDescribeRtpPortalsAndSkipPocketWorlds() {
        EditorSnapshot snapshot = host.snapshot(portal.getId()).orElseThrow();

        assertEquals(portal.getRtpRevision(), snapshot.configurationRevision());
        assertEquals("minecraft:rtp-host", snapshot.settings().sourceWorldKey());
        assertEquals(List.of("minecraft:rtp-host"), snapshot.loadedWorlds().stream().map(world -> world.key()).toList());
        assertEquals(-63, snapshot.loadedWorlds().getFirst().minimumFeetY());
        assertEquals(318, snapshot.loadedWorlds().getFirst().maximumFeetY());
        assertEquals(StatusState.IDLE, snapshot.status().state());
        assertTrue(host.snapshot(plain.getId()).isEmpty());
        assertTrue(host.snapshot(UUID.randomUUID()).isEmpty());
        assertTrue(host.biomeOptions(plain.getId()).isEmpty());
    }

    @Test
    void mutationsApplyOnTheSourceRegionAndAdvanceTheRevision() {
        long revision = portal.getRtpRevision();

        RtpOutcome outcome = host.mutate(viewer, portal.getId(), revision, new SoundMutation(false)).join();

        assertEquals(RtpOutcome.applied(WormholesMessages.PORTAL_RTP_APPLIED), outcome);
        assertFalse(portal.getRtpSettings().isSoundEnabled());
        assertTrue(portal.getRtpRevision() > revision);
    }

    @Test
    void staleRevisionsChangeNothing() {
        long revision = portal.getRtpRevision();

        RtpOutcome outcome = host.mutate(viewer, portal.getId(), revision + 1L, new SoundMutation(false)).join();

        assertEquals(RtpOutcome.stale(), outcome);
        assertTrue(portal.getRtpSettings().isSoundEnabled());
        assertEquals(revision, portal.getRtpRevision());
    }

    @Test
    void rejectedMutationsReportTheirReason() {
        portal.getStructure().setWorld(world(NamespacedKey.minecraft("elsewhere")));

        RtpOutcome outcome = host.mutate(viewer, portal.getId(), portal.getRtpRevision(), new SoundMutation(false)).join();

        assertEquals(RtpOutcome.Status.REJECTED, outcome.status());
        assertEquals(WormholesMessages.PORTAL_RTP_SETTING_REJECTED, outcome.notice());
        assertEquals("source world does not match RTP settings", outcome.arguments().require("reason").value());
    }

    @Test
    void unexpectedFailuresOnTheSourceRegionFailTheFuture() {
        portal.getStructure().setWorld(null);

        CompletableFuture<RtpOutcome> outcome = host.mutate(viewer, portal.getId(), portal.getRtpRevision(), new SoundMutation(false));

        CompletionException failure = assertThrows(CompletionException.class, outcome::join);
        assertInstanceOf(NullPointerException.class, failure.getCause());
        assertTrue(portal.getRtpSettings().isSoundEnabled());
    }

    @Test
    void portalsOutsideRtpModeAnswerNotRtp() {
        assertEquals(RtpOutcome.notRtp(), host.mutate(viewer, plain.getId(), 0L, new SoundMutation(false)).join());
        assertEquals(RtpOutcome.notRtp(), host.reset(viewer, plain.getId(), 0L).join());
        assertEquals(RtpOutcome.notRtp(), host.manual(viewer, plain.getId(), 0L, ManualAction.REROLL).join());
        assertEquals(RtpOutcome.notRtp(), host.mutate(viewer, UUID.randomUUID(), 0L, new SoundMutation(false)).join());
    }

    @Test
    void appliedChangesRefreshThePortalsOpenMenus() {
        MenuService previousMenus = Wormholes.menus;
        MenuService menus = mock(MenuService.class);
        Wormholes.menus = menus;
        try {
            MenuScope.Portal scope = new MenuScope.Portal(portal.getId());
            host.mutate(viewer, portal.getId(), portal.getRtpRevision() + 1L, new SoundMutation(false)).join();
            verify(menus, never()).invalidate(scope);

            host.mutate(viewer, portal.getId(), portal.getRtpRevision(), new SoundMutation(false)).join();
            verify(menus).invalidate(scope);
            host.reset(viewer, portal.getId(), portal.getRtpRevision()).join();
            verify(menus, times(2)).invalidate(scope);
        } finally {
            Wormholes.menus = previousMenus;
        }
    }

    @Test
    void resetRestoresTheDefaults() {
        RtpSettings defaults = portal.getRtpSettings();
        host.mutate(viewer, portal.getId(), portal.getRtpRevision(), new SoundMutation(false)).join();

        RtpOutcome outcome = host.reset(viewer, portal.getId(), portal.getRtpRevision()).join();

        assertEquals(RtpOutcome.applied(WormholesMessages.PORTAL_RTP_RESET_DEFAULTS), outcome);
        assertEquals(defaults, portal.getRtpSettings());
        assertEquals(RtpOutcome.stale(), host.reset(viewer, portal.getId(), portal.getRtpRevision() + 1L).join());
    }

    @Test
    void manualActionsNeedTheRuntime() {
        assertEquals(RtpOutcome.rejected(WormholesMessages.PORTAL_RTP_RUNTIME_UNAVAILABLE, MessageArgs.empty()),
            host.manual(viewer, portal.getId(), portal.getRtpRevision(), ManualAction.REROLL).join());
        assertEquals(RtpOutcome.stale(), host.manual(viewer, portal.getId(), portal.getRtpRevision() + 1L, ManualAction.REROLL).join());
    }

    @Test
    void manualActionsReportTheRuntimeOutcome() {
        BukkitRtpRuntime runtime = mock(BukkitRtpRuntime.class);
        Wormholes.rtpRuntime = runtime;
        long revision = portal.getRtpRevision();

        when(runtime.requestManualReroll(portal.getId())).thenReturn(CompletableFuture.completedFuture(Boolean.TRUE));
        assertEquals(RtpOutcome.applied(WormholesMessages.PORTAL_RTP_REROLL_PREPARING),
            host.manual(viewer, portal.getId(), revision, ManualAction.REROLL).join());
        when(runtime.requestManualReroll(portal.getId())).thenReturn(CompletableFuture.completedFuture(Boolean.FALSE));
        assertEquals(RtpOutcome.Status.REJECTED, host.manual(viewer, portal.getId(), revision, ManualAction.REROLL).join().status());
        when(runtime.requestManualReroll(portal.getId())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("boom")));
        assertEquals(WormholesMessages.PORTAL_RTP_REROLL_FAILED, host.manual(viewer, portal.getId(), revision, ManualAction.REROLL).join().notice());

        when(runtime.requestPoolRebuild(portal.getId())).thenReturn(CompletableFuture.completedFuture(Set.<RtpDestination>of()));
        assertEquals(RtpOutcome.applied(WormholesMessages.PORTAL_RTP_POOL_REBUILDING),
            host.manual(viewer, portal.getId(), revision, ManualAction.REBUILD_POOL).join());
        when(runtime.requestPoolRebuild(portal.getId())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("boom")));
        assertEquals(WormholesMessages.PORTAL_RTP_POOL_FAILED, host.manual(viewer, portal.getId(), revision, ManualAction.REBUILD_POOL).join().notice());
    }

    private LocalPortal portal(PortalType type) {
        PortalStructure structure = new PortalStructure();
        structure.setWorld(overworld);
        structure.setArea(new Cuboid(new Location(overworld, 0.0D, 64.0D, 0.0D), new Location(overworld, 0.0D, 66.0D, 2.0D)));
        return new LocalPortal(UUID.randomUUID(), type, structure);
    }

    private static World world(NamespacedKey key) {
        UUID worldId = UUID.nameUUIDFromBytes(key.toString().getBytes(StandardCharsets.UTF_8));
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] {World.class}, (proxy, method, arguments) ->
            switch (method.getName()) {
                case "getName" -> key.getKey();
                case "getKey" -> key;
                case "getUID" -> worldId;
                case "getMinHeight" -> Integer.valueOf(-64);
                case "getMaxHeight" -> Integer.valueOf(320);
                case "getSeaLevel" -> Integer.valueOf(63);
                case "getEnvironment" -> World.Environment.NORMAL;
                case "toString" -> "RtpHostWorld[" + key + "]";
                case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
                case "equals" -> Boolean.valueOf(proxy == arguments[0]);
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }
}
