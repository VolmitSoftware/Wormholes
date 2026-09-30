package art.arcane.wormholes.modded;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.LoggerFactory;

final class MinecraftSectionCacheGameTest {
    private MinecraftSectionCacheGameTest() {
    }

    static void run(GameTestHelper helper) {
        WormholesModRuntime runtime = WormholesGameTests.RUNTIME;
        ServerLevel level = helper.getLevel();
        BlockPos base = helper.absolutePos(new BlockPos(2, 2, 2));
        BlockPos centre = base.offset(3, 3, 3);
        fill(level, base, 7, Blocks.STONE.defaultBlockState());
        for (int chunkX = (centre.getX() >> 4) - 1; chunkX <= (centre.getX() >> 4) + 1; chunkX++) {
            for (int chunkZ = (centre.getZ() >> 4) - 1; chunkZ <= (centre.getZ() >> 4) + 1; chunkZ++) {
                level.getChunk(chunkX, chunkZ);
            }
        }
        MinecraftProjectionService projections = runtime.projections();
        MinecraftProjectionWorldView view = projections.view(level);
        helper.startSequence().thenIdle(2).thenExecute(() -> {
            helper.assertTrue(view.sampleBlockData(centre.getX(), centre.getY(), centre.getZ()).is(Blocks.STONE),
                "Section cache view did not read the filled stone");
            int depth = view.buriedDepth(centre.getX(), centre.getY(), centre.getZ());
            helper.assertTrue(depth == 2, "Buried depth at the cube centre was " + depth + " instead of 2");
            int surface = view.buriedDepth(base.getX(), centre.getY(), centre.getZ());
            helper.assertTrue(surface == 0, "Buried depth at the cube face was " + surface + " instead of 0");
            helper.assertTrue(projections.sectionCacheSections() > 0 && projections.sectionCacheBytes() > 0L,
                "Section cache reported no cached sections");
            level.setBlockAndUpdate(centre.east(), Blocks.AIR.defaultBlockState());
        }).thenIdle(2).thenExecute(() -> {
            int depth = view.buriedDepth(centre.getX(), centre.getY(), centre.getZ());
            helper.assertTrue(depth == 0, "Buried depth after exposing the centre was " + depth + " instead of 0");
            helper.assertTrue(view.sampleBlockData(centre.getX() + 1, centre.getY(), centre.getZ()).isAir(),
                "Section cache served the removed block after its change mark");
            fill(level, base, 7, Blocks.AIR.defaultBlockState());
            LoggerFactory.getLogger("WormholesGameTest").info("WORMHOLES_GAME_TEST_PASS projection_section_cache_runtime cached_read buried_depth change_eviction");
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
