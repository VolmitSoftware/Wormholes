package art.arcane.wormholes.modded;

import art.arcane.optics.math.CellKeys;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MinecraftProjectionMutationTest extends MinecraftTestBase {
    @Test
    public void successfulMutationsStampCanonicalWorldWithoutInstantiatingAProjectionView() {
        WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        when(runtime.server()).thenReturn(mock(MinecraftServer.class));
        ServerLevel world = mock(ServerLevel.class);
        when(world.dimension()).thenReturn(Level.OVERWORLD);
        MinecraftProjectionService service = new MinecraftProjectionService(runtime);
        long before = service.changes().currentVersion();

        service.blockChanged(world, new BlockPos(-17, 64, 33));
        service.columnChanged(world, 7, -9);

        LongArrayList dirty = new LongArrayList();
        assertTrue(service.changes().collectDirtySince(MinecraftProjectionWorldView.worldId(world), -2, -9, 7, 2, before, dirty));
        assertTrue(dirty.contains(CellKeys.chunkKey(-2, 2)));
        assertTrue(dirty.contains(CellKeys.chunkKey(7, -9)));
        assertEquals(2, dirty.size());
        assertTrue(service.changes().currentVersion() > before);
        service.close();
    }

    @Test
    public void retiredProjectionServiceDoesNotRepopulateWorldTracking() {
        WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        when(runtime.server()).thenReturn(mock(MinecraftServer.class));
        ServerLevel world = mock(ServerLevel.class);
        when(world.dimension()).thenReturn(Level.OVERWORLD);
        MinecraftProjectionService service = new MinecraftProjectionService(runtime);
        service.close();
        long before = service.changes().currentVersion();

        service.blockChanged(world, new BlockPos(0, 64, 0));
        service.columnChanged(world, 0, 0);

        assertEquals(before, service.changes().currentVersion());
        LongArrayList dirty = new LongArrayList();
        assertTrue(service.changes().collectDirtySince(MinecraftProjectionWorldView.worldId(world), 0, 0, 0, 0, before, dirty));
        assertTrue(dirty.isEmpty());
    }
}
