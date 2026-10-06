package art.arcane.wormholes.render;

import art.arcane.wormholes.portal.DimensionalPortalKind;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.optics.frame.Frame;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.portal.ProjectionRenderMode;
import art.arcane.optics.fidelity.AtmosphereMode;
import art.arcane.wormholes.render.view.ProjectionWorldView;
import art.arcane.wormholes.util.BukkitGeometry;
import art.arcane.optics.math.Face;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import art.arcane.optics.math.CellKeys;

class PortalProjectorEndSurfaceTest {
    @Test
    void masksOnlyActualExitCellsAfterDestinationReadinessAndRestoresOnClose() {
        try (Fixture fixture = new Fixture(DimensionalPortalKind.END_EXIT)) {
            fixture.ready.set(false);
            fixture.projector.project(true, false);
            assertTrue(fixture.sent.isEmpty());
            fixture.ready.set(true);
            fixture.projector.project(true, false);
            assertTrue(fixture.projector.hasProjection());
            assertEquals(fixture.aperture, fixture.keys(Material.AIR));
            assertFalse(fixture.keys(Material.AIR).contains(CellKeys.pack(1, 64, 1)));
            assertFalse(fixture.keys(Material.AIR).contains(CellKeys.pack(2, 64, 2)));
            fixture.sent.clear();
            fixture.projector.close();
            assertEquals(fixture.aperture, fixture.keys(Material.END_PORTAL));
            assertTrue(fixture.arbiter.isIdle());
            verify(fixture.world, never()).getBlockAt(anyInt(), anyInt(), anyInt());
        }
    }

    @Test
    void unavailableOrChangedDestinationRestoresSurfaceUntilTheNextCommit() {
        try (Fixture fixture = new Fixture(DimensionalPortalKind.END_EXIT)) {
            fixture.projector.project(true, false);
            fixture.sent.clear();
            fixture.ready.set(false);
            fixture.projector.project(true, false);
            assertEquals(fixture.aperture, fixture.keys(Material.END_PORTAL));
            fixture.sent.clear();
            fixture.ready.set(true);
            fixture.projector.project(true, false);
            assertEquals(fixture.aperture, fixture.keys(Material.AIR));
            fixture.sent.clear();
            fixture.projector.setRtpProjectionTarget(new PortalProjector.RtpProjectionTarget(fixture.remote, 10, 70, 10,
                Frame.canonical(Face.U), 2));
            assertEquals(fixture.aperture, fixture.keys(Material.END_PORTAL));
        }
    }

    @Test
    void renderingFailureRestoresTheVanillaSurface() {
        try (Fixture fixture = new Fixture(DimensionalPortalKind.END_EXIT)) {
            fixture.projector.project(true, false);
            fixture.sent.clear();
            when(fixture.remoteView.isChunkReady(anyInt(), anyInt())).thenThrow(new IllegalStateException("Destination unavailable"));
            assertThrows(IllegalStateException.class, () -> fixture.projector.project(true, false));
            assertEquals(fixture.aperture, fixture.keys(Material.END_PORTAL));
        }
    }

    @Test
    void sourceSamplingFailureDuringMaskRefreshRestoresPreviouslyHiddenCells() {
        try (Fixture fixture = new Fixture(DimensionalPortalKind.END_EXIT)) {
            fixture.projector.project(true, false);
            fixture.sent.clear();
            when(fixture.localView.material(anyInt(), anyInt(), anyInt())).thenThrow(new IllegalStateException("Source unavailable"));
            fixture.projector.invalidateProjectionReuse();
            assertThrows(IllegalStateException.class, () -> fixture.projector.project(true, false));
            assertEquals(fixture.aperture, fixture.keys(Material.END_PORTAL));
            assertFalse(fixture.projector.hasPendingScan());
            fixture.sampleLocalMaterials();
            fixture.sent.clear();
            fixture.projector.project(true, false);
            assertEquals(fixture.aperture, fixture.keys(Material.AIR));
        }
    }

    @Test
    void ordinaryPortalLeavesTheLocalEndSurfaceAlone() {
        try (Fixture fixture = new Fixture(DimensionalPortalKind.END_SOURCE)) {
            fixture.projector.project(true, false);
            assertTrue(fixture.keys(Material.AIR).isEmpty());
            fixture.projector.close();
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final AtmosphereMode oldAtmosphere = FidelitySettings.atmosphereModeDefault;
        private final boolean oldBlockEntities = FidelitySettings.blockEntities;
        private final int oldDissolve = FidelitySettings.dissolveTicks;
        private final World world = mock(World.class);
        private final World remote = mock(World.class);
        private final AtomicBoolean ready = new AtomicBoolean(true);
        private final Set<Long> aperture = new HashSet<>();
        private final List<Change> sent = new ArrayList<>();
        private final MockedStatic<Bukkit> bukkit;
        private final ProjectionClaimArbiter arbiter;
        private final PortalProjector projector;
        private final ProjectionWorldView remoteView;
        private final ProjectionWorldView localView;

        private Fixture(DimensionalPortalKind kind) {
            FidelitySettings.atmosphereModeDefault = AtmosphereMode.OFF;
            FidelitySettings.blockEntities = false;
            FidelitySettings.dissolveTicks = 0;
            BlockData air = blockData(Material.AIR);
            BlockData stone = blockData(Material.STONE);
            BlockData endPortal = blockData(Material.END_PORTAL);
            bukkit = mockStatic(Bukkit.class);
            try {
                bukkit.when(() -> Bukkit.createBlockData(anyString())).thenReturn(stone);
                bukkit.when(() -> Bukkit.createBlockData(any(Material.class))).thenReturn(stone);
                bukkit.when(() -> Bukkit.createBlockData(Material.AIR)).thenReturn(air);
                when(world.getUID()).thenReturn(UUID.randomUUID());
                when(remote.getUID()).thenReturn(UUID.randomUUID());
                Set<Block> cells = new HashSet<>();
                for (int x = 0; x < 3; x++) {
                    for (int z = 0; z < 3; z++) {
                        if (x == 1 && z == 1) {
                            continue;
                        }
                        Block cell = mock(Block.class);
                        when(cell.getWorld()).thenReturn(world);
                        when(cell.getX()).thenReturn(x);
                        when(cell.getY()).thenReturn(64);
                        when(cell.getZ()).thenReturn(z);
                        cells.add(cell);
                        if (x != 2 || z != 2) {
                            aperture.add(CellKeys.pack(x, 64, z));
                        }
                    }
                }
                PortalStructure structure = new PortalStructure();
                structure.setBlocks(cells);
                ILocalPortal portal = mock(ILocalPortal.class);
                when(portal.getId()).thenReturn(UUID.randomUUID());
                when(portal.getWorld()).thenReturn(world);
                when(portal.getName()).thenReturn("End return");
                when(portal.getDimensionalPortalKind()).thenReturn(kind);
                when(portal.getFrame()).thenReturn(Frame.canonical(Face.U));
                when(portal.getOrigin()).thenReturn(BukkitGeometry.vector(structure.getCenter()));
                when(portal.frame()).thenCallRealMethod();
                when(portal.origin()).thenCallRealMethod();
                when(portal.id()).thenCallRealMethod();
                when(portal.getStructure()).thenReturn(structure);
                when(portal.isOpen()).thenReturn(true);
                when(portal.getRenderMode()).thenReturn(ProjectionRenderMode.PANOPTIC);
                when(portal.getNetworkViewDepth()).thenReturn(4);
                when(portal.getNetworkViewLateralPad()).thenReturn(1);
                Player observer = mock(Player.class);
                when(observer.getUniqueId()).thenReturn(UUID.randomUUID());
                when(observer.getWorld()).thenReturn(world);
                when(observer.isOnline()).thenReturn(true);
                when(observer.getEyeLocation()).thenReturn(new Location(world, 1.5, 68, 1.5));
                when(observer.getClientViewDistance()).thenReturn(16);
                doAnswer(call -> {
                    Location location = call.getArgument(0);
                    BlockData data = call.getArgument(1);
                    sent.add(new Change(CellKeys.pack(location.getBlockX(), location.getBlockY(), location.getBlockZ()), data.getMaterial()));
                    return null;
                }).when(observer).sendBlockChange(any(Location.class), any(BlockData.class));
                localView = view(world, stone);
                when(localView.sampleBlockData(anyInt(), anyInt(), anyInt())).thenAnswer(call -> {
                    int x = call.getArgument(0);
                    int y = call.getArgument(1);
                    int z = call.getArgument(2);
                    return aperture.contains(CellKeys.pack(x, y, z)) ? endPortal : stone;
                });
                sampleLocalMaterials();
                remoteView = view(remote, stone);
                when(remoteView.isChunkReady(anyInt(), anyInt())).thenAnswer(call -> ready.get());
                arbiter = new ProjectionClaimArbiter(target -> target == world ? localView : remoteView,
                    new BukkitProjectionOutput((player, chunkX, chunkZ) -> true, portalId -> List.of()));
                projector = new PortalProjector(portal, observer, arbiter, target -> target == world ? localView : remoteView, () -> true);
                projector.setRtpProjectionTarget(new PortalProjector.RtpProjectionTarget(remote, 10, 70, 10,
                    Frame.canonical(Face.U), 1));
            } catch (RuntimeException | Error failure) {
                bukkit.close();
                restoreFidelity();
                throw failure;
            }
        }

        private void sampleLocalMaterials() {
            doAnswer(call -> {
                int x = call.getArgument(0);
                int y = call.getArgument(1);
                int z = call.getArgument(2);
                return aperture.contains(CellKeys.pack(x, y, z)) ? Material.END_PORTAL : Material.STONE;
            }).when(localView).material(anyInt(), anyInt(), anyInt());
        }

        private Set<Long> keys(Material material) {
            Set<Long> keys = new HashSet<>();
            for (Change change : sent) {
                if (change.material() == material) {
                    keys.add(change.key());
                }
            }
            return keys;
        }

        @Override
        public void close() {
            try {
                projector.close();
            } finally {
                bukkit.close();
                restoreFidelity();
            }
        }

        private void restoreFidelity() {
            FidelitySettings.atmosphereModeDefault = oldAtmosphere;
            FidelitySettings.blockEntities = oldBlockEntities;
            FidelitySettings.dissolveTicks = oldDissolve;
        }
    }

    private static ProjectionWorldView view(World world, BlockData data) {
        ProjectionWorldView view = mock(ProjectionWorldView.class);
        when(view.getWorld()).thenReturn(world);
        when(view.getMinHeight()).thenReturn(-64);
        when(view.getMaxHeight()).thenReturn(320);
        when(view.isChunkReady(anyInt(), anyInt())).thenReturn(true);
        when(view.sampleBlockData(anyInt(), anyInt(), anyInt())).thenReturn(data);
        Material material = data.getMaterial();
        when(view.material(anyInt(), anyInt(), anyInt())).thenReturn(material);
        return view;
    }

    private static BlockData blockData(Material material) {
        BlockData data = mock(BlockData.class);
        when(data.getMaterial()).thenReturn(material);
        when(data.getAsString()).thenReturn("minecraft:" + material.name().toLowerCase());
        when(data.clone()).thenReturn(data);
        return data;
    }

    private record Change(long key, Material material) {
    }
}
