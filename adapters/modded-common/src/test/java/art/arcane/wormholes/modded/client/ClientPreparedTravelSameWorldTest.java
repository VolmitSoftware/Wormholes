package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.optics.crossing.Pose;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.client.render.ClientTravelScene;
import art.arcane.wormholes.modded.client.render.PortalEnvironmentTest;
import art.arcane.optics.frame.OpticTransform;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static art.arcane.wormholes.modded.client.ClientTravelTestFixtures.set;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import art.arcane.wormholes.network.client.TravelMessage;

public class ClientPreparedTravelSameWorldTest extends MinecraftTestBase {
    @Test
    public void loadedSameWorldArrivalKeepsLiveLevelAndCacheWithoutResettingOrReplacingColumns() throws ReflectiveOperationException {
        ClientLevel live = mock(ClientLevel.class);
        ClientChunkCache cache = mock(ClientChunkCache.class);
        LevelChunk column = mock(LevelChunk.class);
        ClientTravelScene scene = mock(ClientTravelScene.class);
        when(live.getChunkSource()).thenReturn(cache);
        when(cache.getChunk(anyInt(), anyInt(), eq(ChunkStatus.FULL), eq(false))).thenReturn(column);
        List<TravelMessage.TravelCoordinate> coordinates = new ArrayList<>(49);
        for (int z = -3; z <= 3; z++) {
            for (int x = -3; x <= 3; x++) {
                coordinates.add(new TravelMessage.TravelCoordinate(x, z));
            }
        }
        TravelMessage.TravelBegin begin = new TravelMessage.TravelBegin(new UUID(1, 2), 1, new UUID(2, 3),
            "minecraft:overworld", ClientTravelTestFixtures.geometry(), OpticTransform.IDENTITY, 1.0F,
            new TravelMessage.TravelWorld("minecraft:overworld", "minecraft:overworld", 1, false, false, 63, -64, 384),
            new TravelMessage.TravelPose(0, 80, 0, 0, 0), coordinates,
            PortalEnvironmentTest.environment(OpticTransform.IDENTITY), 30_000, TravelMessage.ArrivalRules.FRAME, false, 0, false);
        ClientPreparedTravel travel = ClientTravelTestFixtures.travel(ignored -> { });
        set(travel, "begin", begin);
        set(travel, "scene", scene);
        set(travel, "staged", mock(ClientLevel.class));
        Pose motion = new Pose(new Vec3d(0, 80, 0), new Vec3d(0, 80, 0), new Vec3d(0, 80, 0),
            new Vec3d(0, 0, 0), 0, 0, 0, 0, 0, 0, 0, 0);
        Method method = ClientPreparedTravel.class.getDeclaredMethod("prepareSameWorld", ClientLevel.class, Pose.class, Pose.class);
        method.setAccessible(true);
        method.invoke(travel, live, motion, motion);
        assertSame(live, travel.level());
        verify(scene).adoptLevel(live);
        for (TravelMessage.TravelCoordinate coordinate : coordinates) {
            verify(cache).getChunk(coordinate.x(), coordinate.z(), ChunkStatus.FULL, false);
        }
        verifyNoMoreInteractions(cache);
    }
}
