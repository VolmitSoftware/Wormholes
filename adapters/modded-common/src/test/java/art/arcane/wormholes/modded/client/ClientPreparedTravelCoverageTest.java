package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.client.render.PortalEnvironmentTest;
import art.arcane.optics.stream.ProjectionEnvironment;
import art.arcane.wormholes.network.client.ClientViewMessage;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static art.arcane.wormholes.modded.client.ClientTravelTestFixtures.set;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ClientPreparedTravelCoverageTest extends MinecraftTestBase {
    @Test
    public void realNativeNeighborLossAndArrivalBubbleEscapeRequireNormalWaiting() throws ReflectiveOperationException {
        ClientPreparedTravel travel = new ClientPreparedTravel(ignored -> { });
        List<ClientViewMessage.TravelCoordinate> coordinates = new ArrayList<>(49);
        for (int z = -3; z <= 3; z++) {
            for (int x = -3; x <= 3; x++) {
                coordinates.add(new ClientViewMessage.TravelCoordinate(x, z));
            }
        }
        ClientViewMessage.TravelBegin begin = new ClientViewMessage.TravelBegin(new UUID(4, 17), 8, new UUID(2, 9),
            "minecraft:the_nether", ClientTravelTestFixtures.geometry(), ProjectionEnvironment.Transform.IDENTITY, new ClientViewMessage.TravelWorld("minecraft:overworld", "minecraft:overworld",
            7, false, false, 63, -64, 384), new ClientViewMessage.TravelPose(0, 80, 0, 0, 0), coordinates,
            PortalEnvironmentTest.environment(ProjectionEnvironment.Transform.IDENTITY), 30_000);
        ClientLevel level = mock(ClientLevel.class);
        ClientChunkCache cache = mock(ClientChunkCache.class);
        LevelChunk chunk = mock(LevelChunk.class);
        when(level.getChunkSource()).thenReturn(cache);
        when(level.hasChunk(anyInt(), anyInt())).thenReturn(true);
        when(cache.getChunk(anyInt(), anyInt(), eq(ChunkStatus.FULL), eq(false))).thenReturn(chunk);
        set(travel, "begin", begin);
        set(travel, "staged", level);
        assertTrue(covers(travel, begin.arrival()));
        when(cache.getChunk(-1, 0, ChunkStatus.FULL, false)).thenReturn(null);
        assertFalse(covers(travel, begin.arrival()));
        when(cache.getChunk(-1, 0, ChunkStatus.FULL, false)).thenReturn(chunk);
        assertTrue(covers(travel, begin.arrival()));
        assertFalse(covers(travel, new ClientViewMessage.TravelPose(48, 80, 0, 0, 0)));
        assertFalse(covers(travel, new ClientViewMessage.TravelPose(0, -65, 0, 0, 0)));
        assertFalse(covers(travel, new ClientViewMessage.TravelPose(0, 319, 0, 0, 0)));
    }

    private static boolean covers(ClientPreparedTravel travel, ClientViewMessage.TravelPose pose) throws ReflectiveOperationException {
        Method method = ClientPreparedTravel.class.getDeclaredMethod("covers", ClientViewMessage.TravelPose.class);
        method.setAccessible(true);
        return (boolean) method.invoke(travel, pose);
    }
}
