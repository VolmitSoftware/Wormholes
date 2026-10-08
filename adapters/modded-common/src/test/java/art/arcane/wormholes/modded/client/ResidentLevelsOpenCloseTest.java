package art.arcane.wormholes.modded.client;

import art.arcane.optics.stream.EnvironmentState;
import art.arcane.wormholes.modded.client.world.PreparedLevelExtractor;
import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.client.render.ClientSodiumTerrain;
import art.arcane.wormholes.modded.client.world.ClientWorldLoader;
import art.arcane.wormholes.network.client.TravelMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReferenceArray;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

public class ResidentLevelsOpenCloseTest extends MinecraftTestBase {
    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void openCreatesOneLevelPerHandleAndReopeningTheSameWorldRecentersIt() {
        ClientLevel current = ResidentTestFixtures.level(ResidentTestFixtures.OVERWORLD);
        try (Scope scope = new Scope(current)) {
            ResidentLevels residents = scope.residents();
            ClientLevel nether = residents.open(ResidentTestFixtures.open(3, ResidentTestFixtures.NETHER, 12, -4));
            assertEquals(1, scope.levels.constructed().size());
            assertSame(scope.levels.constructed().getFirst(), nether);
            assertTrue(residents.has(3));
            assertSame(nether, residents.level(3));
            assertEquals(3, residents.handle(nether));
            verify(nether.getChunkSource(), atLeastOnce()).updateViewCenter(12, -4);
            assertSame(nether, residents.open(ResidentTestFixtures.open(3, ResidentTestFixtures.NETHER, 13, -4)));
            assertEquals(1, scope.levels.constructed().size());
            verify(nether.getChunkSource()).updateViewCenter(13, -4);
            assertFalse(residents.has(4));
            assertNull(residents.level(4));
        }
    }

    @Test
    public void anOpenArrivingWithoutACurrentLevelIsDropped() {
        ClientLevel current = ResidentTestFixtures.level(ResidentTestFixtures.OVERWORLD);
        try (Scope scope = new Scope(current)) {
            ResidentLevels residents = scope.residents();
            scope.minecraft.level = null;

            assertNull(residents.open(ResidentTestFixtures.open(3, ResidentTestFixtures.NETHER, 12, -4)));
            assertFalse(residents.has(3));
            assertTrue(scope.levels.constructed().isEmpty());
        }
    }

    @Test
    public void closedLevelsStayCachedAndAnOverlappingReopenReusesThemWithoutStreamingAgain() {
        ClientLevel current = ResidentTestFixtures.level(ResidentTestFixtures.OVERWORLD);
        try (Scope scope = new Scope(current)) {
            ResidentLevels residents = scope.residents();
            ClientLevel nether = residents.open(ResidentTestFixtures.open(3, ResidentTestFixtures.NETHER, 12, -4));
            Entity mob = mock(Entity.class);
            when(mob.getId()).thenReturn(77);
            when(nether.entitiesForRendering()).thenReturn(List.of(mob));
            residents.close(new TravelMessage.RemoteLevelClose(3));
            assertFalse(residents.has(3));
            assertTrue(residents.resident(nether));
            assertEquals(0, residents.handle(nether));
            verify(nether).removeEntity(77, Entity.RemovalReason.DISCARDED);
            ResidentTestFixtures.loaded(nether, 14, -4);
            assertSame(nether, residents.open(ResidentTestFixtures.open(9, ResidentTestFixtures.NETHER, 14, -2)));
            assertEquals(1, scope.levels.constructed().size());
            assertEquals(9, residents.handle(nether));
            ClientLevel far = residents.open(ResidentTestFixtures.open(10, ResidentTestFixtures.NETHER, 400, 400));
            assertEquals(2, scope.levels.constructed().size());
            assertTrue(far != nether);
        }
    }

    @Test
    public void theLevelLeftByACrossingBecomesTheReturnRoutesResidentLevel() {
        ClientLevel current = ResidentTestFixtures.level(ResidentTestFixtures.OVERWORLD);
        try (Scope scope = new Scope(current)) {
            ResidentLevels residents = scope.residents();
            ClientLevel nether = residents.open(ResidentTestFixtures.open(3, ResidentTestFixtures.NETHER, 12, -4));
            residents.crossing(current);
            scope.minecraft.level = nether;
            assertTrue(residents.muted(current));
            residents.crossing(null);
            residents.retire(current);
            assertTrue(residents.resident(current));
            assertFalse(residents.muted(nether));
            ResidentTestFixtures.loaded(current, 1, 1);
            assertSame(current, residents.open(ResidentTestFixtures.open(5, ResidentTestFixtures.OVERWORLD, 1, 2)));
            assertEquals(1, scope.levels.constructed().size());
            assertEquals(5, residents.handle(current));
            residents.close(new TravelMessage.RemoteLevelClose(3));
            assertFalse(residents.resident(nether));
            verify(nether, never()).removeEntity(anyInt(), any());
        }
    }

    @Test
    public void afterACrossingTheHandleReopensForTheLevelJustLeft() {
        for (boolean sameDimension : new boolean[]{false, true}) {
            ClientLevel current = ResidentTestFixtures.level(ResidentTestFixtures.OVERWORLD);
            try (Scope scope = new Scope(current)) {
                ResidentLevels residents = scope.residents();
                TravelMessage.TravelWorld world = sameDimension ? ResidentTestFixtures.OVERWORLD : ResidentTestFixtures.NETHER;
                ClientLevel destination = residents.open(ResidentTestFixtures.open(3, world, 300, 300));
                residents.crossing(current);
                scope.minecraft.level = destination;
                residents.crossing(null);
                residents.retire(current);
                ResidentTestFixtures.loaded(current, 1, 1);
                assertSame(current, residents.open(ResidentTestFixtures.open(3, ResidentTestFixtures.OVERWORLD, 1, 2)));
                assertEquals(3, residents.handle(current));
                assertEquals(0, residents.handle(destination));
                assertSame(current, residents.level(3));
                assertEquals(1, scope.levels.constructed().size());
                residents.close(new TravelMessage.RemoteLevelClose(3));
                assertEquals(0, residents.handle(current));
                assertTrue(residents.resident(current));
            }
        }
    }

    @Test
    public void reusedLevelsTakeTheServerWeatherFromTheOpen() {
        ClientLevel current = ResidentTestFixtures.level(ResidentTestFixtures.OVERWORLD);
        try (Scope scope = new Scope(current)) {
            ResidentLevels residents = scope.residents();
            ClientLevel nether = residents.open(ResidentTestFixtures.open(3, ResidentTestFixtures.NETHER, 12, -4));
            residents.crossing(current);
            scope.minecraft.level = nether;
            residents.crossing(null);
            residents.retire(current);
            ResidentTestFixtures.loaded(current, 1, 1);
            assertSame(current, residents.open(weather(5, ResidentTestFixtures.OVERWORLD, 1, 2, 0.0F, 0.0F)));
            verify(current).setRainLevel(0.0F);
            verify(current).setThunderLevel(0.0F);
            assertSame(current, residents.open(weather(5, ResidentTestFixtures.OVERWORLD, 1, 3, 0.6F, 0.2F)));
            verify(current).setRainLevel(0.6F);
            verify(current).setThunderLevel(0.2F);
        }
    }

    @Test
    public void rejectedCrossingLeavesTheSourceUnretired() {
        ClientLevel current = ResidentTestFixtures.level(ResidentTestFixtures.OVERWORLD);
        try (Scope scope = new Scope(current)) {
            ResidentLevels residents = scope.residents();
            residents.open(ResidentTestFixtures.open(3, ResidentTestFixtures.NETHER, 12, -4));
            residents.crossing(current);
            assertSame(current, residents.crossingSource());
            residents.crossing(null);
            assertNull(residents.crossingSource());
            assertFalse(residents.resident(current));
        }
    }

    @Test
    public void aDifferentWorldOnABoundHandleReplacesTheOldBinding() {
        ClientLevel current = ResidentTestFixtures.level(ResidentTestFixtures.OVERWORLD);
        try (Scope scope = new Scope(current)) {
            ResidentLevels residents = scope.residents();
            ClientLevel nether = residents.open(ResidentTestFixtures.open(3, ResidentTestFixtures.NETHER, 12, -4));
            ClientLevel overworld = residents.open(ResidentTestFixtures.open(3, ResidentTestFixtures.OVERWORLD, 300, 300));
            assertTrue(nether != overworld);
            assertEquals(0, residents.handle(nether));
            assertEquals(3, residents.handle(overworld));
            assertTrue(residents.resident(nether));
        }
    }

    @Test
    public void clearReleasesEveryResidentLevelExceptTheKeptOne() {
        ClientLevel current = ResidentTestFixtures.level(ResidentTestFixtures.OVERWORLD);
        try (Scope scope = new Scope(current)) {
            ResidentLevels residents = scope.residents();
            ClientLevel nether = residents.open(ResidentTestFixtures.open(3, ResidentTestFixtures.NETHER, 12, -4));
            ClientLevel far = residents.open(ResidentTestFixtures.open(4, ResidentTestFixtures.OVERWORLD, 300, 300));
            residents.clear(far);
            assertFalse(residents.has(3));
            assertFalse(residents.resident(far));
            scope.terrain.verify(() -> ClientSodiumTerrain.forget(nether));
            scope.terrain.verify(() -> ClientSodiumTerrain.forget(far), never());
        }
    }

    @Test
    public void joiningAConnectionDropsResidentLevelsOfThePreviousOne() throws IOException {
        ClientLevel current = ResidentTestFixtures.level(ResidentTestFixtures.OVERWORLD);
        try (Scope scope = new Scope(current)) {
            WormholesClient client = WormholesClient.initialize(folder.newFolder().toPath(), bytes -> { });
            ResidentLevels residents = client.seamlessTravel().residents();
            ClientLevel nether = residents.open(ResidentTestFixtures.open(1, ResidentTestFixtures.NETHER, 0, 13));
            client.connected();
            assertFalse(residents.has(1));
            assertFalse(residents.resident(nether));
            ClientLevel reopened = residents.open(ResidentTestFixtures.open(1, ResidentTestFixtures.NETHER, 0, 13));
            assertFalse(reopened == nether);
        }
    }

    private static TravelMessage.RemoteLevelOpen weather(int handle, TravelMessage.TravelWorld world, int x, int z, float rain, float thunder) {
        TravelMessage.RemoteLevelOpen open = ResidentTestFixtures.open(handle, world, x, z);
        EnvironmentState environment = open.environment();
        EnvironmentState.Sky sky = environment.sky();
        EnvironmentState.Sky weather = new EnvironmentState.Sky(sky.skybox(), sky.sunAngle(), sky.moonAngle(), sky.starAngle(), sky.starBrightness(),
            sky.sunrise(), sky.color(), sky.moonPhase(), rain, thunder);
        return new TravelMessage.RemoteLevelOpen(handle, world, new EnvironmentState(environment.gameTime(), weather, environment.fog(),
            environment.lighting(), environment.clouds(), environment.transform(), environment.dimension(), environment.world(), environment.scale()),
            open.viewRadius(), open.center());
    }

    static final class Scope implements AutoCloseable {
        final Minecraft minecraft;
        final ClientPacketListener connection;
        final List<TravelMessage> sent = new ArrayList<>();
        final MockedStatic<Minecraft> access;
        final MockedStatic<ClientSodiumTerrain> terrain;
        final MockedStatic<ClientWorldLoader> worlds;
        final MockedConstruction<ClientLevel> levels;
        final MockedConstruction<PreparedLevelExtractor> extractors;

        Scope(ClientLevel current) {
            connection = ResidentTestFixtures.connection(current);
            RegistryAccess.Frozen registries = ResidentTestFixtures.dimensionTypes(ResidentTestFixtures.OVERWORLD, ResidentTestFixtures.NETHER);
            when(connection.registryAccess()).thenReturn(registries);
            minecraft = ResidentTestFixtures.minecraft(current, connection);
            access = mockStatic(Minecraft.class);
            access.when(Minecraft::getInstance).thenReturn(minecraft);
            terrain = mockStatic(ClientSodiumTerrain.class);
            worlds = mockStatic(ClientWorldLoader.class);
            worlds.when(() -> ClientWorldLoader.withWorldRenderer(any(), any())).thenAnswer(call -> {
                call.<Runnable>getArgument(1).run();
                return null;
            });
            extractors = mockConstruction(PreparedLevelExtractor.class);
            levels = mockConstruction(ClientLevel.class, (level, context) -> {
                ClientChunkCache cache = mock(ClientChunkCache.class, withSettings().extraInterfaces(PreparedChunkColumns.class));
                when(((PreparedChunkColumns) cache).wormholes$columns()).thenReturn(new AtomicReferenceArray<>(0));
                when(level.getChunkSource()).thenReturn(cache);
                when(level.entitiesForRendering()).thenReturn(List.of());
                when(level.dimension()).thenReturn(dimension(context.arguments().get(2)));
            });
        }

        ResidentLevels residents() {
            return new ResidentLevels(sent::add, 512L << 20);
        }

        @Override
        public void close() {
            levels.close();
            extractors.close();
            worlds.close();
            terrain.close();
            access.close();
        }

        @SuppressWarnings("unchecked")
        private static ResourceKey<Level> dimension(Object argument) {
            return (ResourceKey<Level>) argument;
        }
    }
}
