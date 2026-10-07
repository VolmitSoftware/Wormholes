package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.client.render.ClientSodiumTerrain;
import art.arcane.wormholes.modded.client.render.PortalIrisMainPipelines;
import art.arcane.wormholes.modded.mixin.client.PreparedTravelPacketMixin;
import art.arcane.wormholes.modded.mixin.client.PreparedLevelAccess;
import art.arcane.wormholes.modded.mixin.client.PreparedPacketAccess;
import art.arcane.optics.aperture.ApertureDescriptor;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.mockito.ArgumentCaptor;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.LevelLoadTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.MockedConstruction;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Map;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import static art.arcane.wormholes.modded.client.ClientTravelTestFixtures.set;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static net.minecraft.world.level.chunk.status.ChunkStatus.FULL;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;
import art.arcane.wormholes.network.client.TravelMessage;

public class ClientPreparedTravelRespawnTest extends MinecraftTestBase {
    @Test
    public void authoritativeRespawnUsesTerrainAndShaderScopesWithoutPlayerKeepFlags() throws ReflectiveOperationException {
        Minecraft minecraft = mock(Minecraft.class);
        ClientLevel source = mock(ClientLevel.class, withSettings().extraInterfaces(PreparedLevelAccess.class));
        minecraft.level = source;
        ClientLevel destination = mock(ClientLevel.class);
        ClientChunkCache destinationCache = mock(ClientChunkCache.class, withSettings().extraInterfaces(PreparedChunkColumns.class));
        when(destination.getChunkSource()).thenReturn(destinationCache);
        ClientPreparedTravel travel = adopted(destination);
        assertFalse(travel.seamlessRespawn());
        WormholesClient client = mock(WormholesClient.class);
        when(client.preparedTravel()).thenReturn(travel);
        Operation<Void> vanilla = operation();
        ClientSodiumTerrain.Handoff terrainScope = mock(ClientSodiumTerrain.Handoff.class);
        PortalIrisMainPipelines.Handoff shaderScope = mock(PortalIrisMainPipelines.Handoff.class);
        AtomicBoolean terrainOpen = new AtomicBoolean();
        AtomicBoolean shaderOpen = new AtomicBoolean();
        boolean iris = iris();
        doAnswer(call -> {
            assertTrue(terrainOpen.get());
            if (iris) {
                assertTrue(shaderOpen.get());
            }
            minecraft.level = destination;
            return null;
        }).when(minecraft).setLevel(destination);
        doAnswer(call -> { terrainOpen.set(false); return null; }).when(terrainScope).close();
        doAnswer(call -> { shaderOpen.set(false); return null; }).when(shaderScope).close();
        try (MockedStatic<Minecraft> access = mockStatic(Minecraft.class);
             MockedStatic<WormholesClient> clients = mockStatic(WormholesClient.class);
             MockedStatic<ClientSodiumTerrain> terrain = mockStatic(ClientSodiumTerrain.class);
             MockedStatic<PortalIrisMainPipelines> shaders = mockStatic(PortalIrisMainPipelines.class);
             MockedConstruction<PreparedLevelExtractor> extractors = mockConstruction(PreparedLevelExtractor.class)) {
            access.when(Minecraft::getInstance).thenReturn(minecraft);
            clients.when(WormholesClient::instance).thenReturn(client);
            terrain.when(() -> ClientSodiumTerrain.handoff(destination)).thenAnswer(call -> {
                terrainOpen.set(true);
                return terrainScope;
            });
            shaders.when(() -> PortalIrisMainPipelines.handoff(destination)).thenAnswer(call -> {
                shaderOpen.set(true);
                return shaderScope;
            });
            assign(minecraft, destination, vanilla);
            verify(minecraft).setLevel(destination);
            ArgumentCaptor<LevelExtractor> extractor = ArgumentCaptor.forClass(LevelExtractor.class);
            verify((PreparedLevelAccess) source).wormholes$extractor(extractor.capture());
            assertEquals(1, extractors.constructed().size());
            assertSame(extractors.constructed().getFirst(), extractor.getValue());
            verify((PreparedChunkColumns) destinationCache).wormholes$announceColumns();
            verify(vanilla, never()).call(minecraft, destination);
            verify(terrainScope).close();
            if (iris) {
                verify(shaderScope).close();
            }
            assertFalse(terrainOpen.get());
            assertFalse(shaderOpen.get());
        }
    }

    @Test
    public void alreadyAttachedPreparedLevelSkipsEngineResetWithoutPlayerKeepFlags() throws ReflectiveOperationException {
        Minecraft minecraft = mock(Minecraft.class);
        ClientLevel destination = mock(ClientLevel.class);
        minecraft.level = destination;
        ClientPreparedTravel travel = adopted(destination);
        assertFalse(travel.seamlessRespawn());
        WormholesClient client = mock(WormholesClient.class);
        when(client.preparedTravel()).thenReturn(travel);
        Operation<Void> vanilla = operation();
        try (MockedStatic<Minecraft> access = mockStatic(Minecraft.class);
             MockedStatic<WormholesClient> clients = mockStatic(WormholesClient.class);
             MockedStatic<ClientSodiumTerrain> terrain = mockStatic(ClientSodiumTerrain.class)) {
            access.when(Minecraft::getInstance).thenReturn(minecraft);
            clients.when(WormholesClient::instance).thenReturn(client);
            assign(minecraft, destination, vanilla);
            verify(minecraft, never()).setLevel(destination);
            verify(vanilla, never()).call(minecraft, destination);
            terrain.verifyNoInteractions();
        }
    }

    @Test
    public void ordinaryRespawnRetainsVanillaLevelAssignment() throws ReflectiveOperationException {
        Minecraft minecraft = mock(Minecraft.class);
        ClientLevel destination = mock(ClientLevel.class);
        WormholesClient client = mock(WormholesClient.class);
        when(client.preparedTravel()).thenReturn(ClientTravelTestFixtures.travel(ignored -> { }));
        Operation<Void> vanilla = operation();
        try (MockedStatic<WormholesClient> clients = mockStatic(WormholesClient.class);
             MockedStatic<ClientSodiumTerrain> terrain = mockStatic(ClientSodiumTerrain.class)) {
            clients.when(WormholesClient::instance).thenReturn(client);
            assign(minecraft, destination, vanilla);
            verify(vanilla).call(minecraft, destination);
            terrain.verifyNoInteractions();
        }
    }

    @Test
    public void retainedFallbackDefersBeforePositionAndCompletesOnlyWithActualCompiledArrival() throws ReflectiveOperationException {
        Minecraft minecraft = mock(Minecraft.class);
        ClientLevel level = mock(ClientLevel.class);
        minecraft.level = level;
        ClientChunkCache cache = mock(ClientChunkCache.class);
        when(level.getChunkSource()).thenReturn(cache);
        when(level.registryAccess()).thenReturn(RegistryAccess.EMPTY);
        ClientPacketListener connection = mock(ClientPacketListener.class, withSettings().extraInterfaces(PreparedPacketAccess.class));
        when(connection.registryAccess()).thenReturn(RegistryAccess.EMPTY);
        when(minecraft.getConnection()).thenReturn(connection);
        LocalPlayer player = mock(LocalPlayer.class);
        minecraft.player = player;
        Vec3 eye = new Vec3(48.5, 65.62, 112.5);
        when(player.getEyePosition()).thenReturn(eye);
        BlockPos position = BlockPos.containing(eye);
        when(cache.getChunk(3, 7, FULL, false)).thenReturn(mock(LevelChunk.class));
        LevelRenderer renderer = mock(LevelRenderer.class);
        set(minecraft, "levelRenderer", renderer);
        when(renderer.isSectionCompiledAndVisible(position, 0)).thenReturn(true);
        LevelLoadTracker tracker = mock(LevelLoadTracker.class);
        when(((PreparedPacketAccess) connection).wormholes$loadTracker()).thenReturn(tracker);
        Runnable compiled = mock(Runnable.class);
        when(tracker.getPlayerCompiledSectionCallback()).thenReturn(compiled);
        when(connection.hasClientLoaded()).thenReturn(true);
        LevelLoadingScreen screen = mock(LevelLoadingScreen.class);
        ClientPreparedTravel travel = fallback(level, connection);
        try (MockedStatic<Minecraft> access = mockStatic(Minecraft.class)) {
            access.when(Minecraft::getInstance).thenReturn(minecraft);
            assertTrue(travel.deferLoadingScreen(screen));
            travel.endRespawn();
            advanceFallback(travel);
            verifyNoInteractions(renderer, compiled);
            verify(minecraft, never()).setScreenAndShow(screen);
            travel.serverPosition();
            verify(renderer).isSectionCompiledAndVisible(position, 0);
            verify(compiled).run();
            verify(minecraft, never()).setScreenAndShow(screen);
            advanceFallback(travel);
            verify(renderer).isSectionCompiledAndVisible(position, 0);
        }
    }

    @Test
    public void retainedFallbackKeepsVanillaScreenForMissingOrUncompiledArrival() throws ReflectiveOperationException {
        for (boolean physical : new boolean[]{false, true}) {
            Minecraft minecraft = mock(Minecraft.class);
            ClientLevel level = mock(ClientLevel.class);
            minecraft.level = level;
            when(level.registryAccess()).thenReturn(RegistryAccess.EMPTY);
            ClientPacketListener connection = mock(ClientPacketListener.class, withSettings().extraInterfaces(PreparedPacketAccess.class));
            when(connection.registryAccess()).thenReturn(RegistryAccess.EMPTY);
            when(minecraft.getConnection()).thenReturn(connection);
            ClientChunkCache cache = mock(ClientChunkCache.class);
            when(level.getChunkSource()).thenReturn(cache);
            if (physical) {
                when(cache.getChunk(0, 0, FULL, false)).thenReturn(mock(LevelChunk.class));
            }
            LocalPlayer player = mock(LocalPlayer.class);
            minecraft.player = player;
            when(player.getEyePosition()).thenReturn(new Vec3(0.5, 65.62, 0.5));
            LevelRenderer renderer = mock(LevelRenderer.class);
            set(minecraft, "levelRenderer", renderer);
            set(minecraft, "gui", mock(Gui.class));
            LevelLoadingScreen screen = mock(LevelLoadingScreen.class);
            ClientPreparedTravel travel = fallback(level, connection);
            try (MockedStatic<Minecraft> access = mockStatic(Minecraft.class)) {
                access.when(Minecraft::getInstance).thenReturn(minecraft);
                assertTrue(travel.deferLoadingScreen(screen));
                travel.serverPosition();
                verify(minecraft).setScreenAndShow(screen);
                verify((PreparedPacketAccess) connection, never()).wormholes$loadTracker();
            }
        }
    }

    @Test
    public void retainedFallbackDoesNotDeferForChangedConnectionRegistryOrWorld() throws ReflectiveOperationException {
        for (int mismatch = 0; mismatch < 3; mismatch++) {
            Minecraft minecraft = mock(Minecraft.class);
            ClientLevel level = mock(ClientLevel.class);
            minecraft.level = level;
            when(level.registryAccess()).thenReturn(RegistryAccess.EMPTY);
            ClientPacketListener connection = mock(ClientPacketListener.class);
            when(connection.registryAccess()).thenReturn(RegistryAccess.EMPTY);
            when(minecraft.getConnection()).thenReturn(connection);
            ClientPreparedTravel travel = fallback(level, connection);
            if (mismatch == 0) {
                when(minecraft.getConnection()).thenReturn(mock(ClientPacketListener.class));
            } else if (mismatch == 1) {
                when(level.registryAccess()).thenReturn(mock(RegistryAccess.class));
            } else {
                minecraft.level = mock(ClientLevel.class);
            }
            try (MockedStatic<Minecraft> access = mockStatic(Minecraft.class)) {
                access.when(Minecraft::getInstance).thenReturn(minecraft);
                assertFalse(travel.deferLoadingScreen(mock(LevelLoadingScreen.class)));
            }
        }
    }

    private static ClientPreparedTravel fallback(ClientLevel level, ClientPacketListener connection) throws ReflectiveOperationException {
        Class<?> retainedType = Class.forName(ClientPreparedTravel.class.getName() + "$RetainedWorld");
        Constructor<?> retained = retainedType.getDeclaredConstructor(ClientLevel.class, ClientPacketListener.class, Object.class,
            TravelMessage.TravelWorld.class, long.class, Map.class, ApertureDescriptor.class);
        retained.setAccessible(true);
        Object provenance = retained.newInstance(level, connection, RegistryAccess.EMPTY, null, System.currentTimeMillis() + 60_000, Map.of(), null);
        Class<?> arrivalType = Class.forName(ClientPreparedTravel.class.getName() + "$AuthoritativeArrival");
        Constructor<?> arrival = arrivalType.getDeclaredConstructor(retainedType);
        arrival.setAccessible(true);
        ClientPreparedTravel travel = ClientTravelTestFixtures.travel(ignored -> { });
        set(travel, "authoritativeArrival", arrival.newInstance(provenance));
        return travel;
    }

    private static void advanceFallback(ClientPreparedTravel travel) throws ReflectiveOperationException {
        Method method = ClientPreparedTravel.class.getDeclaredMethod("advanceAuthoritativeArrival");
        method.setAccessible(true);
        method.invoke(travel);
    }

    private static ClientPreparedTravel adopted(ClientLevel level) throws ReflectiveOperationException {
        ClientPreparedTravel travel = ClientTravelTestFixtures.travel(ignored -> { });
        Field staged = ClientPreparedTravel.class.getDeclaredField("staged");
        staged.setAccessible(true);
        staged.set(travel, level);
        Field adopted = ClientPreparedTravel.class.getDeclaredField("adopted");
        adopted.setAccessible(true);
        adopted.setBoolean(travel, true);
        return travel;
    }

    private static boolean iris() throws ReflectiveOperationException {
        Field field = ClientPreparedTravel.class.getDeclaredField("IRIS");
        field.setAccessible(true);
        return field.getBoolean(null);
    }

    private static void assign(Minecraft minecraft, ClientLevel level, Operation<Void> operation) throws ReflectiveOperationException {
        PreparedTravelPacketMixin mixin = mock(PreparedTravelPacketMixin.class, CALLS_REAL_METHODS);
        Method method = PreparedTravelPacketMixin.class.getDeclaredMethod("wormholes$attachedLevel", Minecraft.class, ClientLevel.class, Operation.class);
        method.setAccessible(true);
        method.invoke(mixin, minecraft, level, operation);
    }

    @SuppressWarnings("unchecked")
    private static Operation<Void> operation() {
        return mock(Operation.class);
    }
}
