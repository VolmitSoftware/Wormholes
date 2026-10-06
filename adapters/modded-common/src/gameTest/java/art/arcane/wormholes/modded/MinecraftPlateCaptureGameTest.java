package art.arcane.wormholes.modded;

import art.arcane.optics.frame.Frame;
import art.arcane.optics.aperture.ApertureCells;
import art.arcane.optics.volume.LodPolicy;
import art.arcane.optics.plate.PlateCaptureJob;
import art.arcane.optics.plate.ViewPlate;
import art.arcane.optics.plate.ViewPlateBuilder;
import art.arcane.optics.plate.ViewPlateKey;
import art.arcane.optics.view.ContentView;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

final class MinecraftPlateCaptureGameTest {
    private MinecraftPlateCaptureGameTest() {
    }

    static void run(GameTestHelper helper) {
        WormholesModRuntime runtime = WormholesGameTests.RUNTIME;
        ServerLevel level = helper.getLevel();
        BlockPos base = helper.absolutePos(new BlockPos(6, 2, 6));
        fill(level, base, 5, Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(base.offset(2, 2, 2), Blocks.GOLD_BLOCK.defaultBlockState());
        MinecraftProjectionService projections = runtime.projections();
        MinecraftProjectionWorldView view = projections.view(level);
        BlockPos aperture = helper.absolutePos(new BlockPos(6, 2, 14));
        ApertureCells geometry = new ApertureCells();
        geometry.setArea(new Box(aperture.getX(), aperture.getX() + 5, aperture.getY(), aperture.getY() + 5,
            aperture.getZ(), aperture.getZ() + 1));
        Frame frame = Frame.canonical(Face.S);
        double originX = aperture.getX() + 2.5D;
        double originY = aperture.getY() + 2.5D;
        double originZ = aperture.getZ() + 0.5D;
        ViewPlateKey key = new ViewPlateKey(UUID.randomUUID(), view, true, 0, 0L);
        ViewPlateBuilder.Request<BlockState, BlockState, ContentView<BlockState, BlockState>> request = new ViewPlateBuilder.Request<>(
            key, geometry, view, frame, frame, originX, originY, originZ, originX, originY, originZ, false, 0,
            12.0D, 4.0D, 0.0D, false, Blocks.AIR.defaultBlockState(), LodPolicy.NONE,
            false, 0L, 1L, projections.changes().currentVersion(), MinecraftProjectorBlocks.INSTANCE);
        AtomicReference<MinecraftCapturedChunkView> capturedView = new AtomicReference<>();
        PlateCaptureJob<BlockState, ServerLevel, MinecraftPlateCaptureSource.CapturedChunk> job = new PlateCaptureJob<>(new PlateCaptureJob.Plan<>(
            key, level, ViewPlateBuilder.footprint(request), new MinecraftPlateCaptureSource(runtime, MinecraftPlateCaptureSource.Options.column(view.worldId(), true)), captured -> {
                MinecraftCapturedChunkView built = new MinecraftCapturedChunkView(view.worldId(), view.getMinHeight(), view.getMaxHeight(), 0L, captured);
                capturedView.set(built);
                return ViewPlateBuilder.job(request.withDestView(built));
            }));
        ViewPlate<BlockState> immediate = projections.plates().current(key, 0L, 1L, projections.changes(), false, previous -> job);
        helper.assertTrue(immediate == null, "Plate cache returned a plate before its capture ran");
        helper.assertTrue(projections.plateCaptureQueueSize() >= 1, "Plate capture job was not queued for the server thread");
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(projections.plateCaptureQueueSize() == 0
            && projections.plates().peek(key) != null, "Captured plate has not been built")).thenExecute(() -> {
                MinecraftCapturedChunkView captured = capturedView.get();
                helper.assertTrue(captured != null, "Capture job finished without a captured view");
                for (int x = 0; x < 5; x++) {
                    for (int y = 0; y < 5; y++) {
                        for (int z = 0; z < 5; z++) {
                            BlockPos position = base.offset(x, y, z);
                            BlockState expected = level.getBlockState(position);
                            BlockState sampled = captured.sampleBlockData(position.getX(), position.getY(), position.getZ());
                            helper.assertTrue(expected == sampled, "Captured view diverged from the level at " + position);
                        }
                    }
                }
                BlockPos above = base.offset(2, 6, 2);
                helper.assertTrue(captured.sampleBlockData(above.getX(), above.getY(), above.getZ()).isAir(),
                    "Captured view did not read air above the cube");
                ViewPlate<BlockState> plate = projections.plates().peek(key);
                helper.assertTrue(plate != null && !plate.isEmpty(), "Captured plate holds no cells");
                helper.assertTrue(plate.destinationWorldId().equals(view.worldId()), "Captured plate lost its destination world");
                helper.assertTrue(projections.plates().size() >= 1 && projections.plates().bytes() > 0L,
                    "Plate stats did not account for the captured plate");
                projections.plates().invalidate(key);
                fill(level, base, 5, Blocks.AIR.defaultBlockState());
                LoggerFactory.getLogger("WormholesGameTest").info("WORMHOLES_GAME_TEST_PASS projection_plate_capture_runtime queued_capture server_thread_snapshot worker_build level_parity");
            }).thenSucceed();
    }

    private static void fill(ServerLevel level, BlockPos base, int size, BlockState state) {
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                for (int z = 0; z < size; z++) {
                    level.setBlockAndUpdate(base.offset(x, y, z), state);
                }
            }
        }
    }
}
