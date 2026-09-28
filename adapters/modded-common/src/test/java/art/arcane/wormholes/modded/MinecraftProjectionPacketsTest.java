package art.arcane.wormholes.modded;

import it.unimi.dsi.fastutil.shorts.Short2ObjectMap;
import it.unimi.dsi.fastutil.shorts.Short2ObjectOpenHashMap;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class MinecraftProjectionPacketsTest {
    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        for (Block block : BuiltInRegistries.BLOCK) {
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                if (Block.BLOCK_STATE_REGISTRY.getId(state) < 0) {
                    Block.BLOCK_STATE_REGISTRY.add(state);
                }
            }
        }
    }

    @Test
    public void sectionPacketPreservesNegativeCoordinatesAndEveryCell() {
        Short2ObjectMap<BlockState> changes = new Short2ObjectOpenHashMap<>();
        Map<BlockPos, BlockState> expected = new HashMap<>();
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    BlockState state = (x + y + z) % 2 == 0 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
                    changes.put((short) ((x << 8) | (z << 4) | y), state);
                    expected.put(new BlockPos(-32 + x, -64 + y, 48 + z), state);
                }
            }
        }
        Map<BlockPos, BlockState> actual = new HashMap<>();
        MinecraftProjectionPackets.sectionPacket(SectionPos.asLong(-2, -4, 3), changes)
            .runUpdates((position, state) -> actual.put(position.immutable(), state));
        assertEquals(expected.size(), actual.size());
        for (Map.Entry<BlockPos, BlockState> entry : expected.entrySet()) {
            assertSame(entry.getKey().toString(), entry.getValue(), actual.get(entry.getKey()));
        }
    }
}
