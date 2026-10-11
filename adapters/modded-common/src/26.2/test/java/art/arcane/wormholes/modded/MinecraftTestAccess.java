package art.arcane.wormholes.modded;

import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.EndPodiumFeature;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;

public final class MinecraftTestAccess {
    private MinecraftTestAccess() {
    }

    public static InterpolationHandler idleInterpolation() {
        return null;
    }

    public static HolderLookup.Provider registries() {
        return VanillaRegistries.createLookup();
    }

    public static void assertRescueCooldown(ServerPlayer player) {
        assertEquals(40, player.invulnerableTime);
    }

    public static IntList removed(ClientboundRemoveEntitiesPacket packet) {
        return packet.getEntityIds();
    }

    public static void recordPodium(WorldGenLevel generated, Map<BlockPos, BlockState> blocks) {
        when(generated.ensureCanWrite(any(BlockPos.class))).thenReturn(true);
        when(generated.setBlock(any(BlockPos.class), any(BlockState.class), anyInt())).thenAnswer(invocation -> {
            blocks.put(((BlockPos) invocation.getArgument(0)).immutable(), invocation.getArgument(1));
            return true;
        });
    }

    public static void podium(WorldGenLevel generated, boolean active, BlockPos center, long seed) {
        new EndPodiumFeature(active).place(NoneFeatureConfiguration.INSTANCE, generated, null, RandomSource.create(seed), center);
    }
}
