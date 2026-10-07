package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import art.arcane.optics.fidelity.AtmosphereMode;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.QuarterTurn;
import art.arcane.optics.math.Face;
import art.arcane.optics.scan.PassInputs;
import art.arcane.optics.scan.PassPlanner;
import art.arcane.optics.view.WorldChangeTracker;
import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.platform.QueuedOpticsScheduler;
import art.arcane.wormholes.portal.BlackoutColor;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.portal.ProjectionRenderMode;
import art.arcane.wormholes.render.view.ProjectionWorldView;
import art.arcane.wormholes.util.BukkitGeometry;
import art.arcane.wormholes.util.Cuboid;

final class PortalProjectorPassParityTest {
    @Test
    void anUnchangedPassReusesTheCommittedProjection() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.settle();
            int skips = fixture.reuseSkips();

            fixture.projector.project(true, false, 0L);

            assertEquals(skips + 1, fixture.reuseSkips());
            assertFalse(fixture.projector.hasPendingScan());
        }
    }

    @Test
    void smallEyeDriftKeepsReusing() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.settle();
            int skips = fixture.reuseSkips();
            fixture.eye.get().add(0.2D, 0.0D, 0.0D);

            fixture.projector.project(true, false, 0L);

            assertEquals(skips + 1, fixture.reuseSkips());
        }
    }

    @Test
    void blackoutColourChangeRescansWhileTheViewerStandsStill() throws Exception {
        try (Fixture fixture = new Fixture()) {
            when(fixture.portal.isBlackoutBackground()).thenReturn(true);
            fixture.settle();
            when(fixture.portal.getBlackoutColor()).thenReturn(BlackoutColor.RED);

            fixture.assertRescans();
        }
    }

    @Test
    void atmosphereModeChangeRescansWhileTheViewerStandsStill() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.settle();
            FidelitySettings.atmosphereModeDefault = AtmosphereMode.TINT;

            fixture.assertRescans();
        }
    }

    @Test
    void nearPlanePaddingChangeRescansWhileTheViewerStandsStill() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.settle();
            Settings.NEAR_PLANE_PADDING = Settings.NEAR_PLANE_PADDING + 0.5D;

            fixture.assertRescans();
        }
    }

    @Test
    void clientViewDistanceChangeRescansWhileTheViewerStandsStill() throws Exception {
        try (Fixture fixture = new Fixture()) {
            when(fixture.portal.getNetworkViewDepth()).thenReturn(64);
            fixture.settle();
            when(fixture.player.getClientViewDistance()).thenReturn(2);

            fixture.assertRescans();
        }
    }

    @Test
    void everyReuseDecisionIsThePlannersVerdictOnTheProjectorInputs() throws Exception {
        try (Fixture fixture = new Fixture()) {
            when(fixture.portal.isBlackoutBackground()).thenReturn(true);
            fixture.settle();
            Field inputsField = PortalProjector.class.getDeclaredField("passInputs");
            inputsField.setAccessible(true);
            PassInputs inputs = (PassInputs) inputsField.get(fixture.projector);
            Runnable[] changes = {
                () -> { },
                () -> fixture.eye.get().add(0.1D, 0.0D, 0.0D),
                () -> when(fixture.portal.getBlackoutColor()).thenReturn(BlackoutColor.LIME),
                () -> { },
                () -> Settings.NEAR_PLANE_PADDING = Settings.NEAR_PLANE_PADDING + 0.25D,
                () -> { },
                () -> fixture.eye.get().add(0.0D, 0.0D, 1.5D),
                () -> { }
            };
            boolean[] expectedReuse = {true, true, false, true, false, true, false, true};
            for (int step = 0; step < changes.length; step++) {
                changes[step].run();
                int skips = fixture.reuseSkips();
                fixture.projector.project(true, false);
                boolean reused = fixture.reuseSkips() == skips + 1;
                assertEquals(expectedReuse[step], reused, "step " + step);
                assertEquals(PassPlanner.reusable(inputs), reused, "step " + step);
            }
        }
    }

    @Test
    void wallMirrorTurnsReachTheScanUnnormalized() throws Exception {
        try (Fixture fixture = new Fixture()) {
            when(fixture.portal.getMirrorRotation()).thenReturn(QuarterTurn.DEGREES_90);
            fixture.projector.project(true, false, 0L);

            Field field = PortalProjector.class.getDeclaredField("destination");
            field.setAccessible(true);
            ProjectorDestination destination = (ProjectorDestination) field.get(fixture.projector);

            assertEquals(1, destination.mirrorRotationQuarterTurns, "OpticTransform.mirror applies frame coherence itself");
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final AtmosphereMode oldAtmosphere = FidelitySettings.atmosphereModeDefault;
        private final boolean oldBlockEntities = FidelitySettings.blockEntities;
        private final int oldDissolve = FidelitySettings.dissolveTicks;
        private final double oldNearPlanePadding = Settings.NEAR_PLANE_PADDING;
        private final boolean oldViewDistanceCap = Settings.PROJECTION_CLIENT_VIEW_DISTANCE_CAP;
        private final boolean oldHoldInvisibleClaims = Settings.PROJECTION_HOLD_INVISIBLE_CLAIMS;
        private final WorldChangeTracker oldTracker = Wormholes.projectionChangeTracker;
        private final MockedStatic<Bukkit> bukkit;
        private final World world = mock(World.class);
        private final AtomicReference<Location> eye = new AtomicReference<>(new Location(world, 1.0D, 65.0D, 4.0D));
        private final ProjectionClaimArbiter arbiter = mock(ProjectionClaimArbiter.class);
        private final ILocalPortal portal = mock(ILocalPortal.class);
        private final Player player = mock(Player.class);
        private final PortalProjector projector;

        private Fixture() {
            FidelitySettings.atmosphereModeDefault = AtmosphereMode.OFF;
            FidelitySettings.blockEntities = false;
            FidelitySettings.dissolveTicks = 0;
            Settings.PROJECTION_CLIENT_VIEW_DISTANCE_CAP = true;
            Settings.PROJECTION_HOLD_INVISIBLE_CLAIMS = false;
            Wormholes.projectionChangeTracker = new WorldChangeTracker();
            BlockData stone = mock(BlockData.class);
            when(stone.getMaterial()).thenReturn(Material.STONE);
            when(stone.getAsString()).thenReturn("minecraft:stone");
            when(stone.clone()).thenReturn(stone);
            BlockData air = mock(BlockData.class);
            when(air.getMaterial()).thenReturn(Material.AIR);
            when(air.getAsString()).thenReturn("minecraft:air");
            when(air.clone()).thenReturn(air);
            bukkit = mockStatic(Bukkit.class);
            BlockData shell = mock(BlockData.class);
            when(shell.getAsString()).thenReturn("minecraft:black_concrete");
            when(shell.clone()).thenReturn(shell);
            bukkit.when(() -> Bukkit.createBlockData(anyString())).thenReturn(shell);
            bukkit.when(() -> Bukkit.createBlockData(any(Material.class))).thenReturn(air);
            when(world.getUID()).thenReturn(UUID.randomUUID());
            when(world.getMinHeight()).thenReturn(-64);
            when(world.getMaxHeight()).thenReturn(320);
            when(world.getEnvironment()).thenReturn(World.Environment.NORMAL);
            PortalStructure structure = new PortalStructure();
            structure.setArea(new Cuboid(Map.of("worldKey", "minecraft:overworld", "x1", 0, "x2", 2,
                "y1", 64, "y2", 66, "z1", 0, "z2", 0)));
            when(portal.getId()).thenReturn(UUID.randomUUID());
            when(portal.getWorld()).thenReturn(world);
            when(portal.getName()).thenReturn("pass parity");
            when(portal.getFrame()).thenReturn(Frame.canonical(Face.N));
            when(portal.getOrigin()).thenReturn(BukkitGeometry.vector(structure.getCenter()));
            when(portal.frame()).thenCallRealMethod();
            when(portal.origin()).thenCallRealMethod();
            when(portal.id()).thenCallRealMethod();
            when(portal.getStructure()).thenReturn(structure);
            when(portal.isOpen()).thenReturn(true);
            when(portal.isMirrorMode()).thenReturn(true);
            when(portal.getMirrorRotation()).thenReturn(QuarterTurn.DEGREES_0);
            when(portal.getRenderMode()).thenReturn(ProjectionRenderMode.PANOPTIC);
            when(portal.getNetworkViewDepth()).thenReturn(8);
            when(portal.getNetworkViewLateralPad()).thenReturn(4);
            when(portal.getNetworkViewHeartbeatTicks()).thenReturn(1_200);
            when(portal.getBlackoutColor()).thenReturn(BlackoutColor.BLACK);
            when(player.getUniqueId()).thenReturn(UUID.randomUUID());
            when(player.getName()).thenReturn("viewer");
            when(player.isOnline()).thenReturn(true);
            when(player.getWorld()).thenReturn(world);
            when(player.getEyeLocation()).thenAnswer(call -> eye.get().clone());
            when(player.getClientViewDistance()).thenReturn(16);
            ProjectionWorldView view = mock(ProjectionWorldView.class);
            when(view.getWorld()).thenReturn(world);
            when(view.getMinHeight()).thenReturn(-64);
            when(view.getMaxHeight()).thenReturn(320);
            when(view.isChunkReady(anyInt(), anyInt())).thenReturn(true);
            when(view.sampleBlockData(anyInt(), anyInt(), anyInt())).thenReturn(air);
            when(view.material(anyInt(), anyInt(), anyInt())).thenReturn(Material.STONE);
            ProjectionClaimArbiter.ClaimUpdateResult result = mock(ProjectionClaimArbiter.ClaimUpdateResult.class);
            when(arbiter.submitDelta(any(), any(), any(), any(), anyDouble(), anyBoolean(), anyBoolean())).thenReturn(result);
            when(arbiter.release(any(Player.class), any(ILocalPortal.class), any(World.class), anyBoolean())).thenReturn(result);
            projector = new PortalProjector(portal, player, arbiter, ignored -> view, () -> true, new QueuedOpticsScheduler());
        }

        private void settle() throws Exception {
            for (int pass = 0; pass < 3; pass++) {
                projector.project(true, false);
            }
            assertFalse(projector.hasPendingScan());
            int skips = reuseSkips();
            projector.project(true, false, 0L);
            assertEquals(skips + 1, reuseSkips(), "the settled projection must start on the reuse path");
        }

        private void assertRescans() throws Exception {
            int skips = reuseSkips();
            projector.project(true, false, 0L);
            assertEquals(skips, reuseSkips(), "a changed presentation input must not reuse the committed projection");
            assertTrue(projector.hasPendingScan(), "the changed pass must start a scan");
        }

        private int reuseSkips() throws Exception {
            Field field = PortalProjector.class.getDeclaredField("lastReuseSkips");
            field.setAccessible(true);
            return (int) field.get(projector);
        }

        @Override
        public void close() {
            bukkit.close();
            FidelitySettings.atmosphereModeDefault = oldAtmosphere;
            FidelitySettings.blockEntities = oldBlockEntities;
            FidelitySettings.dissolveTicks = oldDissolve;
            Settings.NEAR_PLANE_PADDING = oldNearPlanePadding;
            Settings.PROJECTION_CLIENT_VIEW_DISTANCE_CAP = oldViewDistanceCap;
            Settings.PROJECTION_HOLD_INVISIBLE_CLAIMS = oldHoldInvisibleClaims;
            Wormholes.projectionChangeTracker = oldTracker;
        }
    }
}
