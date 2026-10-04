package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.network.client.ClientTravelHash;
import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.wormholes.network.client.ClientViewCapability;
import art.arcane.wormholes.network.client.ClientViewProtocol;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.Minecraft;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import io.netty.buffer.Unpooled;
import art.arcane.wormholes.modded.client.render.ClientTravelScene;
import art.arcane.wormholes.modded.client.render.ClientPortalRenderer;
import art.arcane.wormholes.modded.client.render.PortalEnvironmentTest;
import art.arcane.wormholes.modded.clientview.MinecraftPortalEnvironment;
import art.arcane.wormholes.network.client.ClientViewEnvironment;
import art.arcane.wormholes.geometry.GeometryVector;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import org.mockito.MockedStatic;
import org.junit.Test;
import org.junit.BeforeClass;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Collection;
import java.util.BitSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.withSettings;
import static org.mockito.ArgumentMatchers.eq;
import static net.minecraft.world.level.chunk.status.ChunkStatus.FULL;

public class ClientPreparedTravelCachedTest {
    @BeforeClass
    public static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void originalChunkPacketsUseListenerWorldAndRemainAvailableAcrossAdoption() throws ReflectiveOperationException {
        Method fixture = ClientPreparedTravelPendingTest.class.getDeclaredMethod("begin", long.class);
        fixture.setAccessible(true);
        ClientPreparedTravel travel = new ClientPreparedTravel(ignored -> { });
        set(travel, "begin", fixture.invoke(null, 12L));
        ClientTravelCache cache = (ClientTravelCache) get(travel, "cache");
        Minecraft minecraft = mock(Minecraft.class);
        ClientPacketListener connection = mock(ClientPacketListener.class);
        when(connection.registryAccess()).thenReturn(RegistryAccess.EMPTY);
        when(minecraft.getConnection()).thenReturn(connection);
        ClientLevel source = mock(ClientLevel.class);
        when(source.dimension()).thenReturn(Level.NETHER);
        when(source.registryAccess()).thenReturn(RegistryAccess.EMPTY);
        ClientLevel destination = mock(ClientLevel.class);
        when(destination.dimension()).thenReturn(Level.OVERWORLD);
        when(destination.registryAccess()).thenReturn(RegistryAccess.EMPTY);
        minecraft.level = destination;
        WormholesClient client = mock(WormholesClient.class);
        ClientViewSession session = mock(ClientViewSession.class);
        when(client.session()).thenReturn(session);
        when(session.active()).thenReturn(true);
        when(session.has(ClientViewCapability.PREPARED_TRAVEL_CACHE)).thenReturn(true);
        byte[] raw = {7, 1, 9};
        ClientboundLevelChunkWithLightPacket original = nativePacket(0, 0, raw);
        byte[] expected = encoded(source, original);
        try (MockedStatic<Minecraft> access = mockStatic(Minecraft.class);
             MockedStatic<WormholesClient> clients = mockStatic(WormholesClient.class)) {
            access.when(Minecraft::getInstance).thenReturn(minecraft);
            clients.when(WormholesClient::instance).thenReturn(client);
            travel.rememberNativeChunk(source, original);
            assertArrayEquals(expected, cache.get("minecraft:the_nether", 0, 0, ClientTravelHash.of(expected)));
            assertNull(cache.peek("minecraft:overworld", 0, 0));
            raw[0] = 3;
            assertArrayEquals(expected, cache.peek("minecraft:the_nether", 0, 0));
            set(travel, "adopted", true);
            ClientboundLevelChunkWithLightPacket arrived = nativePacket(0, 0, new byte[]{2});
            byte[] received = encoded(destination, arrived);
            travel.rememberNativeChunk(destination, arrived);
            assertArrayEquals(received, cache.peek("minecraft:overworld", 0, 0));
            assertArrayEquals(expected, cache.peek("minecraft:the_nether", 0, 0));
            travel.rememberNativeChunk(source, nativePacket(128, 0, new byte[]{3}));
            assertNull(cache.peek("minecraft:the_nether", 128, 0));
            when(session.has(ClientViewCapability.PREPARED_TRAVEL_CACHE)).thenReturn(false);
            travel.rememberNativeChunk(source, nativePacket(0, 0, new byte[]{4}));
            assertArrayEquals(expected, cache.peek("minecraft:the_nether", 0, 0));
        }
    }

    @Test
    public void sourceCaptureUsesRetainedNativeBytesWithoutReserializingLoadedChunks() throws ReflectiveOperationException {
        Method fixture = ClientPreparedTravelPendingTest.class.getDeclaredMethod("begin", long.class);
        fixture.setAccessible(true);
        ClientViewMessage.TravelBegin begin = (ClientViewMessage.TravelBegin) fixture.invoke(null, 12L);
        ClientPreparedTravel travel = new ClientPreparedTravel(ignored -> { });
        set(travel, "begin", begin);
        Class<?> sourceType = Class.forName(ClientPreparedTravel.class.getName() + "$SourcePreparation");
        Constructor<?> constructor = sourceType.getDeclaredConstructor(ClientViewMessage.TravelBegin.class);
        constructor.setAccessible(true);
        Object preparation = constructor.newInstance(begin);
        set(travel, "sourcePreparation", preparation);
        ClientTravelCache cache = (ClientTravelCache) get(travel, "cache");
        byte[] retained = {8, 3, 5};
        cache.put("minecraft:the_nether", -3, -3, retained);
        ClientLevel level = mock(ClientLevel.class);
        when(level.dimension()).thenReturn(Level.NETHER);
        ClientChunkCache chunks = mock(ClientChunkCache.class);
        LevelChunk chunk = mock(LevelChunk.class);
        when(level.getChunkSource()).thenReturn(chunks);
        when(chunks.getChunk(eq(-3), eq(-3), eq(FULL), eq(false))).thenReturn(chunk);
        Minecraft minecraft = mock(Minecraft.class);
        minecraft.level = level;
        minecraft.player = mock(LocalPlayer.class);
        Method capture = ClientPreparedTravel.class.getDeclaredMethod("captureSource");
        capture.setAccessible(true);
        try (MockedStatic<Minecraft> access = mockStatic(Minecraft.class)) {
            access.when(Minecraft::getInstance).thenReturn(minecraft);
            capture.invoke(travel);
        }
        assertEquals(1, get(travel, "sourceCapture"));
        Collection<?> columns = (Collection<?>) get(preparation, "columns");
        assertEquals(1, columns.size());
        Object column = columns.iterator().next();
        Method data = column.getClass().getDeclaredMethod("data");
        data.setAccessible(true);
        assertArrayEquals(retained, (byte[]) data.invoke(column));
        verify(chunk, never()).getSections();
        verify(level, never()).getLightEngine();
        assertEquals(0L, travel.readyRevision());
    }

    private static ClientboundLevelChunkWithLightPacket nativePacket(int x, int z, byte[] data) {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            buffer.writeVarInt(0);
            buffer.writeByteArray(data);
            buffer.writeVarInt(0);
            ClientboundLevelChunkPacketData chunk = ClientboundLevelChunkPacketData.STREAM_CODEC.decode(buffer);
            ClientboundLightUpdatePacketData light = new ClientboundLightUpdatePacketData(new BitSet(), new BitSet(),
                new BitSet(), new BitSet(), List.of(), List.of());
            return new ClientboundLevelChunkWithLightPacket(x, z, chunk, light);
        } finally {
            buffer.release();
        }
    }

    private static byte[] encoded(ClientLevel level, ClientboundLevelChunkWithLightPacket packet) throws ReflectiveOperationException {
        Method encode = ClientPreparedTravel.class.getDeclaredMethod("encodeNativeChunk", ClientLevel.class,
            ClientboundLevelChunkWithLightPacket.class);
        encode.setAccessible(true);
        return (byte[]) encode.invoke(null, level, packet);
    }

    @Test
    public void rendererInvalidationRestartsOnlyActiveUnpredictedSourcePreparation() throws ReflectiveOperationException {
        Method fixture = ClientPreparedTravelPendingTest.class.getDeclaredMethod("begin", long.class);
        fixture.setAccessible(true);
        ClientViewMessage.TravelBegin begin = (ClientViewMessage.TravelBegin) fixture.invoke(null, 12L);
        Class<?> sourceType = Class.forName(ClientPreparedTravel.class.getName() + "$SourcePreparation");
        Constructor<?> constructor = sourceType.getDeclaredConstructor(ClientViewMessage.TravelBegin.class);
        constructor.setAccessible(true);
        Class<?> predictionType = Class.forName(ClientPreparedTravel.class.getName() + "$Prediction");
        for (int state = 0; state < 4; state++) {
            ClientPreparedTravel travel = new ClientPreparedTravel(ignored -> { });
            set(travel, "begin", state == 3 ? null : begin);
            set(travel, "sourcePreparation", constructor.newInstance(begin));
            set(travel, "sourceCapture", 16);
            if (state == 1) {
                set(travel, "adopted", true);
            } else if (state == 2) {
                set(travel, "prediction", mock(predictionType));
            }
            ClientPortalRenderer renderer = mock(ClientPortalRenderer.class);
            try (MockedStatic<ClientPortalRenderer> renderers = mockStatic(ClientPortalRenderer.class)) {
                renderers.when(ClientPortalRenderer::instance).thenReturn(renderer);
                travel.discardSourcePreparation();
                assertNull(get(travel, "sourcePreparation"));
                assertEquals(state == 0 ? 0 : 49, get(travel, "sourceCapture"));
                verify(renderer).retireTravelSource();
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void matchingReturnConsumesSourceSnapshotsButRequiresFreshNativeBarrier() throws ReflectiveOperationException {
        Method fixture = ClientPreparedTravelPendingTest.class.getDeclaredMethod("begin", long.class);
        fixture.setAccessible(true);
        ClientViewMessage.TravelBegin old = (ClientViewMessage.TravelBegin) fixture.invoke(null, 12L);
        ClientViewMessage.TravelBegin next = (ClientViewMessage.TravelBegin) fixture.invoke(null, 13L);
        ClientPreparedTravel travel = new ClientPreparedTravel(ignored -> { });
        Class<?> sourceType = Class.forName(ClientPreparedTravel.class.getName() + "$SourcePreparation");
        Constructor<?> constructor = sourceType.getDeclaredConstructor(ClientViewMessage.TravelBegin.class);
        constructor.setAccessible(true);
        Object source = constructor.newInstance(old);
        ClientLevel level = mock(ClientLevel.class);
        ClientTravelScene scene = mock(ClientTravelScene.class);
        set(source, "level", level);
        set(source, "scene", scene);
        byte[] payload = {1, 2, 3};
        ClientViewMessage.TravelCoordinate coordinate = old.chunks().getFirst();
        ((Map<ClientViewMessage.TravelCoordinate, byte[]>) get(source, "payloads")).put(coordinate, payload);
        ((Map<ClientViewMessage.TravelCoordinate, Integer>) get(source, "decoded")).put(coordinate, 7);
        set(travel, "sourcePreparation", source);
        Method prepare = ClientPreparedTravel.class.getDeclaredMethod("preparation", ClientViewMessage.TravelBegin.class);
        prepare.setAccessible(true);
        ClientPortalRenderer renderer = mock(ClientPortalRenderer.class);
        try (MockedStatic<ClientPortalRenderer> renderers = mockStatic(ClientPortalRenderer.class)) {
            renderers.when(ClientPortalRenderer::instance).thenReturn(renderer);
            Object pending = prepare.invoke(travel, next);
            assertSame(level, get(pending, "level"));
            assertSame(scene, get(pending, "scene"));
            assertSame(payload, ((Map<?, ?>) get(pending, "payloads")).get(coordinate));
            assertEquals(0, ((Map<?, ?>) get(pending, "decoded")).get(coordinate));
            assertNull(get(travel, "sourcePreparation"));
            verify(scene).rebind(next);
            verify(renderer).retireTravelSource();
            ClientTravelChunks chunks = (ClientTravelChunks) get(pending, "chunks");
            assertEquals(0L, chunks.completeRevision());
            assertTrue(chunks.reuse(new ClientViewMessage.TravelReuse(next.token(), next.generation(), coordinate.x(), coordinate.z(),
                1, ClientTravelHash.of(payload)), payload));
            assertEquals(0L, chunks.completeRevision());
            chunks.end(new ClientViewMessage.TravelEnd(next.token(), next.generation(), 1,
                List.of(new ClientViewMessage.TravelChunkRevision(coordinate.x(), coordinate.z(), 1))));
            assertEquals(1L, chunks.completeRevision());
            assertEquals(0L, travel.readyRevision());
        }
    }

    @Test
    public void mismatchedOrExpiredSourceSnapshotsAreRetiredWithoutTransfer() throws ReflectiveOperationException {
        Method fixture = ClientPreparedTravelPendingTest.class.getDeclaredMethod("begin", long.class);
        fixture.setAccessible(true);
        ClientViewMessage.TravelBegin old = (ClientViewMessage.TravelBegin) fixture.invoke(null, 12L);
        Class<?> sourceType = Class.forName(ClientPreparedTravel.class.getName() + "$SourcePreparation");
        Constructor<?> constructor = sourceType.getDeclaredConstructor(ClientViewMessage.TravelBegin.class);
        constructor.setAccessible(true);
        Method prepare = ClientPreparedTravel.class.getDeclaredMethod("preparation", ClientViewMessage.TravelBegin.class);
        prepare.setAccessible(true);
        for (int mismatch = 0; mismatch < 3; mismatch++) {
            ClientPreparedTravel travel = new ClientPreparedTravel(ignored -> { });
            Object source = constructor.newInstance(old);
            ClientTravelScene scene = mock(ClientTravelScene.class);
            set(source, "level", mock(ClientLevel.class));
            set(source, "scene", scene);
            @SuppressWarnings("unchecked")
            Map<ClientViewMessage.TravelCoordinate, Integer> decoded = (Map<ClientViewMessage.TravelCoordinate, Integer>) get(source, "decoded");
            decoded.put(old.chunks().getFirst(), 0);
            ClientViewMessage.TravelBegin next = mock(ClientViewMessage.TravelBegin.class);
            when(next.token()).thenReturn(old.token());
            when(next.generation()).thenReturn(old.generation() + 1);
            when(next.expiresMillis()).thenReturn(30_000);
            when(next.world()).thenReturn(mismatch == 0
                ? new ClientViewMessage.TravelWorld("minecraft:overworld", "minecraft:overworld", 8, false, false, 63, -64, 384)
                : old.world());
            when(next.chunks()).thenReturn(mismatch == 1 ? List.of(new ClientViewMessage.TravelCoordinate(1, 0)) : old.chunks());
            if (mismatch == 2) {
                set(source, "deadline", 1L);
            }
            set(travel, "sourcePreparation", source);
            ClientPortalRenderer renderer = mock(ClientPortalRenderer.class);
            try (MockedStatic<ClientPortalRenderer> renderers = mockStatic(ClientPortalRenderer.class)) {
                renderers.when(ClientPortalRenderer::instance).thenReturn(renderer);
                Object pending = prepare.invoke(travel, next);
                assertNull(get(pending, "level"));
                assertNull(get(pending, "scene"));
                assertTrue(((Map<?, ?>) get(pending, "decoded")).isEmpty());
                assertNull(get(travel, "sourcePreparation"));
                verify(scene, never()).rebind(next);
                verify(renderer).retireTravelSource();
            }
        }
    }

    @Test
    public void unloadedSourceColumnIsRetriedAfterOtherPositionsAreCaptured() throws ReflectiveOperationException {
        Class<?> sourceType = Class.forName(ClientPreparedTravel.class.getName() + "$SourcePreparation");
        Constructor<?> sourceConstructor = sourceType.getDeclaredConstructor(ClientViewMessage.TravelBegin.class);
        sourceConstructor.setAccessible(true);
        Object source = sourceConstructor.newInstance(mock(ClientViewMessage.TravelBegin.class));
        Method next = sourceType.getDeclaredMethod("nextCapture");
        next.setAccessible(true);
        Class<?> columnType = Class.forName(ClientPreparedTravel.class.getName() + "$Column");
        Constructor<?> columnConstructor = columnType.getDeclaredConstructor(int.class, int.class, int.class, byte[].class);
        columnConstructor.setAccessible(true);
        Method capture = sourceType.getDeclaredMethod("capture", int.class, columnType);
        capture.setAccessible(true);
        assertEquals(0, ((Integer) next.invoke(source)).intValue());
        for (int index = 1; index < 49; index++) {
            assertEquals(index, ((Integer) next.invoke(source)).intValue());
            capture.invoke(source, index, columnConstructor.newInstance(index % 7, index / 7, 0, new byte[]{1}));
        }
        assertEquals(0, ((Integer) next.invoke(source)).intValue());
        capture.invoke(source, 0, columnConstructor.newInstance(0, 0, 0, new byte[]{1}));
        assertEquals(-1, ((Integer) next.invoke(source)).intValue());
    }

    @Test
    public void sourcePayloadBoundsRejectBeforeAddingColumnsAndAcceptExactTotalLimit() throws ReflectiveOperationException {
        Class<?> sourceType = Class.forName(ClientPreparedTravel.class.getName() + "$SourcePreparation");
        Constructor<?> sourceConstructor = sourceType.getDeclaredConstructor(ClientViewMessage.TravelBegin.class);
        sourceConstructor.setAccessible(true);
        Object source = sourceConstructor.newInstance(mock(ClientViewMessage.TravelBegin.class));
        Class<?> columnType = Class.forName(ClientPreparedTravel.class.getName() + "$Column");
        Constructor<?> columnConstructor = columnType.getDeclaredConstructor(int.class, int.class, int.class, byte[].class);
        columnConstructor.setAccessible(true);
        Method capture = sourceType.getDeclaredMethod("capture", int.class, columnType);
        capture.setAccessible(true);
        Object oversized = columnConstructor.newInstance(0, 0, 0, new byte[ClientViewProtocol.MAX_TRAVEL_CHUNK_BYTES + 1]);
        assertTrue(assertThrows(InvocationTargetException.class,
            () -> capture.invoke(source, 0, oversized)).getCause() instanceof IllegalArgumentException);
        assertEquals(0, ((Collection<?>) get(source, "columns")).size());
        assertEquals(0, get(source, "bytes"));
        set(source, "bytes", ClientViewProtocol.MAX_TRAVEL_BYTES - 1);
        Object column = columnConstructor.newInstance(0, 0, 0, new byte[]{1});
        capture.invoke(source, 0, column);
        assertEquals(ClientViewProtocol.MAX_TRAVEL_BYTES, get(source, "bytes"));
        assertTrue(assertThrows(InvocationTargetException.class,
            () -> capture.invoke(source, 1, column)).getCause() instanceof IllegalArgumentException);
        assertEquals(1, ((Collection<?>) get(source, "columns")).size());
    }

    @Test
    public void sourcePreparationUsesExactNativeWorldMetadataAndOnlyItsRouteNeighborhood() throws ReflectiveOperationException {
        Method fixture = ClientPreparedTravelPendingTest.class.getDeclaredMethod("begin", long.class);
        fixture.setAccessible(true);
        ClientViewMessage.TravelBegin destination = (ClientViewMessage.TravelBegin) fixture.invoke(null, 12L);
        ClientPreparedTravel travel = new ClientPreparedTravel(ignored -> { });
        set(travel, "begin", destination);
        ClientLevel level = mock(ClientLevel.class, withSettings().extraInterfaces(ClientTravelWorld.class));
        ClientViewMessage.TravelWorld world = new ClientViewMessage.TravelWorld("minecraft:the_nether", "minecraft:the_nether",
            93217562L, true, true, 72, 0, 256);
        when(((ClientTravelWorld) level).wormholes$travelWorld()).thenReturn(world);
        LocalPlayer player = mock(LocalPlayer.class);
        when(player.getX()).thenReturn(12.25);
        when(player.getY()).thenReturn(85.5);
        when(player.getZ()).thenReturn(-3.4);
        when(player.getEyePosition()).thenReturn(new Vec3(12.25, 87.12, -3.4));
        GeometryVector eye = new GeometryVector(12.25, 87.12, -3.4);
        ClientViewEnvironment template = PortalEnvironmentTest.environment(ClientViewEnvironment.Transform.IDENTITY);
        ClientViewEnvironment environment = new ClientViewEnvironment(template.gameTime(), template.sky(), template.fog(),
            template.lighting(), template.clouds(), template.transform(),
            new ClientViewEnvironment.Dimension(world.minY(), world.height(), false,
                ClientViewEnvironment.CardinalLighting.DEFAULT, 0.0, false),
            new ClientViewEnvironment.World(world.dimension(), 6000, "minecraft:nether_wastes", world.seaLevel(),
                7, 0, 128, true, 0.1F, ClientViewEnvironment.EyeMedium.NONE, false));
        Method capture = ClientPreparedTravel.class.getDeclaredMethod("sourceBegin", ClientLevel.class, LocalPlayer.class);
        capture.setAccessible(true);
        try (MockedStatic<MinecraftPortalEnvironment> environments = mockStatic(MinecraftPortalEnvironment.class)) {
            environments.when(() -> MinecraftPortalEnvironment.capture(level, eye, ClientViewEnvironment.Transform.IDENTITY, true))
                .thenReturn(environment);
            ClientViewMessage.TravelBegin source = (ClientViewMessage.TravelBegin) capture.invoke(travel, level, player);
            assertSame(world, source.world());
            assertSame(environment, source.environment());
            assertEquals(destination.token(), source.token());
            assertEquals(destination.sourcePortal(), source.sourcePortal());
            assertEquals(49, source.chunks().size());
            assertTrue(source.chunks().contains(new ClientViewMessage.TravelCoordinate(-3, -3)));
            assertTrue(source.chunks().contains(new ClientViewMessage.TravelCoordinate(3, 3)));
            assertEquals(85.5, source.arrival().y(), 0.0);
            assertEquals(ClientViewEnvironment.Transform.IDENTITY, source.destinationToSource());
            environments.verify(() -> MinecraftPortalEnvironment.capture(level, eye, ClientViewEnvironment.Transform.IDENTITY, true));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void alreadyVerifiedColumnsCannotStarveRemainingManifestAcrossFrames() throws ReflectiveOperationException {
        Method fixture = ClientPreparedTravelPendingTest.class.getDeclaredMethod("begin", long.class);
        fixture.setAccessible(true);
        ClientViewMessage.TravelBegin original = (ClientViewMessage.TravelBegin) fixture.invoke(null, 9L);
        List<ClientViewMessage.TravelCoordinate> manifest = new ArrayList<>(49);
        for (int z = 0; z < 7; z++) {
            for (int x = 0; x < 7; x++) {
                manifest.add(new ClientViewMessage.TravelCoordinate(x, z));
            }
        }
        ClientViewMessage.TravelBegin begin = new ClientViewMessage.TravelBegin(original.token(), original.generation(),
            original.sourcePortal(), original.sourceWorld(), original.sourceGeometry(), original.destinationToSource(),
            original.world(), original.arrival(), manifest, original.environment(), original.expiresMillis());
        ClientPreparedTravel travel = new ClientPreparedTravel(ignored -> { });
        Class<?> pendingType = Class.forName(ClientPreparedTravel.class.getName() + "$PendingPreparation");
        Constructor<?> pendingConstructor = pendingType.getDeclaredConstructor(ClientViewMessage.TravelBegin.class);
        pendingConstructor.setAccessible(true);
        Object pending = pendingConstructor.newInstance(begin);
        set(pending, "level", mock(ClientLevel.class));
        set(travel, "pendingPreparation", pending);
        Map<ClientViewMessage.TravelCoordinate, Object> columns = (Map<ClientViewMessage.TravelCoordinate, Object>) get(pending, "columns");
        Map<ClientViewMessage.TravelCoordinate, Integer> decoded = (Map<ClientViewMessage.TravelCoordinate, Integer>) get(pending, "decoded");
        Map<ClientViewMessage.TravelCoordinate, byte[]> payloads = (Map<ClientViewMessage.TravelCoordinate, byte[]>) get(pending, "payloads");
        Class<?> columnType = Class.forName(ClientPreparedTravel.class.getName() + "$Column");
        Constructor<?> columnConstructor = columnType.getDeclaredConstructor(int.class, int.class, int.class, byte[].class);
        columnConstructor.setAccessible(true);
        for (ClientViewMessage.TravelCoordinate coordinate : manifest) {
            byte[] data = {(byte) coordinate.x(), (byte) coordinate.z()};
            payloads.put(coordinate, data);
            decoded.put(coordinate, 0);
            columns.put(coordinate, columnConstructor.newInstance(coordinate.x(), coordinate.z(), 1, data));
        }
        int visited = 0;
        for (ClientViewMessage.TravelCoordinate coordinate : columns.keySet()) {
            if (visited++ < 16) {
                decoded.put(coordinate, 1);
            }
        }
        Method advance = ClientPreparedTravel.class.getDeclaredMethod("advancePreparation");
        advance.setAccessible(true);
        for (int frame = 0; frame < 33; frame++) {
            advance.invoke(travel);
        }
        assertSame(pending, get(travel, "pendingPreparation"));
        for (ClientViewMessage.TravelCoordinate coordinate : manifest) {
            assertEquals(Integer.valueOf(1), decoded.get(coordinate));
        }
        assertEquals(0L, ((ClientTravelChunks) get(pending, "chunks")).completeRevision());
        assertEquals(0L, travel.readyRevision());
    }

    @Test
    public void cachedWarmColumnsRemainUnacknowledgedUntilCurrentProofAndEnd() throws ReflectiveOperationException {
        Method fixture = ClientPreparedTravelPendingTest.class.getDeclaredMethod("begin", long.class);
        fixture.setAccessible(true);
        ClientViewMessage.TravelBegin begin = (ClientViewMessage.TravelBegin) fixture.invoke(null, 8L);
        ClientPreparedTravel travel = new ClientPreparedTravel(ignored -> { });
        Field cacheField = ClientPreparedTravel.class.getDeclaredField("cache");
        cacheField.setAccessible(true);
        ClientTravelCache cache = (ClientTravelCache) cacheField.get(travel);
        byte[] bytes = {1, 2, 3};
        cache.put(begin.world().dimension(), 0, 0, bytes);
        Method warm = ClientPreparedTravel.class.getDeclaredMethod("cachedColumns", ClientViewMessage.TravelBegin.class);
        warm.setAccessible(true);
        List<?> columns = (List<?>) warm.invoke(travel, begin);
        assertEquals(1, columns.size());
        Method revision = columns.getFirst().getClass().getDeclaredMethod("revision");
        revision.setAccessible(true);
        assertEquals(0, revision.invoke(columns.getFirst()));
        assertEquals(0L, travel.readyRevision());
        ClientTravelChunks chunks = new ClientTravelChunks(begin);
        assertEquals(0L, chunks.completeRevision());
        ClientViewMessage.TravelReuse proof = new ClientViewMessage.TravelReuse(begin.token(), begin.generation(), 0, 0, 1,
            ClientTravelHash.of(bytes));
        assertTrue(chunks.reuse(proof, cache.get(begin.world().dimension(), 0, 0, proof.hash())));
        assertEquals(0L, chunks.completeRevision());
        chunks.end(new ClientViewMessage.TravelEnd(begin.token(), begin.generation(), 1L,
            List.of(new ClientViewMessage.TravelChunkRevision(0, 0, 1))));
        assertEquals(1L, chunks.completeRevision());
        assertEquals(0L, travel.readyRevision());
    }

    @Test
    public void matchingServerProofAdvancesRevisionWithoutApplyingNativeDataAgain() throws ReflectiveOperationException {
        ClientViewMessage.TravelCoordinate coordinate = new ClientViewMessage.TravelCoordinate(0, 0);
        Map<ClientViewMessage.TravelCoordinate, Integer> decoded = new HashMap<>();
        Map<ClientViewMessage.TravelCoordinate, byte[]> payloads = new HashMap<>();
        byte[] bytes = {1, 2, 3};
        decoded.put(coordinate, 0);
        payloads.put(coordinate, bytes);
        LongOpenHashSet changed = new LongOpenHashSet();
        Class<?> columnType = Class.forName(ClientPreparedTravel.class.getName() + "$Column");
        Constructor<?> constructor = columnType.getDeclaredConstructor(int.class, int.class, int.class, byte[].class);
        constructor.setAccessible(true);
        Object column = constructor.newInstance(0, 0, 7, bytes.clone());
        Method decode = ClientPreparedTravel.class.getDeclaredMethod("decodeChanged", ClientLevel.class, ClientTravelScene.class,
            Map.class, LongOpenHashSet.class, Map.class, columnType);
        decode.setAccessible(true);
        decode.invoke(null, null, null, decoded, changed, payloads, column);
        assertEquals(Integer.valueOf(7), decoded.get(coordinate));
        assertSame(bytes, payloads.get(coordinate));
        assertTrue(changed.isEmpty());
    }

    private static Object get(Object object, String name) throws ReflectiveOperationException {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static void set(Object object, String name, Object value) throws ReflectiveOperationException {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }
}
