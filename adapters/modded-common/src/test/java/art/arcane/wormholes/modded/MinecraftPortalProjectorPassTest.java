package art.arcane.wormholes.modded;

import art.arcane.optics.aperture.ApertureCells;
import art.arcane.optics.fidelity.AtmosphereMode;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.QuarterTurn;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.CellKeys;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.recursion.RecursiveEndpoints;
import art.arcane.optics.scan.PassInputs;
import art.arcane.optics.scan.PassPlanner;
import art.arcane.optics.view.WorldChangeTracker;
import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.config.toml.MainConfig;
import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.config.toml.ProjectionConfig;
import art.arcane.wormholes.config.toml.RenderConfig;
import art.arcane.wormholes.portal.BlackoutColor;
import art.arcane.wormholes.portal.ProjectionRenderMode;
import art.arcane.wormholes.render.FidelitySettings;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;
import org.mockito.invocation.Invocation;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

public class MinecraftPortalProjectorPassTest extends MinecraftTestBase {
    private static final UUID LOCAL_WORLD = UUID.nameUUIDFromBytes("pass-local".getBytes(StandardCharsets.UTF_8));
    private static final UUID DESTINATION_WORLD = UUID.nameUUIDFromBytes("pass-destination".getBytes(StandardCharsets.UTF_8));

    @Test
    public void anUnchangedPassReusesWithoutScanning() {
        Scene scene = scene();
        try (MinecraftPortalProjector projector = scene.projector()) {
            settle(projector, 1L);
            clearInvocations(scene.destination());

            assertEquals(MinecraftPortalProjector.Result.IDLE, projector.update(2L, Long.MAX_VALUE));
            assertEquals(0, samples(scene));
            assertEquals(2L, projector.passCount());
        }
    }

    @Test
    public void smallEyeDriftKeepsReusing() {
        Scene scene = scene();
        try (MinecraftPortalProjector projector = scene.projector()) {
            settle(projector, 1L);
            scene.eye().set(new Vec3(1.1D, 65.0D, 4.0D));

            assertEquals(MinecraftPortalProjector.Result.IDLE, projector.update(2L, Long.MAX_VALUE));
        }
    }

    @Test
    public void meaningfulEyeMovementRescans() {
        Scene scene = scene();
        try (MinecraftPortalProjector projector = scene.projector()) {
            settle(projector, 1L);
            scene.eye().set(new Vec3(2.0D, 65.0D, 4.0D));

            assertEquals(MinecraftPortalProjector.Result.READY, projector.update(2L, Long.MAX_VALUE));
        }
    }

    @Test
    public void blackoutAndAtmosphereChangesRescan() {
        Scene scene = scene();
        AtmosphereMode atmosphere = FidelitySettings.atmosphereModeDefault;
        try (MinecraftPortalProjector projector = scene.projector()) {
            settle(projector, 1L);
            when(scene.source().isBlackoutBackground()).thenReturn(true);
            settle(projector, 2L);
            assertEquals(MinecraftPortalProjector.Result.IDLE, projector.update(3L, Long.MAX_VALUE));
            when(scene.source().getBlackoutColor()).thenReturn(BlackoutColor.RED);
            settle(projector, 4L);
            FidelitySettings.atmosphereModeDefault = AtmosphereMode.TINT;
            settle(projector, 5L);
            assertEquals(MinecraftPortalProjector.Result.IDLE, projector.update(6L, Long.MAX_VALUE));
        } finally {
            FidelitySettings.atmosphereModeDefault = atmosphere;
        }
    }

    @Test
    public void projectionSettingChangesRescan() {
        Scene scene = scene();
        try (MinecraftPortalProjector projector = scene.projector()) {
            settle(projector, 1L);
            scene.projection().nearPlanePadding += 0.5D;
            settle(projector, 2L);
            scene.projection().aperturePaddingBlocks += 0.25D;
            settle(projector, 3L);
            when(scene.player().requestedViewDistance()).thenReturn(2);
            settle(projector, 4L);
            assertEquals(MinecraftPortalProjector.Result.IDLE, projector.update(5L, Long.MAX_VALUE));
        }
    }

    @Test
    public void aDestinationChangeInsideTheFootprintIsResampledWithinTheStableCadence() {
        Scene scene = scene();
        try (MinecraftPortalProjector projector = scene.projector()) {
            settle(projector, 1L);
            long remoteKey = projector.scan().claims().values().iterator().next().getLightRemoteKey();
            scene.changes().markChanged(DESTINATION_WORLD, CellKeys.unpackX(remoteKey), CellKeys.unpackY(remoteKey),
                CellKeys.unpackZ(remoteKey));
            clearInvocations(scene.destination());
            long tick = 2L;
            MinecraftPortalProjector.Result result = projector.update(tick, Long.MAX_VALUE);
            while (result == MinecraftPortalProjector.Result.IDLE && tick < 200L) {
                tick++;
                result = projector.update(tick, Long.MAX_VALUE);
            }

            assertEquals(MinecraftPortalProjector.Result.READY, result);
            assertTrue(samples(scene) > 0);
            projector.commit();
            assertEquals(MinecraftPortalProjector.Result.IDLE, projector.update(tick + 1L, Long.MAX_VALUE));
        }
    }

    @Test
    public void everyReuseDecisionIsThePlannersVerdictOnTheProjectorInputs() throws Exception {
        Scene scene = scene();
        try (MinecraftPortalProjector projector = scene.projector()) {
            settle(projector, 1L);
            Field field = MinecraftPortalProjector.class.getDeclaredField("passInputs");
            field.setAccessible(true);
            PassInputs inputs = (PassInputs) field.get(projector);
            Runnable[] changes = {
                () -> { },
                () -> scene.eye().set(new Vec3(1.1D, 65.0D, 4.0D)),
                () -> when(scene.source().getNetworkViewLateralPad()).thenReturn(6),
                () -> { },
                () -> scene.eye().set(new Vec3(1.1D, 65.0D, 6.0D)),
                () -> { }
            };
            boolean[] expectedReuse = {true, true, false, true, false, true};
            for (int step = 0; step < changes.length; step++) {
                changes[step].run();
                MinecraftPortalProjector.Result result = projector.update(step + 2L, Long.MAX_VALUE);
                boolean reused = result == MinecraftPortalProjector.Result.IDLE;
                assertEquals("step " + step, expectedReuse[step], reused);
                assertEquals("step " + step, PassPlanner.reusable(inputs), reused);
                if (!reused) {
                    assertEquals(MinecraftPortalProjector.Result.READY, result);
                    projector.commit();
                }
            }
        }
    }

    private static void settle(MinecraftPortalProjector projector, long tick) {
        assertEquals(MinecraftPortalProjector.Result.READY, projector.update(tick, Long.MAX_VALUE));
        projector.commit();
    }

    private static int samples(Scene scene) {
        int samples = 0;
        for (Invocation invocation : mockingDetails(scene.destination()).getInvocations()) {
            if (invocation.getMethod().getName().equals("sampleBlockData")) {
                samples++;
            }
        }
        return samples;
    }

    private static Scene scene() {
        WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        WormholesModConfiguration configuration = mock(WormholesModConfiguration.class);
        MinecraftProjectorPortalAccess access = mock(MinecraftProjectorPortalAccess.class);
        when(access.mirrorTurns(any())).thenReturn(QuarterTurn.DEGREES_0);
        ServerPlayer player = mock(ServerPlayer.class);
        MinecraftServer server = mock(MinecraftServer.class);
        PlayerList players = mock(PlayerList.class);
        WorldChangeTracker changes = new WorldChangeTracker();
        MinecraftProjectionService projections = mock(MinecraftProjectionService.class);
        when(projections.changes()).thenReturn(changes);
        when(runtime.projections()).thenReturn(projections);
        when(runtime.server()).thenReturn(server);
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
        WormholesSettings settings = new WormholesSettings(new MainConfig(), projection, new RenderConfig(), new NetworkConfig());
        when(configuration.settings()).thenReturn(settings);
        AtomicReference<Vec3> eye = new AtomicReference<>(new Vec3(1.0D, 65.0D, 4.0D));
        when(player.level()).thenReturn(sourceWorld);
        when(player.getEyePosition()).thenAnswer(call -> eye.get());
        when(access.eligible(source)).thenReturn(true);
        when(access.current(source)).thenReturn(true);
        when(access.world(source)).thenReturn(sourceWorld);
        when(access.world(target)).thenReturn(targetWorld);
        when(access.projectionDestination(source)).thenReturn(target);
        when(access.endpoints()).thenReturn(List.of());
        when(access.createRecursiveIndex()).thenReturn(new RecursiveEndpoints<>(access,
            () -> new RecursiveEndpoints.Options(0.75D, 64.0D)));
        MinecraftProjectionWorldView local = view(sourceWorld, LOCAL_WORLD, Blocks.AIR.defaultBlockState());
        MinecraftProjectionWorldView destination = view(targetWorld, DESTINATION_WORLD, Blocks.STONE.defaultBlockState());
        MinecraftPortalProjector.Context context = new MinecraftPortalProjector.Context(player, source,
            world -> world == sourceWorld ? local : destination, access, null);
        return new Scene(runtime, context, changes, destination, source, player, settings.getProjection(), eye);
    }

    private static MinecraftProjectionWorldView view(ServerLevel world, UUID worldId, BlockState block) {
        MinecraftProjectionWorldView view = mock(MinecraftProjectionWorldView.class);
        when(view.getWorld()).thenReturn(world);
        when(view.worldId()).thenReturn(worldId);
        when(view.getMinHeight()).thenReturn(-64);
        when(view.getMaxHeight()).thenReturn(320);
        when(view.isChunkReady(anyInt(), anyInt())).thenReturn(true);
        when(view.sampleBlockData(anyInt(), anyInt(), anyInt())).thenReturn(block);
        when(view.material(anyInt(), anyInt(), anyInt())).thenReturn(block);
        return view;
    }

    private static MinecraftPortal portal(double x) {
        MinecraftPortal portal = mock(MinecraftPortal.class);
        ApertureCells geometry = new ApertureCells();
        geometry.setArea(new Box(x, x + 2.0D, 64.0D, 67.0D, 0.0D, 1.0D));
        when(portal.getId()).thenReturn(UUID.randomUUID());
        when(portal.getGeometry()).thenReturn(geometry);
        when(portal.getFrame()).thenReturn(Frame.canonical(Face.S));
        when(portal.getOrigin()).thenReturn(new Vec3d(x + 1.0D, 65.0D, 0.0D));
        when(portal.frame()).thenCallRealMethod();
        when(portal.origin()).thenCallRealMethod();
        when(portal.id()).thenCallRealMethod();
        when(portal.getNetworkViewDepth()).thenReturn(8);
        when(portal.getBlackoutColor()).thenReturn(BlackoutColor.BLACK);
        when(portal.getRenderMode()).thenReturn(ProjectionRenderMode.PANOPTIC);
        return portal;
    }

    private record Scene(WormholesModRuntime runtime, MinecraftPortalProjector.Context context, WorldChangeTracker changes,
                         MinecraftProjectionWorldView destination, MinecraftPortal source, ServerPlayer player,
                         ProjectionConfig projection, AtomicReference<Vec3> eye) {
        private MinecraftPortalProjector projector() {
            return new MinecraftPortalProjector(runtime, context);
        }
    }
}
