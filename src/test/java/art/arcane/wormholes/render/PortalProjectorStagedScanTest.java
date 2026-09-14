package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.MirrorRotation;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.portal.ProjectionRenderMode;
import art.arcane.wormholes.render.atmosphere.AtmosphereMode;
import art.arcane.wormholes.render.view.ProjectionWorldView;
import art.arcane.wormholes.util.Cuboid;
import art.arcane.wormholes.util.Direction;

final class PortalProjectorStagedScanTest {
    @Test
    void movingCameraAndContentChangesCompleteWithoutPublishingPartialClaims() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.projector.project(true, false, 0L);
            assertTrue(fixture.projector.hasPendingScan());
            assertFalse(fixture.projector.hasProjection());
            fixture.verifyNoSubmission();
            int slices = 0;
            while (fixture.projector.hasPendingScan() && slices++ < 100) {
                fixture.eye.get().add(0.01D, 0.0D, 0.0D);
                fixture.revision.incrementAndGet();
                fixture.projector.project(true, false, 0L);
            }
            assertFalse(fixture.projector.hasPendingScan(), "movement and block updates cannot starve completion");
            assertTrue(slices > 1);
            assertEquals(1.0D, field(fixture.projector, "lastEyeX"));
            verify(fixture.arbiter, atLeastOnce()).submitDelta(any(), any(), any(), any(), anyDouble(), anyBoolean(), anyBoolean());
            int sampled = fixture.samples.get();
            fixture.projector.project(true, false, 0L);
            assertTrue(fixture.projector.hasPendingScan(), "content changes during the pass require another scan");
            fixture.finish();
            assertTrue(fixture.samples.get() > sampled);
        }
    }

    @Test
    void reloadCancelsPendingWorkWithoutConsumingInitialFullSend() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.projector.project(true, false, 0L);
            assertTrue(fixture.projector.hasPendingScan());
            fixture.projector.invalidateProjectionReuse();
            fixture.projector.project(false, true, 0L);
            assertFalse(fixture.projector.hasPendingScan());
            assertEquals(1, field(fixture.projector, "initialFullSendPassesRemaining"));
            fixture.verifyNoSubmission();
            fixture.projector.project(true, false);
            assertFalse(fixture.projector.hasPendingScan());
            assertEquals(0, field(fixture.projector, "initialFullSendPassesRemaining"));
        }
    }

    @Test
    void crossingThePortalPlaneDiscardsTheOldCameraPass() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.projector.project(true, false, 0L);
            assertTrue(fixture.projector.hasPendingScan());
            fixture.eye.set(new Location(fixture.world, 1.0D, 65.0D, -4.0D));
            fixture.projector.project(true, false, 0L);
            assertTrue(fixture.projector.hasPendingScan());
            fixture.verifyNoSubmission();
            fixture.finish();
            assertEquals(-4.0D, field(fixture.projector, "lastEyeZ"));
        }
    }

    @Test
    void leavingTheWorldDiscardsPendingWorkWithoutPublishing() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.projector.project(true, false, 0L);
            World other = mock(World.class);
            when(other.getUID()).thenReturn(UUID.randomUUID());
            fixture.observerWorld.set(other);
            fixture.projector.project(true, false, Long.MAX_VALUE);
            assertTrue(fixture.projector.isClosed());
            assertFalse(fixture.projector.hasPendingScan());
            fixture.verifyNoSubmission();
        }
    }

    @Test
    void anExistingViewRemainsCommittedUntilTheNextPassCompletes() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.projector.project(true, false);
            int rendered = fixture.projector.getProjectedCount();
            assertTrue(rendered > 0);
            fixture.eye.get().add(1.0D, 0.0D, 0.0D);
            fixture.projector.project(true, false, 0L);
            assertTrue(fixture.projector.hasPendingScan());
            assertEquals(rendered, fixture.projector.getProjectedCount());
            assertTrue(fixture.projector.hasProjection());
            fixture.finish();
            assertFalse(fixture.projector.hasPendingScan());
        }
    }

    private static Object field(PortalProjector projector, String name) throws ReflectiveOperationException {
        Field field = PortalProjector.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(projector);
    }

    private static final class Fixture implements AutoCloseable {
        private final AtmosphereMode oldAtmosphere = FidelitySettings.atmosphereModeDefault;
        private final boolean oldBlockEntities = FidelitySettings.blockEntities;
        private final int oldDissolve = FidelitySettings.dissolveTicks;
        private final MockedStatic<Bukkit> bukkit;
        private final World world = mock(World.class);
        private final AtomicReference<World> observerWorld = new AtomicReference<>(world);
        private final AtomicReference<Location> eye = new AtomicReference<>(new Location(world, 1.0D, 65.0D, 4.0D));
        private final AtomicLong revision = new AtomicLong();
        private final AtomicInteger samples = new AtomicInteger();
        private final ProjectionClaimArbiter arbiter = mock(ProjectionClaimArbiter.class);
        private final PortalProjector projector;

        private Fixture() {
            FidelitySettings.atmosphereModeDefault = AtmosphereMode.OFF;
            FidelitySettings.blockEntities = false;
            FidelitySettings.dissolveTicks = 0;
            BlockData stone = mock(BlockData.class);
            when(stone.getMaterial()).thenReturn(Material.STONE);
            when(stone.getAsString()).thenReturn("minecraft:stone");
            when(stone.clone()).thenReturn(stone);
            bukkit = mockStatic(Bukkit.class);
            bukkit.when(() -> Bukkit.createBlockData(anyString())).thenReturn(stone);
            bukkit.when(() -> Bukkit.createBlockData(any(Material.class))).thenReturn(stone);
            when(world.getUID()).thenReturn(UUID.randomUUID());
            when(world.getMinHeight()).thenReturn(-64);
            when(world.getMaxHeight()).thenReturn(320);
            PortalStructure structure = new PortalStructure();
            structure.setArea(new Cuboid(Map.of("worldKey", "minecraft:overworld", "x1", 0, "x2", 2,
                "y1", 64, "y2", 66, "z1", 0, "z2", 0)));
            ILocalPortal portal = mock(ILocalPortal.class);
            when(portal.getId()).thenReturn(UUID.randomUUID());
            when(portal.getWorld()).thenReturn(world);
            when(portal.getName()).thenReturn("staged projection");
            when(portal.getFrame()).thenReturn(PortalFrame.canonical(Direction.N));
            when(portal.getOrigin()).thenReturn(structure.getCenter().toVector());
            when(portal.getStructure()).thenReturn(structure);
            when(portal.isOpen()).thenReturn(true);
            when(portal.isMirrorMode()).thenReturn(true);
            when(portal.getMirrorRotation()).thenReturn(MirrorRotation.DEGREES_0);
            when(portal.getRenderMode()).thenReturn(ProjectionRenderMode.PANOPTIC);
            when(portal.getNetworkViewDepth()).thenReturn(8);
            when(portal.getNetworkViewLateralPad()).thenReturn(4);
            Player player = mock(Player.class);
            when(player.getUniqueId()).thenReturn(UUID.randomUUID());
            when(player.getName()).thenReturn("viewer");
            when(player.isOnline()).thenReturn(true);
            when(player.getWorld()).thenAnswer(call -> observerWorld.get());
            when(player.getEyeLocation()).thenAnswer(call -> eye.get().clone());
            when(player.getClientViewDistance()).thenReturn(16);
            ProjectionWorldView view = mock(ProjectionWorldView.class);
            when(view.getWorld()).thenReturn(world);
            when(view.getMinHeight()).thenReturn(-64);
            when(view.getMaxHeight()).thenReturn(320);
            when(view.isChunkReady(anyInt(), anyInt())).thenReturn(true);
            when(view.getRevision()).thenAnswer(call -> revision.get());
            when(view.sampleBlockData(anyInt(), anyInt(), anyInt())).thenAnswer(call -> {
                samples.incrementAndGet();
                return stone;
            });
            when(view.sampleMaterial(anyInt(), anyInt(), anyInt())).thenReturn(Material.STONE);
            ProjectionClaimArbiter.ClaimUpdateResult result = mock(ProjectionClaimArbiter.ClaimUpdateResult.class);
            when(arbiter.submitDelta(any(), any(), any(), any(), anyDouble(), anyBoolean(), anyBoolean())).thenReturn(result);
            when(arbiter.release(any(Player.class), any(ILocalPortal.class), any(World.class), anyBoolean())).thenReturn(result);
            projector = new PortalProjector(portal, player, arbiter, ignored -> view, () -> true);
        }

        private void finish() {
            int attempts = 0;
            while (projector.hasPendingScan() && attempts++ < 100) {
                projector.project(true, false, 0L);
            }
            assertFalse(projector.hasPendingScan());
        }

        private void verifyNoSubmission() {
            verify(arbiter, never()).submitDelta(any(), any(), any(), any(), anyDouble(), anyBoolean(), anyBoolean());
        }

        @Override
        public void close() {
            bukkit.close();
            FidelitySettings.atmosphereModeDefault = oldAtmosphere;
            FidelitySettings.blockEntities = oldBlockEntities;
            FidelitySettings.dissolveTicks = oldDissolve;
        }
    }
}
