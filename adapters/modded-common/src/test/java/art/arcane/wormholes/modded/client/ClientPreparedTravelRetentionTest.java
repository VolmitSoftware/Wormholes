package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.MinecraftChunkPacketEncoding;
import art.arcane.wormholes.modded.client.render.ClientPortalRenderer;
import art.arcane.wormholes.modded.client.render.ClientSodiumTerrain;
import art.arcane.wormholes.modded.client.render.ClientTravelScene;
import art.arcane.wormholes.modded.client.render.PortalIrisMainPipelines;
import art.arcane.wormholes.modded.mixin.client.PreparedLevelAccess;
import art.arcane.wormholes.modded.mixin.client.PreparedLevelDataAccess;
import art.arcane.optics.stream.ViewStreamCapability;
import art.arcane.wormholes.network.client.ClientTravelWindow;
import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.math.Face;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.chunk.LevelChunk;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.MockedConstruction;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.BitSet;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.AtomicReference;

import static art.arcane.wormholes.modded.client.ClientTravelTestFixtures.field;
import static art.arcane.wormholes.modded.client.ClientTravelTestFixtures.set;
import static net.minecraft.world.level.chunk.status.ChunkStatus.FULL;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.withSettings;
import art.arcane.wormholes.network.client.TravelMessage;

public class ClientPreparedTravelRetentionTest extends MinecraftTestBase {
    @Test
    public void shiftedReturnKeepsNativeCacheHaloButOnlyTrustsNewManifestProof() throws ReflectiveOperationException {
        TravelMessage.TravelBegin original = begin();
        TravelMessage.TravelCoordinate kept = original.chunks().getFirst();
        TravelMessage.TravelCoordinate added = new TravelMessage.TravelCoordinate(1, 0);
        TravelMessage.TravelBegin shifted = new TravelMessage.TravelBegin(original.token(), original.generation() + 1,
            original.sourcePortal(), original.sourceWorld(), original.sourceGeometry(), original.destinationToSource(), original.world(),
            new TravelMessage.TravelPose(16, original.arrival().y(), 0, 0, 0), List.of(kept, added), original.environment(), original.expiresMillis(), TravelMessage.ArrivalRules.FRAME, false, 0, false);
        ClientPreparedTravel travel = ClientTravelTestFixtures.travel(ignored -> { });
        ClientPacketListener connection = mock(ClientPacketListener.class);
        when(connection.registryAccess()).thenReturn(RegistryAccess.EMPTY);
        ClientLevel level = mock(ClientLevel.class, withSettings().extraInterfaces(PreparedLevelAccess.class));
        when(((PreparedLevelAccess) level).wormholes$lightUpdates()).thenReturn(new ArrayDeque<>());
        when(level.registryAccess()).thenReturn(RegistryAccess.EMPTY);
        ClientChunkCache cache = mock(ClientChunkCache.class, withSettings().extraInterfaces(PreparedChunkColumns.class));
        when(level.getChunkSource()).thenReturn(cache);
        LevelChunk keptChunk = mock(LevelChunk.class);
        LevelChunk staleChunk = mock(LevelChunk.class);
        LevelChunk haloChunk = mock(LevelChunk.class);
        when(keptChunk.getPos()).thenReturn(new ChunkPos(kept.x(), kept.z()));
        when(staleChunk.getPos()).thenReturn(new ChunkPos(4, 5));
        when(haloChunk.getPos()).thenReturn(new ChunkPos(2, 0));
        AtomicReferenceArray<LevelChunk> columns = new AtomicReferenceArray<>(new LevelChunk[]{keptChunk, staleChunk, haloChunk});
        when(((PreparedChunkColumns) cache).wormholes$columns()).thenReturn(columns);
        when(((PreparedChunkColumns) cache).wormholes$radius()).thenReturn(3);
        Entity staleEntity = mock(Entity.class);
        when(staleEntity.getId()).thenReturn(72);
        when(level.entitiesForRendering()).thenReturn(List.of(staleEntity));
        Object source = source(original);
        set(source, "connection", connection);
        set(source, "registry", RegistryAccess.EMPTY);
        set(source, "level", level);
        ClientTravelScene oldScene = mock(ClientTravelScene.class);
        set(source, "scene", oldScene);
        byte[] bytes = {1, 2};
        map(source, "payloads").put(kept, bytes);
        map(source, "decoded").put(kept, 7);
        set(travel, "sourcePreparation", source);
        Minecraft minecraft = mock(Minecraft.class);
        minecraft.level = mock(ClientLevel.class);
        when(minecraft.getConnection()).thenReturn(connection);
        try (MockedStatic<Minecraft> access = mockStatic(Minecraft.class);
             MockedStatic<ClientPortalRenderer> renderer = mockStatic(ClientPortalRenderer.class)) {
            access.when(Minecraft::getInstance).thenReturn(minecraft);
            renderer.when(ClientPortalRenderer::instance).thenReturn(mock(ClientPortalRenderer.class));
            Object pending = invoke(travel, "preparation", new Class<?>[]{TravelMessage.TravelBegin.class}, shifted);
            assertSame(level, field(pending, "level"));
            assertNull(field(pending, "scene"));
            assertSame(bytes, map(pending, "payloads").get(kept));
            assertEquals(Integer.valueOf(0), map(pending, "decoded").get(kept));
            assertFalse(map(pending, "decoded").containsKey(added));
            assertEquals(0L, ((ClientTravelChunks) field(pending, "chunks")).completeRevision());
            verify(cache).drop(new ChunkPos(4, 5));
            verify(cache, never()).drop(keptChunk.getPos());
            verify(cache, never()).drop(haloChunk.getPos());
            verify(cache).updateViewCenter(1, 0);
            verify(level).removeEntity(72, Entity.RemovalReason.DISCARDED);
            assertNull(field(travel, "sourcePreparation"));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void declinedRapidReturnKeepsActualWorldForAuthoritativeRespawnAndValidatedResends() throws ReflectiveOperationException {
        ClientPreparedTravel travel = ClientTravelTestFixtures.travel(ignored -> { });
        TravelMessage.TravelBegin begin = begin();
        ClientLevel retained = mock(ClientLevel.class, withSettings().extraInterfaces(PreparedLevelAccess.class));
        when(retained.registryAccess()).thenReturn(RegistryAccess.EMPTY);
        when(retained.dimension()).thenReturn(Level.OVERWORLD);
        when(retained.entitiesForRendering()).thenReturn(List.of());
        when(((PreparedLevelAccess) retained).wormholes$lightUpdates()).thenReturn(new ArrayDeque<>());
        ClientChunkCache cache = mock(ClientChunkCache.class, withSettings().extraInterfaces(PreparedChunkColumns.class));
        when(retained.getChunkSource()).thenReturn(cache);
        when(cache.getChunk(0, 0, FULL, false)).thenReturn(mock(LevelChunk.class));
        ClientPacketListener connection = mock(ClientPacketListener.class);
        when(connection.registryAccess()).thenReturn(RegistryAccess.EMPTY);
        Object source = source(begin);
        set(source, "level", retained);
        set(source, "connection", connection);
        set(source, "registry", RegistryAccess.EMPTY);
        ClientboundLevelChunkWithLightPacket packet = packet();
        byte[] installed = MinecraftChunkPacketEncoding.encode(RegistryAccess.EMPTY, packet);
        map(source, "payloads").put(begin.chunks().getFirst(), installed);
        set(travel, "sourcePreparation", source);
        set(travel, "staged", retained);
        set(travel, "begin", begin);
        set(travel, "chunks", new ClientTravelChunks(begin));
        Minecraft minecraft = mock(Minecraft.class);
        minecraft.level = mock(ClientLevel.class, withSettings().extraInterfaces(PreparedLevelAccess.class));
        when(minecraft.getConnection()).thenReturn(connection);
        ClientLevel.ClientLevelData data = mock(ClientLevel.ClientLevelData.class, withSettings().extraInterfaces(PreparedLevelDataAccess.class));
        DimensionType dimension = mock(DimensionType.class);
        when(dimension.minY()).thenReturn(begin.world().minY());
        when(dimension.height()).thenReturn(begin.world().height());
        Holder.Reference<DimensionType> type = mock(Holder.Reference.class);
        when(type.value()).thenReturn(dimension);
        when(type.unwrapKey()).thenReturn(Optional.of(ResourceKey.create(Registries.DIMENSION_TYPE,
            Identifier.parse(begin.world().dimensionType()))));
        LevelExtractor extractor = mock(LevelExtractor.class);
        ClientPreparedTravel.Construction construction = new ClientPreparedTravel.Construction(data, Level.OVERWORLD, type, extractor,
            begin.world().debug(), begin.world().seed(), begin.world().seaLevel(), 10, 8);
        WormholesClient client = mock(WormholesClient.class);
        ClientViewSession session = mock(ClientViewSession.class);
        when(client.session()).thenReturn(session);
        when(session.active()).thenReturn(true);
        when(session.has(ViewStreamCapability.PREPARED_TRAVEL_CACHE)).thenReturn(true);
        AtomicReference<byte[]> physical = new AtomicReference<>(installed);
        ClientSodiumTerrain.Handoff nativeScope = mock(ClientSodiumTerrain.Handoff.class);
        PortalIrisMainPipelines.Handoff shaderScope = mock(PortalIrisMainPipelines.Handoff.class);
        try (MockedStatic<Minecraft> access = mockStatic(Minecraft.class);
             MockedStatic<WormholesClient> clients = mockStatic(WormholesClient.class);
             MockedStatic<ClientPortalRenderer> renderer = mockStatic(ClientPortalRenderer.class);
             MockedStatic<ClientSodiumTerrain> terrain = mockStatic(ClientSodiumTerrain.class);
             MockedStatic<PortalIrisMainPipelines> iris = mockStatic(PortalIrisMainPipelines.class);
             MockedStatic<MinecraftChunkPacketEncoding> encoding = mockStatic(MinecraftChunkPacketEncoding.class);
             MockedConstruction<ClientboundLevelChunkWithLightPacket> packets = mockConstruction(ClientboundLevelChunkWithLightPacket.class);
             MockedConstruction<PreparedLevelExtractor> extractors = mockConstruction(PreparedLevelExtractor.class)) {
            access.when(Minecraft::getInstance).thenReturn(minecraft);
            clients.when(WormholesClient::instance).thenReturn(client);
            renderer.when(ClientPortalRenderer::instance).thenReturn(mock(ClientPortalRenderer.class));
            terrain.when(() -> ClientSodiumTerrain.authoritativeHandoff(retained)).thenReturn(nativeScope);
            iris.when(() -> PortalIrisMainPipelines.authoritativeHandoff(retained)).thenReturn(shaderScope);
            encoding.when(() -> MinecraftChunkPacketEncoding.encode(eq(RegistryAccess.EMPTY), any()))
                .thenAnswer(call -> call.getArgument(1) == packet ? installed : physical.get());
            invoke(travel, "declinePreparation", new Class<?>[0]);
            assertNull(field(travel, "begin"));
            assertNull(field(travel, "staged"));
            assertNull(field(travel, "sourcePreparation"));
            assertEquals(1, map(travel, "retainedWorlds").size());
            terrain.verify(() -> ClientSodiumTerrain.forget(retained), never());
            assertEquals(0L, travel.readyRevision());
            ClientPreparedTravel.Construction changedSeed = new ClientPreparedTravel.Construction(data, Level.OVERWORLD, type, extractor,
                begin.world().debug(), begin.world().seed() + 1, begin.world().seaLevel(), 10, 8);
            assertNull(travel.adopt(changedSeed));
            when(minecraft.getConnection()).thenReturn(mock(ClientPacketListener.class));
            assertNull(travel.adopt(construction));
            when(minecraft.getConnection()).thenReturn(connection);
            assertSame(retained, travel.adopt(construction));
            assertEquals(0L, travel.readyRevision());
            assertNull(field(travel, "begin"));
            assertNull(field(travel, "staged"));
            terrain.verify(() -> ClientSodiumTerrain.forget(retained), never());
            verify(cache).updateViewRadius(10);
            verify(retained).setServerSimulationDistance(8);
            verify((PreparedLevelAccess) retained).wormholes$extractor(extractor);
            verify((PreparedLevelAccess) retained).wormholes$data(data);
            assertTrue(travel.attachRespawnLevel(retained));
            verify((PreparedChunkColumns) cache).wormholes$announceColumns();
            terrain.verify(() -> ClientSodiumTerrain.authoritativeHandoff(retained));
            terrain.verify(() -> ClientSodiumTerrain.handoff(retained), never());
            assertTrue(travel.receiveNativeChunk(retained, packet));
            travel.sectionChanged(retained, 0, 4, 0);
            physical.set(new byte[]{9});
            assertFalse(travel.receiveNativeChunk(retained, packet));
            travel.sectionChanged(retained, 0, 4, 0);
            physical.set(installed);
            assertTrue(travel.receiveNativeChunk(retained, packet));
            assertEquals(2, packets.constructed().size());
        }
    }

    @Test
    public void nextPreparationReusesActualWorldAfterRapidReturnProofWasDeclined() throws ReflectiveOperationException {
        ClientPreparedTravel travel = ClientTravelTestFixtures.travel(ignored -> { });
        TravelMessage.TravelBegin begin = begin();
        ClientLevel retained = mock(ClientLevel.class, withSettings().extraInterfaces(PreparedLevelAccess.class));
        when(retained.registryAccess()).thenReturn(RegistryAccess.EMPTY);
        when(retained.entitiesForRendering()).thenReturn(List.of());
        when(((PreparedLevelAccess) retained).wormholes$lightUpdates()).thenReturn(new ArrayDeque<>());
        ClientChunkCache cache = mock(ClientChunkCache.class, withSettings().extraInterfaces(PreparedChunkColumns.class));
        when(retained.getChunkSource()).thenReturn(cache);
        when(((PreparedChunkColumns) cache).wormholes$columns()).thenReturn(new AtomicReferenceArray<>(0));
        when(((PreparedChunkColumns) cache).wormholes$radius()).thenReturn(3);
        ClientPacketListener connection = mock(ClientPacketListener.class);
        when(connection.registryAccess()).thenReturn(RegistryAccess.EMPTY);
        Object source = source(begin);
        set(source, "connection", connection);
        set(source, "registry", RegistryAccess.EMPTY);
        set(source, "level", retained);
        byte[] installed = {1, 2};
        map(source, "payloads").put(begin.chunks().getFirst(), installed);
        set(travel, "sourcePreparation", source);
        set(travel, "begin", begin);
        Minecraft minecraft = mock(Minecraft.class);
        minecraft.level = mock(ClientLevel.class);
        when(minecraft.getConnection()).thenReturn(connection);
        try (MockedStatic<Minecraft> access = mockStatic(Minecraft.class);
             MockedStatic<ClientPortalRenderer> renderer = mockStatic(ClientPortalRenderer.class);
             MockedStatic<ClientSodiumTerrain> terrain = mockStatic(ClientSodiumTerrain.class);
             MockedStatic<PortalIrisMainPipelines> iris = mockStatic(PortalIrisMainPipelines.class)) {
            access.when(Minecraft::getInstance).thenReturn(minecraft);
            renderer.when(ClientPortalRenderer::instance).thenReturn(mock(ClientPortalRenderer.class));
            invoke(travel, "declinePreparation", new Class<?>[0]);
            assertNull(field(travel, "sourcePreparation"));
            Object pending = invoke(travel, "preparation", new Class<?>[]{TravelMessage.TravelBegin.class}, begin);
            assertSame(retained, field(pending, "level"));
            assertSame(installed, map(pending, "payloads").get(begin.chunks().getFirst()));
            assertEquals(Integer.valueOf(0), map(pending, "decoded").get(begin.chunks().getFirst()));
            assertEquals(0L, ((ClientTravelChunks) field(pending, "chunks")).completeRevision());
            assertEquals(0L, travel.readyRevision());
            terrain.verify(() -> ClientSodiumTerrain.forget(retained), never());
            when(connection.registryAccess()).thenReturn(mock(RegistryAccess.Frozen.class));
            Object changedRegistry = invoke(travel, "preparation", new Class<?>[]{TravelMessage.TravelBegin.class}, begin);
            assertNull(field(changedRegistry, "level"));
            when(connection.registryAccess()).thenReturn(RegistryAccess.EMPTY);
            minecraft.level = retained;
            Object active = invoke(travel, "preparation", new Class<?>[]{TravelMessage.TravelBegin.class}, begin);
            assertNull(field(active, "level"));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void retainedManagedApertureRequiresExactWorldAuthorityAndRevokesOnDrop() throws ReflectiveOperationException {
        for (int kind : List.of(ApertureDescriptor.KIND_FRAME, ApertureDescriptor.KIND_VANILLA_REPLACEMENT)) {
            ClientPreparedTravel travel = ClientTravelTestFixtures.travel(ignored -> { });
            TravelMessage.TravelBegin original = begin();
            ApertureDescriptor aperture = new ApertureDescriptor(0, 0, 0, Face.N.ordinal(), true, 0, false, 2, 3,
                new long[]{1}, 0, 0, 1, 64, 0, 0, 0, 0, 0, 0, kind, 0.0D, 0, 11, List.of());
            TravelMessage.TravelBegin begin = new TravelMessage.TravelBegin(original.token(), original.generation(),
                original.sourcePortal(), original.sourceWorld(), aperture, original.destinationToSource(), original.world(),
                original.arrival(), original.chunks(), original.environment(), original.expiresMillis(), TravelMessage.ArrivalRules.FRAME, false, 0, false);
            ClientLevel level = mock(ClientLevel.class);
            when(level.registryAccess()).thenReturn(RegistryAccess.EMPTY);
            ClientPacketListener connection = mock(ClientPacketListener.class);
            when(connection.registryAccess()).thenReturn(RegistryAccess.EMPTY);
            Object source = source(begin);
            set(source, "connection", connection);
            set(source, "registry", RegistryAccess.EMPTY);
            set(source, "level", level);
            set(travel, "sourcePreparation", source);
            Minecraft minecraft = mock(Minecraft.class);
            minecraft.level = level;
            when(minecraft.getConnection()).thenReturn(connection);
            try (MockedStatic<Minecraft> access = mockStatic(Minecraft.class)) {
                access.when(Minecraft::getInstance).thenReturn(minecraft);
                invoke(travel, "retainActualWorlds", new Class<?>[0]);
                BlockPos open = BlockPos.ZERO;
                boolean managed = kind == ApertureDescriptor.KIND_VANILLA_REPLACEMENT;
                assertEquals(managed, travel.managesVanillaPortal(level, open));
                assertFalse(travel.managesVanillaPortal(level, new BlockPos(1, 0, 0)));
                assertFalse(travel.managesVanillaPortal(level, new BlockPos(0, 0, 1)));
                assertFalse(travel.managesVanillaPortal(mock(ClientLevel.class), open));
                when(minecraft.getConnection()).thenReturn(mock(ClientPacketListener.class));
                assertFalse(travel.managesVanillaPortal(level, open));
                when(minecraft.getConnection()).thenReturn(connection);
                minecraft.level = mock(ClientLevel.class);
                assertFalse(travel.managesVanillaPortal(level, open));
                minecraft.level = level;
                travel.discardManagedVanillaPortal(level, ClientTravelTestFixtures.geometry());
                assertEquals(managed, travel.managesVanillaPortal(level, open));
                ApertureDescriptor reverseSide = new ApertureDescriptor(0, 0, 0, Face.N.ordinal(), false, 0, false, 2, 3,
                    new long[]{1}, 0, 0, 1, 64, 0, 0, 0, 0, 0, 0, kind, 0.0D, 0, 11, List.of());
                travel.discardManagedVanillaPortal(level, reverseSide);
                invoke(travel, "retainActualWorlds", new Class<?>[0]);
                assertFalse(travel.managesVanillaPortal(level, open));
                Class<?> type = Class.forName(ClientPreparedTravel.class.getName() + "$RetainedWorld");
                Constructor<?> row = type.getDeclaredConstructor(ClientLevel.class, ClientPacketListener.class, Object.class,
                    TravelMessage.TravelWorld.class, long.class, Map.class, ApertureDescriptor.class);
                row.setAccessible(true);
                Map<Object, Object> retained = (Map<Object, Object>) field(travel, "retainedWorlds");
                retained.put(level, row.newInstance(level, connection, RegistryAccess.EMPTY, begin.world(), 1L, Map.of(), aperture));
                assertFalse(travel.managesVanillaPortal(level, open));
            }
        }
    }

    @Test
    public void resourceAndDisconnectResetDisposeActualWorldsAfterProofCancellation() throws ReflectiveOperationException {
        for (boolean resources : List.of(false, true)) {
            ClientPreparedTravel travel = ClientTravelTestFixtures.travel(ignored -> { });
            TravelMessage.TravelBegin begin = begin();
            ClientLevel retained = mock(ClientLevel.class);
            when(retained.registryAccess()).thenReturn(RegistryAccess.EMPTY);
            ClientPacketListener connection = mock(ClientPacketListener.class);
            when(connection.registryAccess()).thenReturn(RegistryAccess.EMPTY);
            Object source = source(begin);
            set(source, "connection", connection);
            set(source, "registry", RegistryAccess.EMPTY);
            set(source, "level", retained);
            set(travel, "sourcePreparation", source);
            set(travel, "begin", begin);
            Minecraft minecraft = mock(Minecraft.class);
            when(minecraft.getConnection()).thenReturn(connection);
            try (MockedStatic<Minecraft> access = mockStatic(Minecraft.class);
                 MockedStatic<ClientPortalRenderer> renderer = mockStatic(ClientPortalRenderer.class);
                 MockedStatic<ClientSodiumTerrain> terrain = mockStatic(ClientSodiumTerrain.class);
                 MockedStatic<PortalIrisMainPipelines> iris = mockStatic(PortalIrisMainPipelines.class)) {
                access.when(Minecraft::getInstance).thenReturn(minecraft);
                renderer.when(ClientPortalRenderer::instance).thenReturn(mock(ClientPortalRenderer.class));
                invoke(travel, "declinePreparation", new Class<?>[0]);
                assertEquals(1, map(travel, "retainedWorlds").size());
                terrain.verify(() -> ClientSodiumTerrain.forget(retained), never());
                if (resources) {
                    travel.discardSourcePreparation();
                } else {
                    travel.clear();
                }
                assertTrue(map(travel, "retainedWorlds").isEmpty());
                terrain.verify(() -> ClientSodiumTerrain.forget(retained));
            }
        }
    }

    @Test
    public void nativePendingAndReadyPreparationsSkipSnapshotsAndCoverResumesThem() throws ReflectiveOperationException {
        ClientPreparedTravel travel = ClientTravelTestFixtures.travel(ignored -> { });
        TravelMessage.TravelBegin begin = begin();
        Class<?> type = Class.forName(ClientPreparedTravel.class.getName() + "$PendingPreparation");
        Constructor<?> constructor = type.getDeclaredConstructor(TravelMessage.TravelBegin.class);
        constructor.setAccessible(true);
        Object pending = constructor.newInstance(begin);
        ClientLevel level = mock(ClientLevel.class);
        ClientTravelScene scene = mock(ClientTravelScene.class);
        set(pending, "level", level);
        set(pending, "scene", scene);
        map(pending, "decoded").put(begin.chunks().getFirst(), 0);
        set(travel, "pendingPreparation", pending);
        try (MockedStatic<ClientSodiumTerrain> terrain = mockStatic(ClientSodiumTerrain.class);
             MockedStatic<Minecraft> minecraft = mockStatic(Minecraft.class);
             MockedStatic<PortalIrisMainPipelines> iris = mockStatic(PortalIrisMainPipelines.class)) {
            minecraft.when(Minecraft::getInstance).thenReturn(mock(Minecraft.class));
            iris.when(() -> PortalIrisMainPipelines.prepare(level)).thenReturn(true);
            terrain.when(() -> ClientSodiumTerrain.prepare(eq(level), eq(begin.environment()), any()))
                .thenReturn(ClientSodiumTerrain.Preparation.PENDING, ClientSodiumTerrain.Preparation.READY,
                    ClientSodiumTerrain.Preparation.COVER);
            terrain.when(() -> ClientSodiumTerrain.usesPreparedTerrain(level)).thenReturn(true, true, false);
            invoke(travel, "advancePreparation", new Class<?>[0]);
            verify(scene, never()).advance();
            invoke(travel, "advancePreparation", new Class<?>[0]);
            verify(scene, never()).advance();
            invoke(travel, "advancePreparation", new Class<?>[0]);
            verify(scene).advance();
            terrain.verify(() -> ClientSodiumTerrain.prepare(eq(level), eq(begin.environment()), any()), times(3));
            assertSame(pending, field(travel, "pendingPreparation"));
        }
    }

    @Test
    public void retainedSourceReconcilesProofWithoutSnapshotsWhileDestinationUsesNativeTerrain() throws ReflectiveOperationException {
        ClientPreparedTravel travel = ClientTravelTestFixtures.travel(ignored -> { });
        TravelMessage.TravelBegin begin = begin();
        Object source = source(begin);
        ClientTravelScene scene = mock(ClientTravelScene.class);
        ClientLevel destination = mock(ClientLevel.class);
        set(source, "level", mock(ClientLevel.class));
        set(source, "scene", scene);
        set(source, "changed", true);
        map(source, "decoded").put(begin.chunks().getFirst(), 0);
        set(travel, "sourcePreparation", source);
        set(travel, "staged", destination);
        try (MockedStatic<ClientSodiumTerrain> terrain = mockStatic(ClientSodiumTerrain.class)) {
            terrain.when(() -> ClientSodiumTerrain.usesPreparedTerrain(destination)).thenReturn(true, false);
            invoke(travel, "advanceSourcePreparation", new Class<?>[0]);
            verify(scene).nativeColumns(Map.of());
            verify(scene, never()).advance();
            assertEquals(false, field(source, "changed"));
            invoke(travel, "advanceSourcePreparation", new Class<?>[0]);
            verify(scene).advance();
            assertSame(source, field(travel, "sourcePreparation"));
        }
    }

    @Test
    public void inactiveSourceDeltaInvalidatesNativeTerrainAndCoverPacketProof() throws ReflectiveOperationException {
        ClientPreparedTravel travel = ClientTravelTestFixtures.travel(ignored -> { });
        TravelMessage.TravelBegin begin = begin();
        TravelMessage.TravelCoordinate coordinate = begin.chunks().getFirst();
        Object source = source(begin);
        ClientLevel level = mock(ClientLevel.class);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        ClientTravelScene scene = mock(ClientTravelScene.class);
        long section = SectionPos.asLong(coordinate.x(), 4, coordinate.z());
        when(scene.changedSection(section)).thenReturn(new LongOpenHashSet());
        set(source, "level", level);
        set(source, "scene", scene);
        map(source, "payloads").put(coordinate, new byte[]{1});
        map(source, "decoded").put(coordinate, 0);
        set(source, "bytes", 1);
        ((BitSet) field(source, "captured")).set(0);
        set(travel, "sourcePreparation", source);
        set(travel, "sourceCapture", 1);
        try (MockedStatic<ClientSodiumTerrain> terrain = mockStatic(ClientSodiumTerrain.class)) {
            travel.sectionChanged(level, coordinate.x(), 4, coordinate.z());
            verify(scene).invalidateColumn(coordinate.x(), coordinate.z());
            verify(scene).changedSection(section);
            terrain.verify(() -> ClientSodiumTerrain.dirty(level, section));
            assertTrue(map(source, "payloads").isEmpty());
            assertTrue(map(source, "decoded").isEmpty());
            assertFalse(((BitSet) field(source, "captured")).get(0));
            assertEquals(0, field(travel, "sourceCapture"));
        }
    }

    @Test
    public void adoptedNativeBytesSurviveArrivalCompletionAndDeltasDisableSuppression() throws ReflectiveOperationException {
        TravelMessage.TravelBegin begin = begin();
        ClientPreparedTravel travel = ClientTravelTestFixtures.travel(ignored -> { });
        ClientLevel level = mock(ClientLevel.class, withSettings().extraInterfaces(PreparedLevelAccess.class));
        when(((PreparedLevelAccess) level).wormholes$lightUpdates()).thenReturn(new ArrayDeque<>());
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(level.registryAccess()).thenReturn(RegistryAccess.EMPTY);
        ClientChunkCache cache = mock(ClientChunkCache.class);
        when(level.getChunkSource()).thenReturn(cache);
        when(cache.getChunk(0, 0, FULL, false)).thenReturn(mock(LevelChunk.class));
        ClientPacketListener connection = mock(ClientPacketListener.class);
        when(connection.registryAccess()).thenReturn(RegistryAccess.EMPTY);
        Minecraft minecraft = mock(Minecraft.class);
        minecraft.level = level;
        when(minecraft.getConnection()).thenReturn(connection);
        WormholesClient client = mock(WormholesClient.class);
        ClientViewSession session = mock(ClientViewSession.class);
        when(client.session()).thenReturn(session);
        when(session.active()).thenReturn(true);
        when(session.has(ViewStreamCapability.PREPARED_TRAVEL_CACHE)).thenReturn(true);
        ClientboundLevelChunkWithLightPacket packet = packet();
        byte[] bytes = MinecraftChunkPacketEncoding.encode(RegistryAccess.EMPTY, packet);
        set(travel, "begin", begin);
        set(travel, "staged", level);
        set(travel, "deadline", System.currentTimeMillis() + 30_000);
        set(travel, "mainCompiled", true);
        map(travel, "payloads").put(begin.chunks().getFirst(), bytes);
        try (MockedStatic<Minecraft> access = mockStatic(Minecraft.class);
             MockedStatic<WormholesClient> clients = mockStatic(WormholesClient.class);
             MockedStatic<ClientPortalRenderer> renderer = mockStatic(ClientPortalRenderer.class);
             MockedStatic<ClientSodiumTerrain> terrain = mockStatic(ClientSodiumTerrain.class)) {
            access.when(Minecraft::getInstance).thenReturn(minecraft);
            clients.when(WormholesClient::instance).thenReturn(client);
            renderer.when(ClientPortalRenderer::instance).thenReturn(mock(ClientPortalRenderer.class));
            invoke(travel, "retainResidentColumns", new Class<?>[0]);
            invoke(travel, "finishArrival", new Class<?>[0]);
            assertNull(field(travel, "begin"));
            assertTrue(travel.receiveNativeChunk(level, packet));
            travel.sectionChanged(level, 0, 4, 0);
            assertFalse(travel.receiveNativeChunk(level, packet));
        }
    }

    @Test
    public void sourceCaptureTracksDistinctColumnsAboveBitSixtyFour() throws ReflectiveOperationException {
        TravelMessage.TravelBegin original = begin();
        List<TravelMessage.TravelCoordinate> coordinates = ClientTravelWindow.coordinates(0, 0, 5);
        TravelMessage.TravelBegin expanded = new TravelMessage.TravelBegin(original.token(), original.generation(), original.sourcePortal(),
            original.sourceWorld(), original.sourceGeometry(), original.destinationToSource(), original.world(), original.arrival(),
            coordinates, original.environment(), original.expiresMillis(), TravelMessage.ArrivalRules.FRAME, false, 0, false);
        Object source = source(expanded);
        Class<?> columnType = Class.forName(ClientPreparedTravel.class.getName() + "$Column");
        Constructor<?> constructor = columnType.getDeclaredConstructor(int.class, int.class, int.class, byte[].class);
        constructor.setAccessible(true);
        for (int index = 0; index < coordinates.size(); index++) {
            assertEquals(index, invoke(source, "nextCapture", new Class<?>[0]));
            TravelMessage.TravelCoordinate coordinate = coordinates.get(index);
            invoke(source, "capture", new Class<?>[]{int.class, columnType}, index,
                constructor.newInstance(coordinate.x(), coordinate.z(), 0, new byte[]{1}));
        }
        assertEquals(-1, invoke(source, "nextCapture", new Class<?>[0]));
        assertEquals(coordinates.size(), map(source, "payloads").size());
    }

    private static TravelMessage.TravelBegin begin() throws ReflectiveOperationException {
        Method method = ClientPreparedTravelPendingTest.class.getDeclaredMethod("begin", long.class);
        method.setAccessible(true);
        return (TravelMessage.TravelBegin) method.invoke(null, 11L);
    }

    private static Object source(TravelMessage.TravelBegin begin) throws ReflectiveOperationException {
        Class<?> type = Class.forName(ClientPreparedTravel.class.getName() + "$SourcePreparation");
        Constructor<?> constructor = type.getDeclaredConstructor(TravelMessage.TravelBegin.class);
        constructor.setAccessible(true);
        return constructor.newInstance(begin);
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

    private static Object invoke(Object target, String name, Class<?>[] parameters, Object... arguments) throws ReflectiveOperationException {
        Method method = target.getClass().getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method.invoke(target, arguments);
    }

    @SuppressWarnings("unchecked")
    private static Map<TravelMessage.TravelCoordinate, Object> map(Object target, String name) throws ReflectiveOperationException {
        return (Map<TravelMessage.TravelCoordinate, Object>) field(target, name);
    }
}
