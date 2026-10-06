package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.client.render.PortalEnvironmentTest;
import art.arcane.optics.stream.ProjectionEnvironment;
import art.arcane.wormholes.network.client.ClientViewMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static art.arcane.wormholes.modded.client.ClientTravelTestFixtures.field;
import static art.arcane.wormholes.modded.client.ClientTravelTestFixtures.set;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

public class ClientPreparedTravelUnloadTest extends MinecraftTestBase {
    @Test
    public void paperSourceUnloadsAfterRespawnPreserveRealDestinationChunksUntilPosition() throws ReflectiveOperationException {
        Minecraft minecraft = mock(Minecraft.class);
        when(minecraft.isSameThread()).thenReturn(true);
        ClientLevel source = mock(ClientLevel.class);
        ClientLevel destination = mock(ClientLevel.class);
        ClientChunkCache cache = mock(ClientChunkCache.class);
        LevelChunk chunk = mock(LevelChunk.class);
        AtomicBoolean resident = new AtomicBoolean(true);
        when(destination.getChunkSource()).thenReturn(cache);
        when(cache.getChunk(anyInt(), anyInt(), eq(ChunkStatus.FULL), eq(false)))
            .thenAnswer(ignored -> resident.get() ? chunk : null);
        ClientPreparedTravel travel = adopted(source, destination);
        ClientViewMessage.TravelBegin begin = begin();
        set(travel, "begin", begin);
        ClientboundForgetLevelChunkPacket forget = new ClientboundForgetLevelChunkPacket(new ChunkPos(0, 0));
        try (MockedStatic<Minecraft> access = mockStatic(Minecraft.class)) {
            access.when(Minecraft::getInstance).thenReturn(minecraft);
            assertTrue(covers(travel, begin.arrival()));
            if (!travel.deferWorldPacket(forget, () -> resident.set(false))) {
                resident.set(false);
            }
            assertTrue(covers(travel, begin.arrival()));
            assertTrue(packets(travel).isEmpty());
            set(travel, "positionConfirmed", true);
            assertFalse(travel.deferWorldPacket(forget, () -> resident.set(false)));
            resident.set(false);
            assertFalse(covers(travel, begin.arrival()));
        }
    }

    @Test
    public void sameWorldAndUnadoptedUnloadsRemainOrdinaryAfterCommit() throws ReflectiveOperationException {
        Minecraft minecraft = mock(Minecraft.class);
        when(minecraft.isSameThread()).thenReturn(true);
        ClientLevel source = mock(ClientLevel.class);
        ClientLevel destination = mock(ClientLevel.class);
        ClientboundForgetLevelChunkPacket forget = new ClientboundForgetLevelChunkPacket(new ChunkPos(0, 0));
        try (MockedStatic<Minecraft> access = mockStatic(Minecraft.class)) {
            access.when(Minecraft::getInstance).thenReturn(minecraft);
            assertFalse(adopted(source, source).deferWorldPacket(forget, () -> { }));
            ClientPreparedTravel travel = adopted(source, destination);
            set(travel, "adopted", false);
            assertFalse(travel.deferWorldPacket(forget, () -> { }));
        }
    }

    @Test
    public void destinationCenterAndRadiusPacketsAreNotSuppressedDuringAdoption() throws ReflectiveOperationException {
        Minecraft minecraft = mock(Minecraft.class);
        when(minecraft.isSameThread()).thenReturn(true);
        ClientPreparedTravel travel = adopted(mock(ClientLevel.class), mock(ClientLevel.class));
        try (MockedStatic<Minecraft> access = mockStatic(Minecraft.class)) {
            access.when(Minecraft::getInstance).thenReturn(minecraft);
            assertFalse(travel.deferWorldPacket(new ClientboundSetChunkCacheCenterPacket(0, 0), () -> { }));
            assertFalse(travel.deferWorldPacket(new ClientboundSetChunkCacheRadiusPacket(12), () -> { }));
        }
    }

    private static ClientPreparedTravel adopted(ClientLevel source, ClientLevel destination) throws ReflectiveOperationException {
        Class<?> type = Class.forName(ClientPreparedTravel.class.getName() + "$Prediction");
        Object prediction = mock(type);
        set(prediction, "source", source);
        set(prediction, "packets", new ArrayDeque<Runnable>());
        ClientPreparedTravel travel = new ClientPreparedTravel(ignored -> { });
        set(travel, "prediction", prediction);
        set(travel, "staged", destination);
        set(travel, "adopted", true);
        set(travel, "commit", mock(ClientViewMessage.TravelCommit.class));
        return travel;
    }

    private static ClientViewMessage.TravelBegin begin() {
        List<ClientViewMessage.TravelCoordinate> coordinates = new ArrayList<>(49);
        for (int z = -3; z <= 3; z++) {
            for (int x = -3; x <= 3; x++) {
                coordinates.add(new ClientViewMessage.TravelCoordinate(x, z));
            }
        }
        return new ClientViewMessage.TravelBegin(new UUID(4, 17), 8, new UUID(2, 9), "minecraft:the_nether",
            ClientTravelTestFixtures.geometry(), ProjectionEnvironment.Transform.IDENTITY,
            new ClientViewMessage.TravelWorld("minecraft:overworld", "minecraft:overworld", 7, false, false, 63, -64, 384),
            new ClientViewMessage.TravelPose(0, 80, 0, 0, 0), coordinates,
            PortalEnvironmentTest.environment(ProjectionEnvironment.Transform.IDENTITY), 30_000);
    }

    private static boolean covers(ClientPreparedTravel travel, ClientViewMessage.TravelPose pose) throws ReflectiveOperationException {
        Method method = ClientPreparedTravel.class.getDeclaredMethod("covers", ClientViewMessage.TravelPose.class);
        method.setAccessible(true);
        return (boolean) method.invoke(travel, pose);
    }

    private static ArrayDeque<?> packets(ClientPreparedTravel travel) throws ReflectiveOperationException {
        return (ArrayDeque<?>) field(field(travel, "prediction"), "packets");
    }
}
