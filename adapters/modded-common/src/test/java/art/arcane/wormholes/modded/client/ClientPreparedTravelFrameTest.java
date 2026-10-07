package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.mixin.client.PreparedTravelCameraMixin;
import art.arcane.wormholes.modded.mixin.client.PreparedLevelAccess;
import art.arcane.wormholes.modded.mixin.client.PreparedLevelDataAccess;
import art.arcane.wormholes.modded.mixin.client.PreparedPacketAccess;
import art.arcane.wormholes.modded.client.render.ClientSodiumTerrain;
import art.arcane.wormholes.modded.client.render.ClientPortalRenderer;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.level.chunk.LevelChunk;
import static net.minecraft.world.level.chunk.status.ChunkStatus.FULL;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import art.arcane.optics.aperture.ApertureDescriptor;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.textures.GpuTexture;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.Overlay;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;
import org.mockito.MockedStatic;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.concurrent.atomic.AtomicBoolean;

import java.lang.reflect.Field;
import java.lang.reflect.Constructor;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.lang.reflect.Method;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;
import art.arcane.wormholes.network.client.TravelMessage;

public class ClientPreparedTravelFrameTest extends MinecraftTestBase {
    @Test
    public void retainedFrameWaitsOnlyForServerPositionWithoutChangingPlayerOrLoadedState() throws ReflectiveOperationException {
        Minecraft minecraft = minecraft();
        ClientPreparedTravel travel = fallback(minecraft);
        try (MockedStatic<Minecraft> access = mockStatic(Minecraft.class)) {
            access.when(Minecraft::getInstance).thenReturn(minecraft);
            assertTrue(travel.holdAuthoritativeFrame());
            assertTrue(travel.holdAuthoritativeFrame());
            verifyNoInteractions(minecraft.player);
            verify(minecraft.getConnection(), never()).hasClientLoaded();
            when(minecraft.player.getEyePosition()).thenReturn(new Vec3(48.5, 65.62, 112.5));
            when(minecraft.level.getChunkSource()).thenReturn(mock(ClientChunkCache.class));
            travel.serverPosition();
            assertFalse(travel.holdAuthoritativeFrame());
            verify(minecraft.getConnection(), never()).hasClientLoaded();
        }
    }

    @Test
    public void timeoutReleasesTheFrameAndRestoresOrdinaryLoadingScreen() throws ReflectiveOperationException {
        Minecraft minecraft = minecraft();
        ClientPreparedTravel travel = fallback(minecraft);
        LevelLoadingScreen screen = mock(LevelLoadingScreen.class);
        try (MockedStatic<Minecraft> access = mockStatic(Minecraft.class)) {
            access.when(Minecraft::getInstance).thenReturn(minecraft);
            assertTrue(travel.deferLoadingScreen(screen));
            field(field(travel, "authoritativeArrival"), "deadline", 1L);
            assertFalse(travel.holdAuthoritativeFrame());
            travel.tick();
            verify(minecraft).setScreenAndShow(screen);
            verifyNoInteractions(minecraft.player);
            verify(minecraft.getConnection(), never()).hasClientLoaded();
        }
    }

    @Test
    public void actualReadyAdoptionUsesTwoSecondWaitAndCleansUpTimedOutScreenAfterPosition() throws ReflectiveOperationException {
        for (boolean expired : new boolean[] { false, true }) {
            Minecraft minecraft = minecraft();
            ClientLevel destination = mock(ClientLevel.class, withSettings().extraInterfaces(PreparedLevelAccess.class));
            when(destination.registryAccess()).thenReturn(RegistryAccess.EMPTY);
            ClientChunkCache chunks = mock(ClientChunkCache.class);
            when(destination.getChunkSource()).thenReturn(chunks);
            LevelChunk chunk = mock(LevelChunk.class);
            when(chunks.getChunk(3, 7, FULL, false)).thenReturn(chunk);
            LevelRenderer levelRenderer = mock(LevelRenderer.class);
            field(minecraft, "levelRenderer", levelRenderer);
            ClientPreparedTravel travel = new ClientPreparedTravel(ignored -> { });
            Method fixture = ClientPreparedTravelPendingTest.class.getDeclaredMethod("begin", long.class);
            fixture.setAccessible(true);
            TravelMessage.TravelBegin begin = (TravelMessage.TravelBegin) fixture.invoke(null, 9L);
            field(travel, "begin", begin);
            field(travel, "staged", destination);
            field(travel, "deadline", System.currentTimeMillis() + 300_000L);
            field(travel, "acknowledgedRevision", 42L);
            TravelMessage.TravelCommit commit = mock(TravelMessage.TravelCommit.class);
            when(commit.contentRevision()).thenReturn(42L);
            when(commit.arrival()).thenReturn(new TravelMessage.TravelPose(48.5, 64, 112.5, 0, 0));
            field(travel, "commit", commit);
            ClientLevel.ClientLevelData data = mock(ClientLevel.ClientLevelData.class, withSettings().extraInterfaces(PreparedLevelDataAccess.class));
            DimensionType dimension = mock(DimensionType.class);
            when(dimension.minY()).thenReturn(begin.world().minY());
            when(dimension.height()).thenReturn(begin.world().height());
            Holder.Reference<DimensionType> type = mock(Holder.Reference.class);
            when(type.value()).thenReturn(dimension);
            when(type.unwrapKey()).thenReturn(Optional.of(ResourceKey.create(Registries.DIMENSION_TYPE,
                Identifier.parse(begin.world().dimensionType()))));
            ClientPreparedTravel.Construction construction = new ClientPreparedTravel.Construction(data, Level.OVERWORLD, type,
                mock(LevelExtractor.class), begin.world().debug(), begin.world().seed(), begin.world().seaLevel(), 10, 8);
            AtomicReference<Screen> current = new AtomicReference<>();
            LevelLoadingScreen screen = mock(LevelLoadingScreen.class);
            when(minecraft.gui.screen()).thenAnswer(call -> current.get());
            doAnswer(call -> { current.set(screen); return null; }).when(minecraft).setScreenAndShow(screen);
            doAnswer(call -> { current.set(null); return null; }).when(minecraft.gui).setScreen(null);
            when(minecraft.player.position()).thenReturn(new Vec3(48.5, 64, 112.5));
            when(minecraft.player.getEyePosition()).thenReturn(new Vec3(48.5, 65.62, 112.5));
            ClientPortalRenderer renderer = mock(ClientPortalRenderer.class);
            when(minecraft.getConnection().hasClientLoaded()).thenReturn(true);
            try (MockedStatic<Minecraft> access = mockStatic(Minecraft.class);
                 MockedStatic<ClientSodiumTerrain> terrain = mockStatic(ClientSodiumTerrain.class);
                 MockedStatic<ClientPortalRenderer> renderers = mockStatic(ClientPortalRenderer.class)) {
                access.when(Minecraft::getInstance).thenReturn(minecraft);
                renderers.when(ClientPortalRenderer::instance).thenReturn(renderer);
                assertNull(travel.adopt(construction));
                terrain.when(() -> ClientSodiumTerrain.ready(destination)).thenReturn(true);
                long before = System.currentTimeMillis();
                assertSame(destination, travel.adopt(construction));
                minecraft.level = destination;
                Object arrival = field(travel, "authoritativeArrival");
                long waitingDeadline = (long) field(arrival, "deadline");
                assertTrue(waitingDeadline >= before + 2_000L);
                assertTrue(waitingDeadline <= System.currentTimeMillis() + 2_000L);
                assertTrue((long) field(travel, "deadline") > waitingDeadline + 100_000L);
                assertTrue(travel.holdAuthoritativeFrame());
                assertTrue(travel.deferLoadingScreen(screen));
                assertSame(screen, field(travel, "deferredScreen"));
                assertSame(screen, field(arrival, "screen"));
                if (expired) {
                    field(arrival, "deadline", 1L);
                    assertFalse(travel.holdAuthoritativeFrame());
                    Method advance = ClientPreparedTravel.class.getDeclaredMethod("advanceAuthoritativeArrival");
                    advance.setAccessible(true);
                    advance.invoke(travel);
                    assertSame(screen, current.get());
                }
                travel.serverPosition();
                assertFalse(travel.holdAuthoritativeFrame());
                assertNull(field(travel, "authoritativeArrival"));
                verifyNoInteractions(levelRenderer);
                verify(minecraft.getConnection(), never()).hasClientLoaded();
                if (!expired) {
                    assertNull(current.get());
                    verify(minecraft, never()).setScreenAndShow(screen);
                }
                Method complete = ClientPreparedTravel.class.getDeclaredMethod("completeLoad", ClientPacketListener.class);
                complete.setAccessible(true);
                complete.invoke(travel, minecraft.getConnection());
                assertNull(current.get());
                if (expired) {
                    verify(minecraft.gui).setScreen(null);
                }
            }
        }
    }

    @Test
    public void predictedTickAndRespawnKeepOwnedTerrainDuringNativeMaintenanceButRejectLostScopeOrChunks() throws ReflectiveOperationException {
        for (int rejected = 0; rejected < 6; rejected++) {
            Minecraft minecraft = minecraft();
            ClientPacketListener connection = mock(ClientPacketListener.class, withSettings().extraInterfaces(PreparedPacketAccess.class));
            when(connection.registryAccess()).thenReturn(RegistryAccess.EMPTY);
            when(minecraft.getConnection()).thenReturn(connection);
            when(minecraft.isSameThread()).thenReturn(true);
            ClientLevel level = mock(ClientLevel.class, withSettings().extraInterfaces(PreparedLevelAccess.class));
            minecraft.level = level;
            when(level.registryAccess()).thenReturn(RegistryAccess.EMPTY);
            ClientChunkCache chunks = mock(ClientChunkCache.class);
            when(level.getChunkSource()).thenReturn(chunks);
            LevelChunk chunk = mock(LevelChunk.class);
            List<TravelMessage.TravelCoordinate> coordinates = new ArrayList<>();
            for (int z = -1; z <= 1; z++) {
                for (int x = -1; x <= 1; x++) {
                    coordinates.add(new TravelMessage.TravelCoordinate(x, z));
                    when(chunks.getChunk(x, z, FULL, false)).thenReturn(chunk);
                }
            }
            Method fixture = ClientPreparedTravelPendingTest.class.getDeclaredMethod("begin", long.class);
            fixture.setAccessible(true);
            TravelMessage.TravelBegin original = (TravelMessage.TravelBegin) fixture.invoke(null, 9L);
            TravelMessage.TravelBegin begin = new TravelMessage.TravelBegin(original.token(), original.generation(),
                original.sourcePortal(), original.sourceWorld(), original.sourceGeometry(), original.destinationToSource(),
                original.world(), original.arrival(), coordinates, original.environment(), original.expiresMillis(), TravelMessage.ArrivalRules.FRAME, false, 0, false);
            ClientPreparedTravel travel = new ClientPreparedTravel(ignored -> { });
            field(travel, "begin", begin);
            field(travel, "staged", level);
            field(travel, "deadline", System.currentTimeMillis() + 300_000L);
            ClientTravelChunks barrier = mock(ClientTravelChunks.class);
            when(barrier.matches(begin.token(), begin.generation())).thenReturn(true);
            field(travel, "chunks", barrier);
            Object prediction = mock(Class.forName(ClientPreparedTravel.class.getName() + "$Prediction"));
            field(prediction, "source", level);
            field(prediction, "connection", rejected == 5 ? mock(ClientPacketListener.class) : connection);
            field(prediction, "revision", 42L);
            field(prediction, "deadline", rejected == 4 ? 1L : System.currentTimeMillis() + 2_000L);
            field(prediction, "extractor", mock(LevelExtractor.class));
            field(prediction, "motion", mock(ClientTravelMotion.class));
            field(prediction, "packets", new ArrayDeque<Runnable>());
            field(prediction, "sourceColumns", new ArrayList<>());
            field(travel, "prediction", prediction);
            when(minecraft.player.position()).thenReturn(new Vec3(0, 80, 0));
            if (rejected == 3) {
                when(chunks.getChunk(1, 1, FULL, false)).thenReturn(null);
            }
            ClientPortalRenderer renderer = mock(ClientPortalRenderer.class);
            TravelMessage.TravelCommit commit = mock(TravelMessage.TravelCommit.class);
            when(commit.token()).thenReturn(begin.token());
            when(commit.generation()).thenReturn(begin.generation());
            when(commit.contentRevision()).thenReturn(42L);
            when(commit.sourceWorld()).thenReturn(begin.sourceWorld());
            when(commit.destinationWorld()).thenReturn(begin.world().dimension());
            when(commit.arrival()).thenReturn(begin.arrival());
            ClientLevel.ClientLevelData data = mock(ClientLevel.ClientLevelData.class, withSettings().extraInterfaces(PreparedLevelDataAccess.class));
            DimensionType dimension = mock(DimensionType.class);
            when(dimension.minY()).thenReturn(begin.world().minY());
            when(dimension.height()).thenReturn(begin.world().height());
            Holder.Reference<DimensionType> type = mock(Holder.Reference.class);
            when(type.value()).thenReturn(dimension);
            when(type.unwrapKey()).thenReturn(Optional.of(ResourceKey.create(Registries.DIMENSION_TYPE,
                Identifier.parse(begin.world().dimensionType()))));
            ClientPreparedTravel.Construction construction = new ClientPreparedTravel.Construction(data, Level.OVERWORLD, type,
                mock(LevelExtractor.class), begin.world().debug(), begin.world().seed(), begin.world().seaLevel(), 10, 8);
            try (MockedStatic<Minecraft> access = mockStatic(Minecraft.class);
                 MockedStatic<ClientSodiumTerrain> terrain = mockStatic(ClientSodiumTerrain.class);
                 MockedStatic<ClientPortalRenderer> renderers = mockStatic(ClientPortalRenderer.class)) {
                access.when(Minecraft::getInstance).thenReturn(minecraft);
                renderers.when(ClientPortalRenderer::instance).thenReturn(renderer);
                terrain.when(() -> ClientSodiumTerrain.handlesMainUpdates(level)).thenReturn(rejected != 1);
                terrain.when(() -> ClientSodiumTerrain.usesPreparedTerrain(level)).thenReturn(rejected != 2);
                assertFalse(ClientSodiumTerrain.ready(level));
                travel.tick();
                if (rejected != 0) {
                    assertFalse(travel.pendingCrossing());
                    verify(renderer).cancelTravel();
                    continue;
                }
                assertTrue(travel.pendingCrossing());
                assertSame(level, travel.level());
                verify(renderer, never()).cancelTravel();
                travel.receive(commit);
                assertSame(commit, field(travel, "commit"));
                assertTrue(travel.beginRespawn(Level.OVERWORLD, true));
                assertSame(level, travel.adopt(construction));
                assertTrue(travel.adopted());
                assertTrue(travel.pendingCrossing());
                assertNull(field(travel, "authoritativeArrival"));
                travel.endRespawn();
                assertFalse(travel.seamlessRespawn());
                verify(minecraft, never()).setLevel(level);
            }
        }
    }

    @Test
    public void foreignExpiredOrdinaryPredictedAndVisibleUiStatesNeverHoldFrames() throws ReflectiveOperationException {
        for (int mismatch = 0; mismatch < 11; mismatch++) {
            Minecraft minecraft = minecraft();
            ClientPreparedTravel travel = mismatch == 0 ? new ClientPreparedTravel(ignored -> { }) : fallback(minecraft);
            switch (mismatch) {
                case 1 -> minecraft.level = mock(ClientLevel.class);
                case 2 -> when(minecraft.level.registryAccess()).thenReturn(mock(RegistryAccess.class));
                case 3 -> when(minecraft.getConnection()).thenReturn(mock(ClientPacketListener.class));
                case 4 -> {
                    Object arrival = field(travel, "authoritativeArrival");
                    Class<?> type = field(arrival, "retained").getClass();
                    Constructor<?> constructor = type.getDeclaredConstructor(ClientLevel.class, ClientPacketListener.class,
                        Object.class, TravelMessage.TravelWorld.class, long.class, Map.class, ApertureDescriptor.class);
                    constructor.setAccessible(true);
                    field(arrival, "retained", constructor.newInstance(minecraft.level, minecraft.getConnection(),
                        RegistryAccess.EMPTY, null, 1L, Map.of(), null));
                }
                case 5 -> when(minecraft.gui.screen()).thenReturn(mock(Screen.class));
                case 6 -> when(minecraft.gui.overlay()).thenReturn(mock(Overlay.class));
                case 7 -> field(travel, "prediction", mock(Class.forName(ClientPreparedTravel.class.getName() + "$Prediction")));
                case 8 -> field(travel, "adopted", true);
                case 9 -> minecraft.player = null;
                case 10 -> when(minecraft.getConnection()).thenReturn(null);
                default -> { }
            }
            try (MockedStatic<Minecraft> access = mockStatic(Minecraft.class)) {
                access.when(Minecraft::getInstance).thenReturn(minecraft);
                assertFalse(travel.holdAuthoritativeFrame());
            }
        }
    }

    @Test
    public void completedImageHoldsAllThreeStagesAndTextureOrUiChangesResumeFreshExtraction() throws ReflectiveOperationException {
        Minecraft minecraft = minecraft();
        ClientPreparedTravel travel = fallback(minecraft);
        WormholesClient client = mock(WormholesClient.class);
        when(client.preparedTravel()).thenReturn(travel);
        PreparedTravelCameraMixin mixin = mock(PreparedTravelCameraMixin.class, CALLS_REAL_METHODS);
        RenderTarget target = mock(RenderTarget.class);
        GpuTexture texture = mock(GpuTexture.class);
        when(target.getColorTexture()).thenReturn(texture);
        field(mixin, "mainRenderTarget", target);
        DeltaTracker tracker = mock(DeltaTracker.class);
        doAnswer(call -> {
            assertFalse(update(mixin, call.getArgument(0)));
            return null;
        }).when(mixin).update(tracker);
        doAnswer(call -> {
            assertFalse(extract(mixin, call.getArgument(0), call.getArgument(1)));
            return null;
        }).when(mixin).extract(tracker, false);
        try (MockedStatic<Minecraft> access = mockStatic(Minecraft.class);
             MockedStatic<WormholesClient> clients = mockStatic(WormholesClient.class)) {
            access.when(Minecraft::getInstance).thenReturn(minecraft);
            clients.when(WormholesClient::instance).thenReturn(client);
            assertFalse(update(mixin, tracker));
            complete(mixin);
            assertTrue(update(mixin, tracker));
            assertTrue(extract(mixin, tracker, false));
            assertTrue(render(mixin));
            verify(mixin, never()).update(tracker);
            verify(mixin, never()).extract(tracker, false);
            assertTrue(update(mixin, tracker));
            assertTrue(extract(mixin, tracker, false));
            when(target.getColorTexture()).thenReturn(mock(GpuTexture.class));
            assertFalse(render(mixin));
            verify(mixin).update(tracker);
            verify(mixin).extract(tracker, false);
            complete(mixin);
            assertTrue(update(mixin, tracker));
            when(minecraft.gui.screen()).thenReturn(mock(Screen.class));
            assertFalse(extract(mixin, tracker, false));
            assertFalse(render(mixin));
            verifyNoInteractions(minecraft.player);
            verify(minecraft.getConnection(), never()).hasClientLoaded();
        }
    }

    @Test
    public void missingClosedOrReplacedCompletedTextureDelegatesOrdinaryFrames() throws ReflectiveOperationException {
        Minecraft minecraft = minecraft();
        ClientPreparedTravel travel = fallback(minecraft);
        WormholesClient client = mock(WormholesClient.class);
        when(client.preparedTravel()).thenReturn(travel);
        PreparedTravelCameraMixin mixin = mock(PreparedTravelCameraMixin.class, CALLS_REAL_METHODS);
        RenderTarget target = mock(RenderTarget.class);
        GpuTexture texture = mock(GpuTexture.class);
        when(target.getColorTexture()).thenReturn(texture);
        field(mixin, "mainRenderTarget", target);
        DeltaTracker tracker = mock(DeltaTracker.class);
        try (MockedStatic<Minecraft> access = mockStatic(Minecraft.class);
             MockedStatic<WormholesClient> clients = mockStatic(WormholesClient.class)) {
            access.when(Minecraft::getInstance).thenReturn(minecraft);
            clients.when(WormholesClient::instance).thenReturn(client);
            assertFalse(update(mixin, tracker));
            complete(mixin);
            when(texture.isClosed()).thenReturn(true);
            assertFalse(update(mixin, tracker));
            when(texture.isClosed()).thenReturn(false);
            when(target.getColorTexture()).thenReturn(null);
            assertFalse(update(mixin, tracker));
            when(target.getColorTexture()).thenReturn(mock(GpuTexture.class));
            assertFalse(update(mixin, tracker));
        }
    }

    private static Minecraft minecraft() throws ReflectiveOperationException {
        Minecraft minecraft = mock(Minecraft.class);
        minecraft.level = mock(ClientLevel.class);
        minecraft.player = mock(LocalPlayer.class);
        field(minecraft, "gui", mock(Gui.class));
        ClientPacketListener connection = mock(ClientPacketListener.class);
        when(minecraft.level.registryAccess()).thenReturn(RegistryAccess.EMPTY);
        when(connection.registryAccess()).thenReturn(RegistryAccess.EMPTY);
        when(minecraft.getConnection()).thenReturn(connection);
        return minecraft;
    }

    private static ClientPreparedTravel fallback(Minecraft minecraft) throws ReflectiveOperationException {
        Method method = ClientPreparedTravelRespawnTest.class.getDeclaredMethod("fallback", ClientLevel.class, ClientPacketListener.class);
        method.setAccessible(true);
        return (ClientPreparedTravel) method.invoke(null, minecraft.level, minecraft.getConnection());
    }

    private static boolean update(PreparedTravelCameraMixin mixin, DeltaTracker tracker) throws ReflectiveOperationException {
        AtomicBoolean called = new AtomicBoolean();
        Operation<Void> original = arguments -> {
            called.set(true);
            return null;
        };
        invoke(mixin, "wormholes$holdUpdate", new Class<?>[]{DeltaTracker.class, Operation.class}, tracker, original);
        return !called.get();
    }

    private static boolean extract(PreparedTravelCameraMixin mixin, DeltaTracker tracker, boolean renderLevel) throws ReflectiveOperationException {
        AtomicBoolean called = new AtomicBoolean();
        Operation<Void> original = arguments -> {
            called.set(true);
            return null;
        };
        invoke(mixin, "wormholes$holdExtract", new Class<?>[]{DeltaTracker.class, boolean.class, Operation.class}, tracker, renderLevel, original);
        return !called.get();
    }

    private static boolean render(PreparedTravelCameraMixin mixin) throws ReflectiveOperationException {
        AtomicBoolean called = new AtomicBoolean();
        Operation<Void> original = arguments -> {
            called.set(true);
            return null;
        };
        invoke(mixin, "wormholes$holdRender", new Class<?>[]{Operation.class}, original);
        return !called.get();
    }

    private static void complete(PreparedTravelCameraMixin mixin) throws ReflectiveOperationException {
        assertFalse(render(mixin));
    }

    private static void invoke(PreparedTravelCameraMixin mixin, String name, Class<?>[] types, Object... arguments) throws ReflectiveOperationException {
        Method method = PreparedTravelCameraMixin.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        method.invoke(mixin, arguments);
    }

    private static Object field(Object target, String name) throws ReflectiveOperationException {
        Field field = field(target.getClass(), name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void field(Object target, String name, Object value) throws ReflectiveOperationException {
        Field field = field(target.getClass(), name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException missing) {
                if (current.getSuperclass() == null) {
                    throw missing;
                }
            }
        }
        throw new NoSuchFieldException(name);
    }
}
