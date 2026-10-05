package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.client.render.ClientSodiumTerrain;
import art.arcane.wormholes.modded.mixin.client.PreparedLightSectionStorageMixin;
import art.arcane.wormholes.modded.mixin.client.PreparedTravelChunkCacheMixin;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.BlockLightSectionStorage;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static net.minecraft.world.level.chunk.status.ChunkStatus.FULL;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ClientPreparedTravelLightTest {
    private static final long SECTION = SectionPos.asLong(0, 1, 0);

    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void identicalUniformAndRawLightPreserveAllQueueWritesWithoutGeometryInvalidation() throws ReflectiveOperationException {
        try (Fixture fixture = new Fixture()) {
            DataLayer uniform = new DataLayer();
            fixture.storage.updating(SECTION, uniform);
            fixture.storage.visible(SECTION, new DataLayer(15));
            DataLayer raw = new DataLayer(new byte[2048]);
            fixture.apply(() -> {
                fixture.storage.queue(SECTION, raw);
                fixture.explicitDirty(SECTION);
            });
            assertSame(raw, fixture.storage.queued(SECTION));
            assertTrue(uniform.isDefinitelyHomogenous());
            DataLayer replacement = new DataLayer();
            fixture.apply(() -> {
                fixture.storage.queue(SECTION, replacement);
                fixture.explicitDirty(SECTION);
            });
            assertSame(replacement, fixture.storage.queued(SECTION));
            assertTrue(replacement.isDefinitelyHomogenous());
            assertEquals(List.of(), fixture.changed);
            fixture.terrain.verifyNoInteractions();
            verify(fixture.level, never()).setSectionDirtyWithNeighbors(0, 1, 0);
        }
    }

    @Test
    public void actualUpdatingLayerOverridesStaleVisibleLayerAndSkyBlockChangesCoalesce() throws ReflectiveOperationException {
        try (Fixture fixture = new Fixture()) {
            fixture.storage.updating(SECTION, new DataLayer(7));
            fixture.storage.visible(SECTION, new DataLayer());
            assertTrue(fixture.storage.getDataLayerData(SECTION).isEmpty());
            Storage sky = new Storage(fixture.cache);
            sky.updating(SECTION, new DataLayer(15));
            fixture.apply(() -> {
                fixture.storage.queue(SECTION, new DataLayer());
                fixture.explicitDirty(SECTION);
                sky.queue(SECTION, new DataLayer());
                fixture.explicitDirty(SECTION);
            });
            assertEquals(List.of(SECTION), fixture.changed);
            verify(fixture.level).setSectionDirtyWithNeighbors(0, 1, 0);
        }
    }

    @Test
    public void queuedWritesKeepOrderAndCompareAgainstLatestPendingAuthoritativeData() throws ReflectiveOperationException {
        try (Fixture fixture = new Fixture()) {
            fixture.storage.updating(SECTION, new DataLayer());
            ArrayDeque<Runnable> queue = new ArrayDeque<>();
            List<Integer> applied = new ArrayList<>();
            for (int value : new int[]{9, 9, 0}) {
                queue.add(fixture.travel.nativeLightUpdate(fixture.level, 0, 0, () -> {
                    fixture.storage.queue(SECTION, new DataLayer(value));
                    fixture.explicitDirty(SECTION);
                    applied.add(value);
                }));
            }
            assertTrue(applied.isEmpty());
            queue.remove().run();
            DataLayer first = fixture.storage.queued(SECTION);
            queue.remove().run();
            assertFalse(first == fixture.storage.queued(SECTION));
            queue.remove().run();
            assertEquals(List.of(9, 9, 0), applied);
            assertEquals(List.of(SECTION, SECTION), fixture.changed);
            assertTrue(fixture.storage.queued(SECTION).isEmpty());
        }
    }

    @Test
    public void absentAndRetiringStorageRemainConservativeAndNullWritesStillRemoveQueuedData() throws ReflectiveOperationException {
        try (Fixture fixture = new Fixture()) {
            fixture.apply(() -> fixture.storage.queue(SECTION, new DataLayer()));
            fixture.storage.updating(SECTION, new DataLayer());
            fixture.storage.removing().add(SECTION);
            fixture.apply(() -> fixture.storage.queue(SECTION, new DataLayer()));
            fixture.storage.removing().clear();
            fixture.apply(() -> fixture.storage.queue(SECTION, null));
            assertEquals(List.of(SECTION, SECTION, SECTION), fixture.changed);
            assertTrue(fixture.storage.queued(SECTION) == null);
        }
    }

    @Test
    public void executionTimeOwnershipAndForeignColumnsKeepOrdinaryDirtyNotifications() throws ReflectiveOperationException {
        try (Fixture fixture = new Fixture()) {
            fixture.storage.updating(SECTION, new DataLayer());
            long foreign = SectionPos.asLong(1, 1, 0);
            fixture.apply(() -> {
                fixture.storage.queue(foreign, new DataLayer(8));
                fixture.explicitDirty(foreign);
            });
            assertTrue(fixture.changed.isEmpty());
            verify(fixture.level).setSectionDirtyWithNeighbors(1, 1, 0);
            LightChunkGetter foreignSource = mock(LightChunkGetter.class);
            Storage foreignStorage = new Storage(foreignSource);
            foreignStorage.updating(SECTION, new DataLayer());
            ClientLevel foreignLevel = mock(ClientLevel.class);
            fixture.apply(() -> {
                assertFalse(ClientPreparedTravel.applyingNativeLight(foreignSource, SECTION));
                foreignStorage.queue(SECTION, new DataLayer(8));
                fixture.explicitDirty(foreignLevel, SECTION);
            });
            assertTrue(fixture.changed.isEmpty());
            verify(foreignLevel).setSectionDirtyWithNeighbors(0, 1, 0);
            Runnable delayed = fixture.travel.nativeLightUpdate(fixture.level, 0, 0, () -> {
                assertFalse(ClientPreparedTravel.applyingColumn(fixture.level, 0, 0));
                fixture.storage.queue(SECTION, new DataLayer(8));
                fixture.explicitDirty(SECTION);
            });
            fixture.minecraft.level = mock(ClientLevel.class);
            delayed.run();
            assertTrue(fixture.changed.isEmpty());
            verify(fixture.level).setSectionDirtyWithNeighbors(0, 1, 0);
        }
    }

    @Test
    public void failedQueuedApplicationRestoresScopeAndBroadlyInvalidatesOwnedGeometry() throws ReflectiveOperationException {
        try (Fixture fixture = new Fixture()) {
            RuntimeException failure = new IllegalStateException("light application failed");
            assertSame(failure, assertThrows(RuntimeException.class, () -> fixture.apply(() -> {
                fixture.storage.queue(SECTION, new DataLayer(8));
                throw failure;
            })));
            assertFalse(ClientPreparedTravel.applyingColumn(fixture.level, 0, 0));
            assertFalse(ClientPreparedTravel.applyingNativeLight(fixture.cache, SECTION));
            verify(fixture.level).setSectionRangeDirty(-1, 0, -1, 1, 2, 1);
            fixture.clients.verify(() -> WormholesClient.localChunkChanged(fixture.level, 0, 0));
        }
    }

    private static Object invoke(Method method, Object receiver, Object... arguments) {
        try {
            return method.invoke(receiver, arguments);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (failure.getCause() instanceof Error error) {
                throw error;
            }
            throw new AssertionError(failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Method method(Class<?> type, String name, Class<?>... parameters) throws ReflectiveOperationException {
        Method method = type.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method;
    }

    private static final class Fixture implements AutoCloseable {
        private final ClientPreparedTravel travel = new ClientPreparedTravel(ignored -> { });
        private final ClientLevel level = mock(ClientLevel.class);
        private final ClientChunkCache cache = mock(ClientChunkCache.class);
        private final Minecraft minecraft = mock(Minecraft.class);
        private final List<Long> changed = new ArrayList<>();
        private final MockedStatic<Minecraft> access;
        private final MockedStatic<ClientSodiumTerrain> terrain;
        private final MockedStatic<WormholesClient> clients;
        private final Storage storage;
        private final PreparedTravelChunkCacheMixin packetMixin = mock(PreparedTravelChunkCacheMixin.class, CALLS_REAL_METHODS);
        private final Method dirty = method(PreparedTravelChunkCacheMixin.class, "wormholes$sectionLightGeometry",
            ClientLevel.class, int.class, int.class, int.class, Operation.class);

        private Fixture() throws ReflectiveOperationException {
            when(level.getChunkSource()).thenReturn(cache);
            when(level.dimension()).thenReturn(Level.OVERWORLD);
            when(level.registryAccess()).thenReturn(RegistryAccess.EMPTY);
            when(level.getMaxSectionY()).thenReturn(2);
            when(cache.getChunk(0, 0, FULL, false)).thenReturn(mock(LevelChunk.class));
            ClientPacketListener connection = mock(ClientPacketListener.class);
            when(connection.registryAccess()).thenReturn(RegistryAccess.EMPTY);
            when(minecraft.getConnection()).thenReturn(connection);
            minecraft.level = level;
            Class<?> resident = Class.forName(ClientPreparedTravel.class.getName() + "$ResidentColumns");
            Constructor<?> constructor = resident.getDeclaredConstructor(ClientLevel.class, ClientPacketListener.class, Object.class,
                long.class, Map.class);
            constructor.setAccessible(true);
            field(ClientPreparedTravel.class, "resident").set(travel,
                constructor.newInstance(level, connection, RegistryAccess.EMPTY, System.currentTimeMillis() + 60_000, new HashMap<>()));
            storage = new Storage(cache);
            access = mockStatic(Minecraft.class);
            access.when(Minecraft::getInstance).thenReturn(minecraft);
            terrain = mockStatic(ClientSodiumTerrain.class);
            clients = mockStatic(WormholesClient.class);
            clients.when(() -> WormholesClient.localSectionChanged(level, 0, 1, 0)).thenAnswer(call -> {
                assertFalse(ClientPreparedTravel.applyingColumn(level, 0, 0));
                changed.add(SECTION);
                return null;
            });
        }

        private void apply(Runnable update) {
            travel.nativeLightUpdate(level, 0, 0, update).run();
            assertFalse(ClientPreparedTravel.applyingColumn(level, 0, 0));
        }

        private void explicitDirty(long section) {
            explicitDirty(level, section);
        }

        private void explicitDirty(ClientLevel destination, long section) {
            Operation<Void> original = arguments -> {
                ((ClientLevel) arguments[0]).setSectionDirtyWithNeighbors((Integer) arguments[1], (Integer) arguments[2], (Integer) arguments[3]);
                return null;
            };
            invoke(dirty, packetMixin, destination, SectionPos.x(section), SectionPos.y(section), SectionPos.z(section), original);
        }

        @Override
        public void close() {
            clients.close();
            terrain.close();
            access.close();
        }
    }

    private static final class Storage extends BlockLightSectionStorage {
        private final Observer observer;
        private final Method observe;
        private final LongSet removals;

        private Storage(LightChunkGetter source) throws ReflectiveOperationException {
            super(source);
            removals = (LongSet) field(LayerLightSectionStorage.class, "toRemove").get(this);
            observer = new Observer(this);
            observe = method(PreparedLightSectionStorageMixin.class, "wormholes$nativeLightDelta", long.class, DataLayer.class,
                CallbackInfo.class);
            field(PreparedLightSectionStorageMixin.class, "toRemove").set(observer, removing());
        }

        private void queue(long section, DataLayer incoming) {
            invoke(observe, observer, section, incoming, null);
            super.queueSectionData(section, incoming);
        }

        private void updating(long section, DataLayer data) {
            updatingSectionData.setLayer(section, data);
            updatingSectionData.clearCache();
        }

        private void visible(long section, DataLayer data) {
            visibleSectionData.setLayer(section, data);
            visibleSectionData.clearCache();
        }

        private DataLayer queued(long section) {
            return queuedSections.get(section);
        }

        private LongSet removing() {
            return removals;
        }

        private LightChunkGetter source() {
            return chunkSource;
        }

        private Long2ObjectMap<DataLayer> pending() {
            return queuedSections;
        }

        private DataLayer current(long section, boolean updating) {
            return getDataLayer(section, updating);
        }

        private boolean stored(long section) {
            return storingLightForSection(section);
        }
    }

    private static final class Observer extends PreparedLightSectionStorageMixin {
        private final Storage storage;

        private Observer(Storage storage) {
            this.storage = storage;
            chunkSource = storage.source();
            queuedSections = storage.pending();
        }

        @Override
        protected DataLayer getDataLayer(long section, boolean updating) {
            return storage.current(section, updating);
        }

        @Override
        protected boolean storingLightForSection(long section) {
            return storage.stored(section);
        }
    }
}
