package art.arcane.wormholes.modded.menu.host;

import art.arcane.optics.aperture.ApertureCells;
import art.arcane.optics.math.Vec3d;
import art.arcane.volmlib.util.localization.LocalizationCandidate;
import art.arcane.volmlib.util.localization.LocalizationSnapshot;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.PluralSelector;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.menu.MenuScope;
import art.arcane.wormholes.menu.MenuService;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.rtp.RtpOutcome;
import art.arcane.wormholes.modded.MinecraftLocalization;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.MinecraftPortalRegistry;
import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.menu.MinecraftMenuViewer;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.rtp.MinecraftRtpRuntime;
import art.arcane.wormholes.portal.rtp.RtpDestination;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.BiomeOption;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.EditorSnapshot;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.ManualAction;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.SoundMutation;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.StatusState;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.TargetWorldMutation;
import art.arcane.wormholes.portal.rtp.RtpSettings;
import art.arcane.wormholes.portal.rtp.RtpWorld;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MinecraftRtpMenuHostTest extends MinecraftTestBase {
    private static final UUID PORTAL = UUID.fromString("0a7d0000-0000-4000-8000-0000000000aa");
    private static final String OVERWORLD = "minecraft:overworld";

    private final AtomicReference<RtpSettings> settings = new AtomicReference<>();
    private MinecraftPortalRegistry portals;
    private MenuService menus;
    private MinecraftRtpRuntime rtp;
    private ServerPlayer player;
    private MinecraftPortal portal;
    private MenuViewer viewer;
    private MinecraftRtpMenuHost host;

    @Before
    public void setUp() {
        WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        portals = mock(MinecraftPortalRegistry.class);
        menus = mock(MenuService.class);
        rtp = mock(MinecraftRtpRuntime.class);
        player = mock(ServerPlayer.class);
        portal = mock(MinecraftPortal.class);
        MinecraftServer server = mock(MinecraftServer.class);
        ServerLevel level = mock(ServerLevel.class);
        MinecraftLocalization localization = mock(MinecraftLocalization.class);
        ApertureCells geometry = new ApertureCells();
        geometry.setBlocks(List.of(new Vec3d(4, 64, 8), new Vec3d(4, 65, 8)));
        settings.set(RtpSettings.defaults(world()));
        when(runtime.portals()).thenReturn(portals);
        when(runtime.menus()).thenReturn(menus);
        when(runtime.rtp()).thenReturn(rtp);
        when(runtime.server()).thenReturn(server);
        when(runtime.localization()).thenReturn(localization);
        when(localization.snapshot(null)).thenReturn(LocalizationSnapshot.create(
            LocalizationCandidate.english(WormholesMessages.catalog(), PluralSelector.oneOther())));
        when(server.getAllLevels()).thenReturn(List.of(level));
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(level.getMinY()).thenReturn(-64);
        when(level.getMaxY()).thenReturn(319);
        when(level.getSeaLevel()).thenReturn(63);
        when(portal.getId()).thenReturn(PORTAL);
        when(portal.getName()).thenReturn("Alpha");
        when(portal.getType()).thenReturn(PortalType.RTP);
        when(portal.getWorldKey()).thenReturn(OVERWORLD);
        when(portal.getGeometry()).thenReturn(geometry);
        when(portals.get(PORTAL)).thenReturn(portal);
        when(rtp.settings(portal)).thenAnswer(invocation -> settings.get());
        when(rtp.snapshot(PORTAL)).thenReturn(Optional.empty());
        doAnswer(invocation -> {
            settings.set(invocation.getArgument(0));
            return null;
        }).when(portal).setRtpSettings(any());
        when(portals.update(eq(player), eq(PORTAL), any())).thenAnswer(invocation -> {
            Consumer<MinecraftPortal> mutation = invocation.getArgument(2);
            mutation.accept(portal);
            return true;
        });
        viewer = new MinecraftMenuViewer(runtime, player);
        host = new MinecraftRtpMenuHost(runtime);
    }

    @Test
    public void biomesSortByPrettyNameAndSkipIrisKeys() {
        List<BiomeOption> options = MinecraftRtpMenuHost.biomeOptions(List.of(
            "minecraft:snowy_plains", "minecraft:badlands", "iris:custom", "minecraft:badlands"));

        assertEquals(2, options.size());
        assertEquals("minecraft:badlands", options.get(0).key());
        assertEquals("Badlands", options.get(0).displayName());
        assertEquals("Snowy Plains", options.get(1).displayName());
        assertFalse(options.get(1).irisBiome());
    }

    @Test
    public void snapshotsDescribeTheRtpPortalWithAStableRevision() {
        EditorSnapshot snapshot = host.snapshot(PORTAL).orElseThrow();

        assertEquals("RTP: Alpha", snapshot.title());
        assertEquals(OVERWORLD, snapshot.settings().sourceWorldKey());
        assertEquals(List.of(OVERWORLD), snapshot.loadedWorlds().stream().map(world -> world.key()).toList());
        assertEquals(StatusState.IDLE, snapshot.status().state());
        assertEquals(4.5D, snapshot.sourceCenterX(), 0.0D);
        assertEquals(snapshot.configurationRevision(), host.snapshot(PORTAL).orElseThrow().configurationRevision());
        assertTrue(host.snapshot(UUID.randomUUID()).isEmpty());
    }

    @Test
    public void mutationsApplyAndAdvanceTheRevisionWhileStaleRevisionsChangeNothing() {
        long revision = host.snapshot(PORTAL).orElseThrow().configurationRevision();

        assertEquals(RtpOutcome.applied(WormholesMessages.PORTAL_RTP_APPLIED), host.mutate(viewer, PORTAL, revision, new SoundMutation(false)).join());
        assertFalse(settings.get().isSoundEnabled());
        long next = host.snapshot(PORTAL).orElseThrow().configurationRevision();
        assertTrue(next > revision);

        assertEquals(RtpOutcome.stale(), host.mutate(viewer, PORTAL, revision, new SoundMutation(true)).join());
        assertFalse(settings.get().isSoundEnabled());

        assertEquals(RtpOutcome.applied(WormholesMessages.PORTAL_RTP_RESET_DEFAULTS), host.reset(viewer, PORTAL, next).join());
        assertEquals(RtpSettings.defaults(world()), settings.get());
    }

    @Test
    public void rejectedRefusedAndNonRtpPortalsReportTheirOutcome() {
        long revision = host.snapshot(PORTAL).orElseThrow().configurationRevision();

        RtpOutcome rejected = host.mutate(viewer, PORTAL, revision, new TargetWorldMutation("minecraft:missing")).join();
        assertEquals(RtpOutcome.Status.REJECTED, rejected.status());
        assertEquals(WormholesMessages.PORTAL_RTP_SETTING_REJECTED, rejected.notice());
        assertEquals("target world is not loaded: minecraft:missing", rejected.arguments().require("reason").value());

        when(portals.update(eq(player), eq(PORTAL), any())).thenReturn(false);
        assertEquals(RtpOutcome.Status.DENIED, host.mutate(viewer, PORTAL, revision, new SoundMutation(false)).join().status());

        when(portal.getType()).thenReturn(PortalType.PORTAL);
        assertEquals(RtpOutcome.notRtp(), host.mutate(viewer, PORTAL, revision, new SoundMutation(false)).join());
        assertEquals(RtpOutcome.notRtp(), host.manual(viewer, PORTAL, revision, ManualAction.REROLL).join());
        assertTrue(host.biomeOptions(PORTAL).isEmpty());
    }

    @Test
    public void appliedChangesRefreshThePortalsOpenMenus() {
        long revision = host.snapshot(PORTAL).orElseThrow().configurationRevision();
        MenuScope.Portal scope = new MenuScope.Portal(PORTAL);

        assertEquals(RtpOutcome.stale(), host.mutate(viewer, PORTAL, revision + 1L, new SoundMutation(false)).join());
        host.mutate(viewer, PORTAL, revision, new TargetWorldMutation("minecraft:missing")).join();
        verify(menus, never()).invalidate(scope);

        host.mutate(viewer, PORTAL, revision, new SoundMutation(false)).join();
        verify(menus).invalidate(scope);
        host.reset(viewer, PORTAL, host.snapshot(PORTAL).orElseThrow().configurationRevision()).join();
        verify(menus, times(2)).invalidate(scope);

        when(portals.update(eq(player), eq(PORTAL), any())).thenReturn(false);
        host.mutate(viewer, PORTAL, host.snapshot(PORTAL).orElseThrow().configurationRevision(), new SoundMutation(true)).join();
        verify(menus, times(2)).invalidate(scope);
    }

    @Test
    public void revisionsRestartOnceThePortalIsGoneOrNoLongerRtp() {
        long first = host.snapshot(PORTAL).orElseThrow().configurationRevision();
        host.mutate(viewer, PORTAL, first, new SoundMutation(false)).join();
        assertTrue(host.snapshot(PORTAL).orElseThrow().configurationRevision() > first);

        when(portal.getType()).thenReturn(PortalType.PORTAL);
        assertTrue(host.snapshot(PORTAL).isEmpty());
        when(portal.getType()).thenReturn(PortalType.RTP);
        assertEquals(first, host.snapshot(PORTAL).orElseThrow().configurationRevision());

        host.mutate(viewer, PORTAL, first, new SoundMutation(true)).join();
        when(portals.get(PORTAL)).thenReturn(null);
        assertTrue(host.snapshot(PORTAL).isEmpty());
        when(portals.get(PORTAL)).thenReturn(portal);
        assertEquals(first, host.snapshot(PORTAL).orElseThrow().configurationRevision());
    }

    @Test
    public void manualActionsReportTheRuntimeOutcome() {
        long revision = host.snapshot(PORTAL).orElseThrow().configurationRevision();

        when(rtp.reroll(PORTAL)).thenReturn(CompletableFuture.completedFuture(Boolean.TRUE));
        assertEquals(RtpOutcome.applied(WormholesMessages.PORTAL_RTP_REROLL_PREPARING), host.manual(viewer, PORTAL, revision, ManualAction.REROLL).join());
        when(rtp.reroll(PORTAL)).thenReturn(CompletableFuture.completedFuture(Boolean.FALSE));
        assertEquals(RtpOutcome.rejected(WormholesMessages.PORTAL_RTP_REROLL_UNAVAILABLE, MessageArgs.empty()),
            host.manual(viewer, PORTAL, revision, ManualAction.REROLL).join());
        when(rtp.reroll(PORTAL)).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("boom")));
        assertEquals(WormholesMessages.PORTAL_RTP_REROLL_FAILED, host.manual(viewer, PORTAL, revision, ManualAction.REROLL).join().notice());
        when(rtp.rebuild(PORTAL)).thenReturn(CompletableFuture.completedFuture(Set.<RtpDestination>of()));
        assertEquals(RtpOutcome.applied(WormholesMessages.PORTAL_RTP_POOL_REBUILDING),
            host.manual(viewer, PORTAL, revision, ManualAction.REBUILD_POOL).join());
        when(rtp.rebuild(PORTAL)).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("boom")));
        assertEquals(WormholesMessages.PORTAL_RTP_POOL_FAILED, host.manual(viewer, PORTAL, revision, ManualAction.REBUILD_POOL).join().notice());
        assertEquals(RtpOutcome.stale(), host.manual(viewer, PORTAL, revision + 1L, ManualAction.REROLL).join());
    }

    private static RtpWorld world() {
        return new RtpWorld(UUID.nameUUIDFromBytes(OVERWORLD.getBytes(StandardCharsets.UTF_8)), OVERWORLD, -64, 320, 63);
    }
}
