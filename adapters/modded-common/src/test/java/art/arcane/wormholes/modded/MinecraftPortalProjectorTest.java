package art.arcane.wormholes.modded;

import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.config.toml.MainConfig;
import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.config.toml.ProjectionConfig;
import art.arcane.wormholes.config.toml.RenderConfig;
import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.portal.BlackoutColor;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.rtp.MinecraftRtpRuntime;
import art.arcane.wormholes.render.FidelitySettings;
import art.arcane.wormholes.render.plate.PlateCaptureJob;
import art.arcane.wormholes.render.plate.ViewPlateBuilder;
import art.arcane.wormholes.render.plate.ViewPlateCache;
import art.arcane.wormholes.portal.RemotePortal;
import art.arcane.wormholes.network.view.RemoteViewCache;
import art.arcane.wormholes.network.view.ViewBox;
import art.arcane.wormholes.network.view.ViewSubscriptionManager;
import art.arcane.wormholes.render.ProjectedBlockClaim;
import art.arcane.wormholes.render.view.ProjectionContentView;
import art.arcane.wormholes.render.view.RemoteProjectionView;
import net.minecraft.network.syncher.SynchedEntityData;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalGeometry;
import art.arcane.wormholes.portal.ProjectionRenderMode;
import art.arcane.wormholes.render.ProjectionCellKey;
import art.arcane.wormholes.render.ProjectionWorldChangeTracker;
import art.arcane.wormholes.render.ProjectorRecursivePortals;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.invocation.Invocation;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.intThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;

public class MinecraftPortalProjectorTest {
    private static final UUID LOCAL_WORLD = UUID.nameUUIDFromBytes("projector-local".getBytes(StandardCharsets.UTF_8));
    private static final UUID DESTINATION_WORLD = UUID.nameUUIDFromBytes("projector-destination".getBytes(StandardCharsets.UTF_8));

    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void nativeObserverRunsSharedCellScanAndCommitsClaims() {
        WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        WormholesModConfiguration configuration = mock(WormholesModConfiguration.class);
        MinecraftPortalRegistry registry = mock(MinecraftPortalRegistry.class);
        MinecraftProjectorPortalAccess access = mock(MinecraftProjectorPortalAccess.class);
        ServerPlayer player = mock(ServerPlayer.class);
        MinecraftServer server = mock(MinecraftServer.class);
        PlayerList players = mock(PlayerList.class);
        when(runtime.server()).thenReturn(server);
        stubProjections(runtime, new ProjectionWorldChangeTracker());
        when(server.getPlayerList()).thenReturn(players);
        when(players.getViewDistance()).thenReturn(8);
        when(player.requestedViewDistance()).thenReturn(8);
        ServerLevel world = mock(ServerLevel.class);
        MinecraftPortal source = portal(0.0D);
        MinecraftPortal target = portal(10.0D);
        MinecraftProjectionWorldView view = mock(MinecraftProjectionWorldView.class);
        ProjectionConfig projection = new ProjectionConfig();
        projection.maxProjectedCells = 10_000;
        when(runtime.configuration()).thenReturn(configuration);
        when(configuration.settings()).thenReturn(new WormholesSettings(new MainConfig(), projection, new RenderConfig(), new NetworkConfig()));
        when(runtime.portals()).thenReturn(registry);
        when(registry.get(source.getId())).thenReturn(source);
        when(player.level()).thenReturn(world);
        when(player.getEyePosition()).thenReturn(new Vec3(1.0D, 65.0D, 4.0D));
        when(access.eligible(source)).thenReturn(true);
        when(access.current(source)).thenReturn(true);
        when(access.world(source)).thenReturn(world);
        when(access.world(target)).thenReturn(world);
        when(access.projectionDestination(source)).thenReturn(target);
        when(access.portals()).thenReturn(List.of());
        when(access.createRecursiveIndex()).thenReturn(new ProjectorRecursivePortals<>(access,
            () -> new ProjectorRecursivePortals.Options(0.75D, 64.0D)));
        when(view.getWorld()).thenReturn(world);
        when(view.getMinHeight()).thenReturn(-64);
        when(view.getMaxHeight()).thenReturn(320);
        when(view.isChunkReady(anyInt(), anyInt())).thenReturn(true);
        BlockState stone = Blocks.STONE.defaultBlockState();
        when(view.sampleBlockData(anyInt(), anyInt(), anyInt())).thenReturn(stone);
        when(view.sampleMaterial(anyInt(), anyInt(), anyInt())).thenReturn(stone);
        try (MinecraftPortalProjector projector = new MinecraftPortalProjector(runtime,
            new MinecraftPortalProjector.Context(player, source, ignored -> view, access, null))) {
            assertEquals(MinecraftPortalProjector.Result.READY, projector.update(1L, Long.MAX_VALUE));
            assertFalse(projector.claimDelta().claims().isEmpty());
            assertTrue(projector.scan().hasPending());
            projector.commit();
            assertFalse(projector.scan().hasPending());
            assertEquals(MinecraftPortalProjector.Result.IDLE, projector.update(1L, Long.MAX_VALUE));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void nativeRemoteDestinationUsesSubscriptionAndSharedDecodedSlice() {
        WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        WormholesModConfiguration configuration = mock(WormholesModConfiguration.class);
        MinecraftPortalRegistry registry = mock(MinecraftPortalRegistry.class);
        MinecraftNetworkService network = mock(MinecraftNetworkService.class);
        MinecraftProjectorPortalAccess access = mock(MinecraftProjectorPortalAccess.class);
        ServerPlayer player = mock(ServerPlayer.class);
        MinecraftServer server = mock(MinecraftServer.class);
        PlayerList players = mock(PlayerList.class);
        when(runtime.server()).thenReturn(server);
        stubProjections(runtime, new ProjectionWorldChangeTracker());
        when(server.getPlayerList()).thenReturn(players);
        when(players.getViewDistance()).thenReturn(8);
        when(player.requestedViewDistance()).thenReturn(8);
        ServerLevel world = mock(ServerLevel.class);
        MinecraftPortal source = portal(0.0D);
        RemotePortal target = mock(RemotePortal.class);
        UUID targetId = UUID.randomUUID();
        when(target.getId()).thenReturn(targetId);
        when(target.getFrame()).thenReturn(PortalFrame.canonical(Direction.S));
        when(target.getOrigin()).thenReturn(new GeometryVector(11.0D, 65.0D, 0.0D));
        when(source.getTunnelType()).thenReturn("UNIVERSAL");
        when(source.getDestinationServer()).thenReturn("example-peer");
        when(source.getNetworkViewUnsubscribeGraceSeconds()).thenReturn(30);
        when(source.getNetworkViewFallbackBlock()).thenReturn("minecraft:air");
        MinecraftProjectionWorldView local = mock(MinecraftProjectionWorldView.class);
        ProjectionConfig projection = new ProjectionConfig();
        projection.maxProjectedCells = 10_000;
        when(runtime.configuration()).thenReturn(configuration);
        when(configuration.settings()).thenReturn(new WormholesSettings(new MainConfig(), projection, new RenderConfig(), new NetworkConfig()));
        when(runtime.portals()).thenReturn(registry);
        when(runtime.network()).thenReturn(network);
        when(registry.get(source.getId())).thenReturn(source);
        when(player.level()).thenReturn(world);
        when(player.getEyePosition()).thenReturn(new Vec3(1.0D, 65.0D, 4.0D));
        when(access.eligible(source)).thenReturn(true);
        when(access.current(source)).thenReturn(true);
        when(access.world(source)).thenReturn(world);
        when(access.remoteDestination(source)).thenReturn(target);
        when(access.createRecursiveIndex()).thenReturn(new ProjectorRecursivePortals<>(access,
            () -> new ProjectorRecursivePortals.Options(0.75D, 64.0D)));
        when(local.getWorld()).thenReturn(world);
        when(local.getMinHeight()).thenReturn(-64);
        when(local.getMaxHeight()).thenReturn(320);
        when(local.isChunkReady(anyInt(), anyInt())).thenReturn(true);
        when(local.sampleBlockData(anyInt(), anyInt(), anyInt())).thenReturn(Blocks.AIR.defaultBlockState());
        when(local.sampleMaterial(anyInt(), anyInt(), anyInt())).thenReturn(Blocks.AIR.defaultBlockState());
        ViewSubscriptionManager<BlockState, SynchedEntityData.DataValue<?>, MinecraftPacketBlobs.Equipment> subscriptions = mock(ViewSubscriptionManager.class);
        RemoteViewCache.RemoteView<BlockState, SynchedEntityData.DataValue<?>, MinecraftPacketBlobs.Equipment> remote = mock(RemoteViewCache.RemoteView.class);
        RemoteViewCache.DecodedSlice<BlockState> slice = mock(RemoteViewCache.DecodedSlice.class);
        when(network.subscriptions()).thenReturn(subscriptions);
        when(subscriptions.touch("example-peer", targetId, 30)).thenReturn(remote);
        when(remote.getBox()).thenReturn(new ViewBox(-128, -64, -128, 128, 319, 128));
        when(remote.getRevision()).thenReturn(1L);
        when(remote.sliceAt(anyInt(), anyInt())).thenReturn(slice);
        when(slice.blockAt(anyInt(), anyInt(), anyInt())).thenReturn(Blocks.GOLD_BLOCK.defaultBlockState());
        try (MinecraftPortalProjector projector = new MinecraftPortalProjector(runtime,
            new MinecraftPortalProjector.Context(player, source, ignored -> local, access, null))) {
            assertEquals(MinecraftPortalProjector.Result.READY, projector.update(1L, Long.MAX_VALUE));
            assertFalse(projector.claimDelta().claims().isEmpty());
            boolean remoteClaim = false;
            for (ProjectedBlockClaim<BlockState, ProjectionContentView<BlockState, BlockState>> claim : projector.claimDelta().claims().values()) {
                if (claim.getData().is(Blocks.GOLD_BLOCK)) {
                    assertTrue(claim.getLightView() instanceof RemoteProjectionView<?, ?, ?, ?>);
                    remoteClaim = true;
                }
            }
            assertTrue(remoteClaim);
            verify(subscriptions).touch("example-peer", targetId, 30);
            projector.commit();
            assertEquals(MinecraftPortalProjector.Result.IDLE, projector.update(1L, Long.MAX_VALUE));
        }
    }

    @Test
    public void destinationChurnOutsideTheFootprintKeepsSamplesAndPendingPasses() {
        Scene scene = scene(Blocks.STONE.defaultBlockState(), Blocks.AIR.defaultBlockState());
        try (MinecraftPortalProjector projector = scene.projector()) {
            assertEquals(MinecraftPortalProjector.Result.PENDING, projector.update(1L, 0L));
            churnOutsideFootprint(scene);
            assertEquals(MinecraftPortalProjector.Result.READY, projector.update(2L, Long.MAX_VALUE));
            assertEquals(1L, projector.passCount());
            projector.commit();
            for (long tick = 3L; tick <= 22L; tick++) {
                churnOutsideFootprint(scene);
                assertEquals(0, destinationSamples(scene, projector, tick));
            }
            assertEquals(21L, projector.passCount());
        }
    }

    @Test
    public void destinationChangeInsideTheFootprintResamplesAndSettles() {
        Scene scene = scene(Blocks.STONE.defaultBlockState(), Blocks.AIR.defaultBlockState());
        try (MinecraftPortalProjector projector = scene.projector()) {
            settle(projector, 1L);
            assertEquals(0, destinationSamples(scene, projector, 2L));
            long remoteKey = projector.scan().claims().values().iterator().next().getLightRemoteKey();
            scene.changes().markChanged(DESTINATION_WORLD, ProjectionCellKey.unpackX(remoteKey), ProjectionCellKey.unpackY(remoteKey),
                ProjectionCellKey.unpackZ(remoteKey));
            assertTrue(destinationSamples(scene, projector, 3L) > 0);
            settle(projector, 4L);
            for (long tick = 5L; tick <= 16L; tick++) {
                assertEquals(0, destinationSamples(scene, projector, tick));
            }
        }
    }

    @Test
    public void localCellChangeReleasesItsClaimOnlyOnceMarked() {
        BlockState air = Blocks.AIR.defaultBlockState();
        Scene scene = scene(air, Blocks.STONE.defaultBlockState());
        try (MinecraftPortalProjector projector = scene.projector()) {
            settle(projector, 1L);
            settle(projector, 2L);
            long cell = projector.scan().claims().keySet().iterator().nextLong();
            int x = ProjectionCellKey.unpackX(cell);
            int y = ProjectionCellKey.unpackY(cell);
            int z = ProjectionCellKey.unpackZ(cell);
            when(scene.local().sampleMaterial(x, y, z)).thenReturn(air);
            when(scene.local().sampleBlockData(x, y, z)).thenReturn(air);
            scene.changes().markChanged(LOCAL_WORLD, x + 1_600, y, z + 1_600);
            settle(projector, 3L);
            assertTrue(projector.scan().claims().containsKey(cell));
            scene.changes().markChanged(LOCAL_WORLD, x, y, z);
            settle(projector, 4L);
            assertFalse(projector.scan().claims().containsKey(cell));
        }
    }

    @Test
    public void localDestinationsScheduleCaptureJobsInsteadOfServerSteps() {
        PlateFixture fixture = plateFixture(PortalType.PORTAL, 48);
        List<ViewPlateBuilder.Job<BlockState, ServerLevel>> scheduled = new ArrayList<>();
        ViewPlateCache<BlockState, ServerLevel> cache = new ViewPlateCache<>(FidelitySettings.plateMaxBytes, scheduled::add);
        try (MinecraftPortalProjector projector = fixture.projector(fixture.player(), cache)) {
            assertEquals(MinecraftPortalProjector.Result.READY, projector.update(1L, Long.MAX_VALUE));
        }
        assertEquals(1, scheduled.size());
        assertTrue(scheduled.get(0) instanceof PlateCaptureJob<?, ?, ?>);
        assertEquals(0L, scheduled.get(0).key().targetIdentity());
        assertSame(fixture.view(), scheduled.get(0).key().destinationViewIdentity());
        assertTrue(((PlateCaptureJob<?, ?, ?>) scheduled.get(0)).pendingChunks() > 0);
        assertTrue(scheduled.get(0).predictedBytes() > 0L);
        verify(fixture.runtime(), never()).schedule(any(), anyLong());
    }

    @Test
    public void plateLateralPadIsClampedByTheFidelitySetting() {
        int clamp = FidelitySettings.plateLateralClampBlocks;
        try {
            FidelitySettings.plateLateralClampBlocks = 8;
            long clamped = predictedPlateBytes(plateFixture(PortalType.PORTAL, 48));
            FidelitySettings.plateLateralClampBlocks = 64;
            long padded = predictedPlateBytes(plateFixture(PortalType.PORTAL, 8));
            long wide = predictedPlateBytes(plateFixture(PortalType.PORTAL, 48));
            assertEquals(padded, clamped);
            assertTrue(wide > clamped);
        } finally {
            FidelitySettings.plateLateralClampBlocks = clamp;
        }
    }

    @Test
    public void rtpViewersOnOneRouteShareOnePlate() {
        PlateFixture fixture = plateFixture(PortalType.RTP, 48);
        when(fixture.rtp().plateIdentity(any(), any())).thenReturn(77L);
        List<ViewPlateBuilder.Job<BlockState, ServerLevel>> scheduled = new ArrayList<>();
        ViewPlateCache<BlockState, ServerLevel> cache = new ViewPlateCache<>(FidelitySettings.plateMaxBytes, scheduled::add);
        try (MinecraftPortalProjector first = fixture.projector(fixture.player(), cache);
             MinecraftPortalProjector second = fixture.projector(fixture.viewer(), cache)) {
            assertEquals(MinecraftPortalProjector.Result.READY, first.update(1L, Long.MAX_VALUE));
            assertEquals(MinecraftPortalProjector.Result.READY, second.update(1L, Long.MAX_VALUE));
        }
        assertEquals(1, scheduled.size());
        assertEquals(77L, scheduled.get(0).key().targetIdentity());
        assertEquals(fixture.portal().getId(), scheduled.get(0).key().portalId());
        verify(fixture.rtp()).plateIdentity(fixture.player(), fixture.portal());
        verify(fixture.rtp()).plateIdentity(fixture.viewer(), fixture.portal());
    }

    @Test
    public void rtpRoutesKeepDistinctPlatesAndTheGateDisablesThem() {
        PlateFixture fixture = plateFixture(PortalType.RTP, 48);
        when(fixture.rtp().plateIdentity(fixture.player(), fixture.portal())).thenReturn(77L);
        when(fixture.rtp().plateIdentity(fixture.viewer(), fixture.portal())).thenReturn(78L);
        List<ViewPlateBuilder.Job<BlockState, ServerLevel>> scheduled = new ArrayList<>();
        ViewPlateCache<BlockState, ServerLevel> cache = new ViewPlateCache<>(FidelitySettings.plateMaxBytes, scheduled::add);
        try (MinecraftPortalProjector first = fixture.projector(fixture.player(), cache);
             MinecraftPortalProjector second = fixture.projector(fixture.viewer(), cache)) {
            assertEquals(MinecraftPortalProjector.Result.READY, first.update(1L, Long.MAX_VALUE));
            assertEquals(MinecraftPortalProjector.Result.READY, second.update(1L, Long.MAX_VALUE));
        }
        assertEquals(2, scheduled.size());
        assertEquals(77L, scheduled.get(0).key().targetIdentity());
        assertEquals(78L, scheduled.get(1).key().targetIdentity());
        assertNotEquals(scheduled.get(0).key(), scheduled.get(1).key());
        assertSame(scheduled.get(0).key().destinationViewIdentity(), scheduled.get(1).key().destinationViewIdentity());
        when(fixture.rtp().plateIdentity(fixture.player(), fixture.portal())).thenReturn(0L);
        try (MinecraftPortalProjector unresolved = fixture.projector(fixture.player(), cache)) {
            assertEquals(MinecraftPortalProjector.Result.READY, unresolved.update(1L, Long.MAX_VALUE));
        }
        assertEquals(2, scheduled.size());
        when(fixture.rtp().plateIdentity(fixture.player(), fixture.portal())).thenReturn(79L);
        boolean gate = FidelitySettings.rtpPlates;
        FidelitySettings.rtpPlates = false;
        try (MinecraftPortalProjector gated = fixture.projector(fixture.player(), cache)) {
            assertEquals(MinecraftPortalProjector.Result.READY, gated.update(1L, Long.MAX_VALUE));
        } finally {
            FidelitySettings.rtpPlates = gate;
        }
        assertEquals(2, scheduled.size());
    }

    private static int destinationSamples(Scene scene, MinecraftPortalProjector projector, long tick) {
        clearInvocations(scene.destination());
        settle(projector, tick);
        int samples = 0;
        for (Invocation invocation : mockingDetails(scene.destination()).getInvocations()) {
            if (invocation.getMethod().getName().equals("sampleBlockData")) {
                samples++;
            }
        }
        return samples;
    }

    private static void churnOutsideFootprint(Scene scene) {
        for (int change = 0; change < 5_000; change++) {
            scene.changes().markChanged(DESTINATION_WORLD, 1_600 + (change & 63), 64, 1_600 + (change >> 6));
        }
    }

    private static void settle(MinecraftPortalProjector projector, long tick) {
        MinecraftPortalProjector.Result result = projector.update(tick, Long.MAX_VALUE);
        assertEquals(MinecraftPortalProjector.Result.READY, result);
        projector.commit();
    }

    private static Scene scene(BlockState destinationBlock, BlockState behindSource) {
        WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        WormholesModConfiguration configuration = mock(WormholesModConfiguration.class);
        MinecraftProjectorPortalAccess access = mock(MinecraftProjectorPortalAccess.class);
        ServerPlayer player = mock(ServerPlayer.class);
        MinecraftServer server = mock(MinecraftServer.class);
        PlayerList players = mock(PlayerList.class);
        ProjectionWorldChangeTracker changes = new ProjectionWorldChangeTracker();
        when(runtime.server()).thenReturn(server);
        stubProjections(runtime, changes);
        when(server.getPlayerList()).thenReturn(players);
        when(players.getViewDistance()).thenReturn(8);
        when(player.requestedViewDistance()).thenReturn(8);
        ServerLevel sourceWorld = mock(ServerLevel.class);
        ServerLevel targetWorld = mock(ServerLevel.class);
        MinecraftPortal source = portal(0.0D);
        MinecraftPortal target = portal(10.0D);
        when(source.getNetworkViewDepth()).thenReturn(64);
        when(source.getNetworkViewHeartbeatTicks()).thenReturn(60);
        when(source.getNetworkViewEntityIntervalTicks()).thenReturn(10);
        when(source.getNetworkViewUnsubscribeGraceSeconds()).thenReturn(30);
        ProjectionConfig projection = new ProjectionConfig();
        projection.maxProjectedCells = 10_000;
        when(runtime.configuration()).thenReturn(configuration);
        when(configuration.settings()).thenReturn(new WormholesSettings(new MainConfig(), projection, new RenderConfig(), new NetworkConfig()));
        when(player.level()).thenReturn(sourceWorld);
        when(player.getEyePosition()).thenReturn(new Vec3(1.0D, 65.0D, 4.0D));
        when(access.eligible(source)).thenReturn(true);
        when(access.current(source)).thenReturn(true);
        when(access.world(source)).thenReturn(sourceWorld);
        when(access.world(target)).thenReturn(targetWorld);
        when(access.projectionDestination(source)).thenReturn(target);
        when(access.portals()).thenReturn(List.of());
        when(access.createRecursiveIndex()).thenReturn(new ProjectorRecursivePortals<>(access,
            () -> new ProjectorRecursivePortals.Options(0.75D, 64.0D)));
        MinecraftProjectionWorldView local = view(sourceWorld, LOCAL_WORLD, Blocks.AIR.defaultBlockState());
        when(local.sampleBlockData(anyInt(), anyInt(), intThat(z -> z < 0))).thenReturn(behindSource);
        when(local.sampleMaterial(anyInt(), anyInt(), intThat(z -> z < 0))).thenReturn(behindSource);
        MinecraftProjectionWorldView destination = view(targetWorld, DESTINATION_WORLD, destinationBlock);
        MinecraftPortalProjector.Context context = new MinecraftPortalProjector.Context(player, source,
            world -> world == sourceWorld ? local : destination, access, null);
        return new Scene(runtime, context, changes, local, destination);
    }

    private static MinecraftProjectionWorldView view(ServerLevel world, UUID worldId, BlockState block) {
        MinecraftProjectionWorldView view = mock(MinecraftProjectionWorldView.class);
        when(view.getWorld()).thenReturn(world);
        when(view.worldId()).thenReturn(worldId);
        when(view.getMinHeight()).thenReturn(-64);
        when(view.getMaxHeight()).thenReturn(320);
        when(view.isChunkReady(anyInt(), anyInt())).thenReturn(true);
        when(view.sampleBlockData(anyInt(), anyInt(), anyInt())).thenReturn(block);
        when(view.sampleMaterial(anyInt(), anyInt(), anyInt())).thenReturn(block);
        return view;
    }

    private static void stubProjections(WormholesModRuntime runtime, ProjectionWorldChangeTracker changes) {
        MinecraftProjectionService projections = mock(MinecraftProjectionService.class);
        when(projections.changes()).thenReturn(changes);
        when(runtime.projections()).thenReturn(projections);
    }

    private static long predictedPlateBytes(PlateFixture fixture) {
        List<ViewPlateBuilder.Job<BlockState, ServerLevel>> scheduled = new ArrayList<>();
        ViewPlateCache<BlockState, ServerLevel> cache = new ViewPlateCache<>(FidelitySettings.plateMaxBytes, scheduled::add);
        try (MinecraftPortalProjector projector = fixture.projector(fixture.player(), cache)) {
            assertEquals(MinecraftPortalProjector.Result.READY, projector.update(1L, Long.MAX_VALUE));
        }
        assertEquals(1, scheduled.size());
        return scheduled.get(0).predictedBytes();
    }

    private static PlateFixture plateFixture(PortalType type, int lateralPad) {
        WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        WormholesModConfiguration configuration = mock(WormholesModConfiguration.class);
        MinecraftPortalRegistry registry = mock(MinecraftPortalRegistry.class);
        MinecraftProjectorPortalAccess access = mock(MinecraftProjectorPortalAccess.class);
        MinecraftProjectionService projections = mock(MinecraftProjectionService.class);
        MinecraftRtpRuntime rtp = mock(MinecraftRtpRuntime.class);
        MinecraftServer server = mock(MinecraftServer.class);
        PlayerList players = mock(PlayerList.class);
        ServerLevel world = mock(ServerLevel.class);
        when(runtime.server()).thenReturn(server);
        when(server.getPlayerList()).thenReturn(players);
        when(players.getViewDistance()).thenReturn(8);
        MinecraftPortal source = portal(0.0D);
        MinecraftPortal target = portal(10.0D);
        when(source.getType()).thenReturn(type);
        when(source.getNetworkViewLateralPad()).thenReturn(lateralPad);
        MinecraftProjectionWorldView view = mock(MinecraftProjectionWorldView.class);
        ProjectionConfig projection = new ProjectionConfig();
        projection.maxProjectedCells = 10_000;
        when(runtime.configuration()).thenReturn(configuration);
        when(configuration.settings()).thenReturn(new WormholesSettings(new MainConfig(), projection, new RenderConfig(), new NetworkConfig()));
        when(runtime.portals()).thenReturn(registry);
        when(runtime.projections()).thenReturn(projections);
        when(runtime.rtp()).thenReturn(rtp);
        when(projections.changes()).thenReturn(new ProjectionWorldChangeTracker());
        when(registry.get(source.getId())).thenReturn(source);
        when(access.eligible(source)).thenReturn(true);
        when(access.current(source)).thenReturn(true);
        when(access.world(source)).thenReturn(world);
        when(access.world(target)).thenReturn(world);
        when(access.projectionDestination(source)).thenReturn(target);
        when(access.portals()).thenReturn(List.of());
        when(access.createRecursiveIndex()).thenAnswer(ignored -> new ProjectorRecursivePortals<>(access,
            () -> new ProjectorRecursivePortals.Options(0.75D, 64.0D)));
        when(view.getWorld()).thenReturn(world);
        when(view.worldId()).thenReturn(UUID.randomUUID());
        when(view.getMinHeight()).thenReturn(-64);
        when(view.getMaxHeight()).thenReturn(320);
        when(view.isChunkReady(anyInt(), anyInt())).thenReturn(true);
        BlockState stone = Blocks.STONE.defaultBlockState();
        when(view.sampleBlockData(anyInt(), anyInt(), anyInt())).thenReturn(stone);
        when(view.sampleMaterial(anyInt(), anyInt(), anyInt())).thenReturn(stone);
        return new PlateFixture(runtime, access, viewer(world), viewer(world), source, view, rtp);
    }

    private static ServerPlayer viewer(ServerLevel world) {
        ServerPlayer player = mock(ServerPlayer.class);
        when(player.requestedViewDistance()).thenReturn(8);
        when(player.level()).thenReturn(world);
        when(player.getEyePosition()).thenReturn(new Vec3(1.0D, 65.0D, 4.0D));
        when(player.getUUID()).thenReturn(UUID.randomUUID());
        return player;
    }

    private record PlateFixture(WormholesModRuntime runtime, MinecraftProjectorPortalAccess access, ServerPlayer player,
                                ServerPlayer viewer, MinecraftPortal portal, MinecraftProjectionWorldView view, MinecraftRtpRuntime rtp) {
        MinecraftPortalProjector projector(ServerPlayer observer, ViewPlateCache<BlockState, ServerLevel> cache) {
            return new MinecraftPortalProjector(runtime, new MinecraftPortalProjector.Context(observer, portal, ignored -> view, access, cache));
        }
    }

    private static MinecraftPortal portal(double x) {
        MinecraftPortal portal = mock(MinecraftPortal.class);
        PortalGeometry geometry = new PortalGeometry();
        geometry.setArea(new AxisAlignedBB(x, x + 2.0D, 64.0D, 67.0D, 0.0D, 1.0D));
        when(portal.getId()).thenReturn(UUID.randomUUID());
        when(portal.getGeometry()).thenReturn(geometry);
        when(portal.getFrame()).thenReturn(PortalFrame.canonical(Direction.S));
        when(portal.getOrigin()).thenReturn(new GeometryVector(x + 1.0D, 65.0D, 0.0D));
        when(portal.getNetworkViewDepth()).thenReturn(8);
        when(portal.getBlackoutColor()).thenReturn(BlackoutColor.BLACK);
        when(portal.getRenderMode()).thenReturn(ProjectionRenderMode.PANOPTIC);
        return portal;
    }

    private record Scene(WormholesModRuntime runtime, MinecraftPortalProjector.Context context, ProjectionWorldChangeTracker changes,
                         MinecraftProjectionWorldView local, MinecraftProjectionWorldView destination) {
        private MinecraftPortalProjector projector() {
            return new MinecraftPortalProjector(runtime, context);
        }
    }
}
