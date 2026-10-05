package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.MinecraftChunkPacketEncoding;
import art.arcane.wormholes.modded.client.render.ClientSodiumTerrain;
import art.arcane.wormholes.modded.client.render.ClientTravelScene;
import art.arcane.wormholes.network.client.ClientViewMessage;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static net.minecraft.world.level.chunk.status.ChunkStatus.FULL;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ClientPreparedTravelDecodeTest {
    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void retainedColumnLightDeltaDirtiesOnlyItsChangedSectionBeforeShaderPreparation() throws ReflectiveOperationException {
        decode(true);
    }

    @Test
    public void unchangedPhysicalSectionsKeepNativeMeshesDespiteChangedPacketMetadata() throws ReflectiveOperationException {
        decode(false);
    }

    @Test
    public void nativeMetadataOnlyResendKeepsAllPhysicalGeometryAndRetiresOldByteProof() throws ReflectiveOperationException {
        nativeUpdates(false);
    }

    @Test
    public void nativeBlockAndQueuedLightChangesDirtyOnlyChangedSectionsInVanillaOrder() throws ReflectiveOperationException {
        nativeUpdates(true);
    }

    private static void nativeUpdates(boolean edits) throws ReflectiveOperationException {
        ClientPreparedTravel travel = new ClientPreparedTravel(ignored -> { });
        ClientLevel level = mock(ClientLevel.class);
        ClientChunkCache cache = mock(ClientChunkCache.class);
        LevelChunk chunk = mock(LevelChunk.class);
        LevelLightEngine engine = mock(LevelLightEngine.class);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(level.registryAccess()).thenReturn(RegistryAccess.EMPTY);
        when(level.getChunkSource()).thenReturn(cache);
        when(level.getLightEngine()).thenReturn(engine);
        when(level.getSectionsCount()).thenReturn(3);
        when(level.getMaxSectionY()).thenReturn(2);
        when(engine.getMinLightSection()).thenReturn(-1);
        when(cache.getChunk(0, 0, FULL, false)).thenReturn(chunk);
        ClientPacketListener connection = mock(ClientPacketListener.class);
        when(connection.registryAccess()).thenReturn(RegistryAccess.EMPTY);
        Minecraft minecraft = mock(Minecraft.class);
        minecraft.level = level;
        when(minecraft.getConnection()).thenReturn(connection);
        Map<ClientViewMessage.TravelCoordinate, byte[]> installed = new HashMap<>();
        installed.put(new ClientViewMessage.TravelCoordinate(0, 0), new byte[]{1});
        resident(travel, level, connection, installed);
        AtomicBoolean blocksApplied = new AtomicBoolean();
        AtomicBoolean lightApplied = new AtomicBoolean();
        ClientTravelSectionState original = new ClientTravelSectionState(new byte[]{1}, null, null, Map.of());
        ClientTravelSectionState blockChange = new ClientTravelSectionState(new byte[]{2}, null, null, Map.of());
        try (MockedStatic<Minecraft> access = mockStatic(Minecraft.class);
             MockedStatic<ClientTravelSectionState> states = mockStatic(ClientTravelSectionState.class);
             MockedStatic<ClientSodiumTerrain> terrain = mockStatic(ClientSodiumTerrain.class);
             MockedStatic<WormholesClient> clients = mockStatic(WormholesClient.class)) {
            access.when(Minecraft::getInstance).thenReturn(minecraft);
            states.when(() -> ClientTravelSectionState.captureBlocks(eq(level), eq(0), anyInt(), eq(0)))
                .thenAnswer(call -> edits && blocksApplied.get() && call.<Integer>getArgument(2) == 1 ? blockChange : original);
            clients.when(() -> WormholesClient.localSectionChanged(eq(level), eq(0), anyInt(), eq(0))).thenAnswer(call -> {
                travel.sectionChanged(level, 0, call.getArgument(2), 0);
                return null;
            });
            LevelChunk replaced = travel.replaceNativeColumn(level, 0, 0, () -> {
                assertTrue(ClientPreparedTravel.applyingColumn(level, 0, 0));
                travel.chunkChanged(level, 0, 0);
                blocksApplied.set(true);
                return chunk;
            });
            assertSame(chunk, replaced);
            assertFalse(ClientPreparedTravel.applyingColumn(level, 0, 0));
            assertTrue(installed.isEmpty());
            ArrayDeque<Runnable> vanilla = new ArrayDeque<>();
            vanilla.add(() -> assertFalse(lightApplied.get()));
            vanilla.add(travel.nativeLightUpdate(level, 0, 0, () -> {
                assertTrue(blocksApplied.get());
                assertTrue(ClientPreparedTravel.applyingColumn(level, 0, 0));
                if (edits) {
                    ClientPreparedTravel.nativeLightSectionChanged(cache, SectionPos.asLong(0, 2, 0));
                }
                lightApplied.set(true);
            }));
            assertFalse(lightApplied.get());
            vanilla.remove().run();
            vanilla.remove().run();
            assertTrue(lightApplied.get());
            assertFalse(ClientPreparedTravel.applyingColumn(level, 0, 0));
            states.verify(() -> ClientTravelSectionState.captureBlocks(eq(level), eq(0), anyInt(), eq(0)), times(10));
            if (edits) {
                verify(level).setSectionDirtyWithNeighbors(0, 1, 0);
                verify(level).setSectionDirtyWithNeighbors(0, 2, 0);
                terrain.verify(() -> ClientSodiumTerrain.dirty(level, SectionPos.asLong(0, 1, 0)));
                terrain.verify(() -> ClientSodiumTerrain.dirty(level, SectionPos.asLong(0, 2, 0)));
                terrain.verify(() -> ClientSodiumTerrain.handlesMainUpdates(level), times(2));
            }
            verify(level, never()).setSectionRangeDirty(anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt());
            terrain.verifyNoMoreInteractions();
        }
    }

    @Test
    public void failedNativeReplacementRestoresScopeAndKeepsOrdinaryInvalidation() throws ReflectiveOperationException {
        ClientPreparedTravel travel = new ClientPreparedTravel(ignored -> { });
        ClientLevel level = mock(ClientLevel.class);
        ClientChunkCache cache = mock(ClientChunkCache.class);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(level.registryAccess()).thenReturn(RegistryAccess.EMPTY);
        when(level.getChunkSource()).thenReturn(cache);
        when(cache.getChunk(0, 0, FULL, false)).thenReturn(mock(LevelChunk.class));
        ClientPacketListener connection = mock(ClientPacketListener.class);
        when(connection.registryAccess()).thenReturn(RegistryAccess.EMPTY);
        Minecraft minecraft = mock(Minecraft.class);
        minecraft.level = level;
        when(minecraft.getConnection()).thenReturn(connection);
        resident(travel, level, connection, new HashMap<>());
        RuntimeException failure = new IllegalStateException("replacement failed");
        try (MockedStatic<Minecraft> access = mockStatic(Minecraft.class);
             MockedStatic<ClientTravelSectionState> states = mockStatic(ClientTravelSectionState.class);
             MockedStatic<WormholesClient> clients = mockStatic(WormholesClient.class)) {
            access.when(Minecraft::getInstance).thenReturn(minecraft);
            ClientTravelSectionState snapshot = new ClientTravelSectionState(null, null, null, Map.of());
            states.when(() -> ClientTravelSectionState.captureBlocks(eq(level), eq(0), anyInt(), eq(0))).thenReturn(snapshot);
            assertSame(failure, assertThrows(RuntimeException.class, () -> travel.replaceNativeColumn(level, 0, 0, () -> {
                assertTrue(ClientPreparedTravel.applyingColumn(level, 0, 0));
                throw failure;
            })));
            assertFalse(ClientPreparedTravel.applyingColumn(level, 0, 0));
            verify(level).setSectionRangeDirty(-1, 0, -1, 1, 0, 1);
            clients.verify(() -> WormholesClient.localChunkChanged(level, 0, 0));
            when(cache.getChunk(0, 0, FULL, false)).thenReturn(null);
            LevelChunk ordinary = mock(LevelChunk.class);
            assertSame(ordinary, travel.replaceNativeColumn(level, 0, 0, () -> {
                assertFalse(ClientPreparedTravel.applyingColumn(level, 0, 0));
                return ordinary;
            }));
        }
    }

    private static void resident(ClientPreparedTravel travel, ClientLevel level, ClientPacketListener connection,
                                  Map<ClientViewMessage.TravelCoordinate, byte[]> payloads) throws ReflectiveOperationException {
        Class<?> type = Class.forName(ClientPreparedTravel.class.getName() + "$ResidentColumns");
        Constructor<?> constructor = type.getDeclaredConstructor(ClientLevel.class, ClientPacketListener.class, Object.class, long.class, Map.class);
        constructor.setAccessible(true);
        Field field = ClientPreparedTravel.class.getDeclaredField("resident");
        field.setAccessible(true);
        field.set(travel, constructor.newInstance(level, connection, RegistryAccess.EMPTY, System.currentTimeMillis() + 60_000, payloads));
    }

    private static void decode(boolean lightChanged) throws ReflectiveOperationException {
        ClientPreparedTravel travel = new ClientPreparedTravel(ignored -> { });
        ClientLevel level = mock(ClientLevel.class);
        ClientChunkCache cache = mock(ClientChunkCache.class);
        LevelChunk chunk = mock(LevelChunk.class);
        LevelLightEngine engine = mock(LevelLightEngine.class);
        when(level.registryAccess()).thenReturn(RegistryAccess.EMPTY);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(level.registryAccess()).thenReturn(RegistryAccess.EMPTY);
        when(level.getChunkSource()).thenReturn(cache);
        when(level.getLightEngine()).thenReturn(engine);
        when(cache.getLightEngine()).thenReturn(engine);
        when(cache.getChunk(0, 0, FULL, false)).thenReturn(chunk);
        when(level.getSectionsCount()).thenReturn(2);
        when(level.getMaxSectionY()).thenReturn(1);
        when(chunk.getSections()).thenReturn(new LevelChunkSection[0]);
        AtomicBoolean applied = new AtomicBoolean();
        when(cache.replaceWithPacketData(eq(0), eq(0), any())).thenAnswer(call -> {
            travel.chunkChanged(level, 0, 0);
            travel.sectionChanged(level, 0, 1, 0);
            applied.set(true);
            return chunk;
        });
        doAnswer(call -> {
            travel.sectionChanged(level, 0, 1, 0);
            return null;
        }).when(engine).runLightUpdates();
        ClientTravelSectionState original = new ClientTravelSectionState(new byte[]{1}, null, new byte[]{0}, Map.of());
        ClientTravelSectionState updated = new ClientTravelSectionState(new byte[]{1}, null, new byte[]{1}, Map.of());
        Map<ClientViewMessage.TravelCoordinate, Integer> decoded = new HashMap<>();
        LongOpenHashSet changed = new LongOpenHashSet();
        Class<?> type = Class.forName(ClientPreparedTravel.class.getName() + "$Column");
        Constructor<?> constructor = type.getDeclaredConstructor(int.class, int.class, int.class, byte[].class);
        constructor.setAccessible(true);
        Object column = constructor.newInstance(0, 0, 7, MinecraftChunkPacketEncoding.encode(RegistryAccess.EMPTY, packet()));
        Method decode = ClientPreparedTravel.class.getDeclaredMethod("decode", ClientLevel.class, ClientTravelScene.class,
            Map.class, LongOpenHashSet.class, type);
        decode.setAccessible(true);
        try (MockedStatic<ClientTravelSectionState> states = mockStatic(ClientTravelSectionState.class);
             MockedStatic<ClientSodiumTerrain> terrain = mockStatic(ClientSodiumTerrain.class);
             MockedStatic<ClientPreparedTravel.SodiumChunks> sodium = mockStatic(ClientPreparedTravel.SodiumChunks.class)) {
            terrain.when(() -> ClientSodiumTerrain.usesPreparedTerrain(level)).thenReturn(false);
            states.when(() -> ClientTravelSectionState.capture(eq(level), eq(0), anyInt(), eq(0)))
                .thenAnswer(call -> lightChanged && applied.get() && call.<Integer>getArgument(2) == 1 ? updated : original);
            decode.invoke(null, level, null, decoded, changed, column);
            assertEquals(Integer.valueOf(7), decoded.get(new ClientViewMessage.TravelCoordinate(0, 0)));
            verify(level, never()).setSectionRangeDirty(anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt());
            verify(engine).runLightUpdates();
            sodium.verify(() -> ClientPreparedTravel.SodiumChunks.lightReady(level, 0, 0));
            if (lightChanged) {
                assertEquals(LongOpenHashSet.of(SectionPos.asLong(0, 1, 0)), changed);
                terrain.verify(() -> ClientSodiumTerrain.dirty(level, SectionPos.asLong(0, 1, 0)));
            } else {
                assertTrue(changed.isEmpty());
            }
            terrain.verify(() -> ClientSodiumTerrain.usesPreparedTerrain(level), never());
            terrain.verifyNoMoreInteractions();
            travel.sectionChanged(level, 0, 0, 0);
            terrain.verify(() -> ClientSodiumTerrain.dirty(level, SectionPos.asLong(0, 0, 0)));
        }
    }

    private static ClientboundLevelChunkWithLightPacket packet() {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            buffer.writeVarInt(0);
            buffer.writeByteArray(new byte[]{1, 2});
            buffer.writeVarInt(0);
            return new ClientboundLevelChunkWithLightPacket(0, 0, ClientboundLevelChunkPacketData.STREAM_CODEC.decode(buffer),
                new ClientboundLightUpdatePacketData(new BitSet(), new BitSet(), new BitSet(), new BitSet(), List.of(), List.of()));
        } finally {
            buffer.release();
        }
    }
}
