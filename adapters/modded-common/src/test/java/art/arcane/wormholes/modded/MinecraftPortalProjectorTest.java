package art.arcane.wormholes.modded;

import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.config.toml.MainConfig;
import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.config.toml.ProjectionConfig;
import art.arcane.wormholes.config.toml.RenderConfig;
import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.portal.BlackoutColor;
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

import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MinecraftPortalProjectorTest {
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
        when(access.destination(source)).thenReturn(target);
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
}
