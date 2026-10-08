package art.arcane.wormholes.modded.client;

import art.arcane.optics.crossing.Pose;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.stream.EnvironmentState;
import art.arcane.optics.stream.ViewStreamCapability;
import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.mixin.client.ParticleEngineAccess;
import art.arcane.wormholes.modded.client.render.ClientPortalRenderer;
import art.arcane.wormholes.modded.client.render.ClientSodiumTerrain;
import art.arcane.wormholes.modded.client.render.PortalIrisMainPipelines;
import art.arcane.wormholes.modded.clientview.MinecraftPortalEnvironment;
import art.arcane.wormholes.modded.mixin.client.PreparedPacketAccess;
import art.arcane.wormholes.network.client.TravelMessage;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ClientSeamlessTravelTest extends MinecraftTestBase {
    @Test
    public void armsAreHeldPerSourcePortalUntilTheServerDisarmsThem() throws ReflectiveOperationException {
        try (Crossing crossing = new Crossing(false)) {
            assertTrue(crossing.travel.armed(crossing.begin.sourcePortal()));
            assertTrue(crossing.travel.receive(new TravelMessage.TravelCancel(SeamlessTravelFixtures.TOKEN, SeamlessTravelFixtures.GENERATION)));
            assertFalse(crossing.travel.armed(crossing.begin.sourcePortal()));
            assertFalse(crossing.travel.receive(new TravelMessage.TravelCancel(new UUID(3, 3), 2L)));
        }
    }

    @Test
    public void acceptWithinToleranceKeepsTheSwappedLevelThePredictedMomentumAndRetiresTheSource() throws ReflectiveOperationException {
        try (Crossing crossing = new Crossing(true)) {
            crossing.travel.receive(SeamlessTravelFixtures.accept(0.0004D, 0.2F));
            assertFalse(crossing.travel.pending());
            assertTrue(crossing.travel.armed());
            assertNull(crossing.residents.crossingSource());
            assertTrue(crossing.residents.resident(crossing.source));
            assertSame(crossing.nether, crossing.scope.minecraft.level);
            assertSame(crossing.nether, crossing.scope.connection.getLevel());
            verify(crossing.scope.minecraft, never()).setLevel(any());
            assertPosition(crossing.player, 100.5004D, 99.0D);
            verify(crossing.player).setYRot(185.0F);
            ArgumentCaptor<Vec3> velocity = ArgumentCaptor.forClass(Vec3.class);
            verify(crossing.player).setDeltaMovement(velocity.capture());
            assertEquals(-0.3D, velocity.getValue().z, 0.000001D);
            verify(crossing.client).dropProjectedEntities(crossing.begin.sourceGeometry());
            assertTrue(crossing.scope.sent.stream().noneMatch(TravelMessage.TravelCancel.class::isInstance));
        }
    }

    @Test
    public void acceptOutsideToleranceCorrectsToTheServerPose() throws ReflectiveOperationException {
        try (Crossing crossing = new Crossing(true)) {
            crossing.travel.receive(SeamlessTravelFixtures.accept(0.5D, 10.0F));
            assertFalse(crossing.travel.pending());
            assertPosition(crossing.player, 101.0D, 99.0D);
            verify(crossing.player).setYRot(195.0F);
            assertSame(crossing.nether, crossing.scope.minecraft.level);
        }
    }

    @Test
    public void cancelDuringThePendingWindowRestoresTheSourceLevelAndKeepsTheArm() throws ReflectiveOperationException {
        try (Crossing crossing = new Crossing(true)) {
            crossing.travel.receive(new TravelMessage.TravelCancel(SeamlessTravelFixtures.TOKEN, SeamlessTravelFixtures.GENERATION));
            assertFalse(crossing.travel.pending());
            assertTrue(crossing.travel.armed(crossing.begin.sourcePortal()));
            assertNull(crossing.residents.crossingSource());
            assertFalse(crossing.residents.resident(crossing.source));
            assertSame(crossing.source, crossing.scope.connection.getLevel());
            verify(crossing.nether).removeEntity(42, Entity.RemovalReason.CHANGED_DIMENSION);
            verify(crossing.source).addEntity(crossing.player);
            verify((PreparedChunkColumns) crossing.source.getChunkSource()).wormholes$announceColumns();
        }
    }

    @Test
    public void anUnclaimedServerCrossingSwapsIntoTheResidentLevelWithoutReloadingTheClient() throws ReflectiveOperationException {
        try (Crossing crossing = new Crossing(false)) {
            crossing.travel.receive(SeamlessTravelFixtures.accept(0.0D, 0.0F));
            verify(crossing.scope.minecraft, never()).setLevel(any());
            assertSame(crossing.nether, crossing.scope.minecraft.level);
            verify(crossing.scope.minecraft.levelExtractor).setLevel(crossing.nether);
            verify(crossing.scope.minecraft.particleEngine, never()).setLevel(any());
            verify((ParticleEngineAccess) crossing.scope.minecraft.particleEngine).wormholes$level(crossing.nether);
            verify(crossing.scope.minecraft.gameRenderer).setLevel(crossing.nether);
            verify(crossing.scope.minecraft.getSoundManager(), never()).stop();
            assertSame(crossing.nether, crossing.scope.connection.getLevel());
            assertTrue(crossing.residents.resident(crossing.source));
            verify(crossing.source).removeEntity(42, Entity.RemovalReason.CHANGED_DIMENSION);
            verify(crossing.nether).addEntity(crossing.player);
            assertPosition(crossing.player, SeamlessTravelFixtures.EXPECTED_ARRIVAL.x, SeamlessTravelFixtures.EXPECTED_ARRIVAL.z);
            verify(crossing.client).dropProjectedEntities(crossing.begin.sourceGeometry());
        }
    }

    @Test
    public void anUnclaimedServerCrossingIntoAnUnknownLevelAsksForItToBeReopened() throws ReflectiveOperationException {
        try (Crossing crossing = new Crossing(false)) {
            crossing.residents.close(new TravelMessage.RemoteLevelClose(3));
            crossing.travel.receive(SeamlessTravelFixtures.accept(0.0D, 0.0F));
            assertSame(crossing.source, crossing.scope.minecraft.level);
            assertTrue(crossing.scope.sent.contains(new TravelMessage.RemoteLevelReopen(3)));
        }
    }

    @Test
    public void armedResidentRoutesKeepWarmingOneReturnViewOfTheCurrentLevel() throws ReflectiveOperationException {
        try (Crossing crossing = new Crossing(false);
             MockedStatic<MinecraftPortalEnvironment> environments = mockStatic(MinecraftPortalEnvironment.class)) {
            EnvironmentState environment = ResidentTestFixtures.environment(ResidentTestFixtures.OVERWORLD);
            environments.when(() -> MinecraftPortalEnvironment.capture(any(), any(), any(), anyBoolean())).thenReturn(environment);
            when(crossing.player.getEyePosition()).thenReturn(new Vec3(100.5D, 65.62D, 99.0D));
            when(crossing.player.getBoundingBox()).thenReturn(new AABB(100.2D, 64.0D, 98.7D, 100.8D, 65.8D, 99.3D));
            crossing.travel.tick();
            crossing.travel.tick();
            environments.verify(() -> MinecraftPortalEnvironment.capture(any(), any(), any(), anyBoolean()), times(1));
            verify(crossing.renderer, times(2)).prepareTravelSourceEnvironment(environment);
            crossing.travel.receive(new TravelMessage.TravelCancel(SeamlessTravelFixtures.TOKEN, SeamlessTravelFixtures.GENERATION));
            crossing.travel.tick();
            verify(crossing.renderer).retireTravelSource();
        }
    }

    @Test
    public void preparationReportsWhatTheArmedDestinationStillNeeds() throws ReflectiveOperationException {
        try (Crossing crossing = new Crossing(false)) {
            crossing.scope.terrain.when(ClientSodiumTerrain::available).thenReturn(true);
            crossing.scope.terrain.when(() -> ClientSodiumTerrain.ready(crossing.nether)).thenReturn(false);
            assertEquals("destination terrain", crossing.travel.unprepared());
            crossing.scope.terrain.when(() -> ClientSodiumTerrain.ready(crossing.nether)).thenReturn(true);
            crossing.shaders.when(() -> PortalIrisMainPipelines.ready(crossing.nether)).thenReturn(false);
            assertEquals("destination shaders", crossing.travel.unprepared());
            crossing.shaders.when(() -> PortalIrisMainPipelines.ready(crossing.nether)).thenReturn(true);
            when(crossing.renderer.travelSourceShaderReady()).thenReturn(false);
            assertEquals("return view shaders", crossing.travel.unprepared());
            when(crossing.renderer.travelSourceShaderReady()).thenReturn(true);
            assertNull(crossing.travel.unprepared());
            crossing.travel.receive(new TravelMessage.TravelCancel(SeamlessTravelFixtures.TOKEN, SeamlessTravelFixtures.GENERATION));
            assertEquals("no armed destination", crossing.travel.unprepared());
        }
    }

    @Test
    public void terrainThatTheMainRendererCoversIsNeverAwaited() throws ReflectiveOperationException {
        try (Crossing crossing = new Crossing(false)) {
            crossing.scope.terrain.when(ClientSodiumTerrain::available).thenReturn(true);
            crossing.scope.terrain.when(() -> ClientSodiumTerrain.ready(crossing.nether)).thenReturn(false);
            crossing.scope.terrain.when(() -> ClientSodiumTerrain.covered(crossing.nether)).thenReturn(true);
            crossing.shaders.when(() -> PortalIrisMainPipelines.ready(crossing.nether)).thenReturn(true);
            when(crossing.renderer.travelSourceShaderReady()).thenReturn(true);
            assertNull(crossing.travel.unprepared());
            crossing.scope.terrain.when(() -> ClientSodiumTerrain.covered(crossing.nether)).thenReturn(false);
            assertEquals("destination terrain", crossing.travel.unprepared());
        }
    }

    private static void assertPosition(LocalPlayer player, double x, double z) {
        ArgumentCaptor<Vec3> position = ArgumentCaptor.forClass(Vec3.class);
        verify(player, atLeastOnce()).setPos(position.capture());
        assertEquals(x, position.getValue().x, 0.000001D);
        assertEquals(64.0D, position.getValue().y, 0.000001D);
        assertEquals(z, position.getValue().z, 0.000001D);
    }

    static final class Crossing implements AutoCloseable {
        final ClientLevel source = ResidentTestFixtures.level(ResidentTestFixtures.OVERWORLD);
        final ResidentLevelsOpenCloseTest.Scope scope = new ResidentLevelsOpenCloseTest.Scope(source);
        final ResidentLevels residents = new ResidentLevels(scope.sent::add, 512L << 20);
        final ClientSeamlessTravel travel = new ClientSeamlessTravel(scope.sent::add, residents);
        final TravelMessage.TravelBegin begin = SeamlessTravelFixtures.begin(true, true);
        final LocalPlayer player = SeamlessTravelFixtures.player();
        final MockedStatic<PortalIrisMainPipelines> shaders = mockStatic(PortalIrisMainPipelines.class);
        final WormholesClient client = mock(WormholesClient.class);
        final MockedStatic<WormholesClient> clients = mockStatic(WormholesClient.class);
        final MockedStatic<ClientPortalRenderer> renderers = mockStatic(ClientPortalRenderer.class);
        final ClientPortalRenderer renderer = mock(ClientPortalRenderer.class);
        final ClientLevel nether;

        Crossing(boolean predicted) throws ReflectiveOperationException {
            ClientViewSession session = mock(ClientViewSession.class);
            when(session.has(any(ViewStreamCapability.class))).thenReturn(true);
            when(session.has(anyLong())).thenReturn(true);
            when(client.session()).thenReturn(session);
            clients.when(WormholesClient::instance).thenReturn(client);
            renderers.when(ClientPortalRenderer::instance).thenReturn(renderer);
            when(player.getId()).thenReturn(42);
            scope.minecraft.player = player;
            nether = ResidentTestFixtures.level(ResidentTestFixtures.NETHER);
            ResidentTestFixtures.loaded(nether, 6, 6);
            residents.retire(nether);
            assertSame(nether, residents.open(ResidentTestFixtures.open(3, ResidentTestFixtures.NETHER, 6, 6)));
            assertTrue(travel.receive(begin));
            if (!predicted) {
                return;
            }
            pending().add(crossing(begin, source, nether));
            residents.crossing(source);
            scope.minecraft.level = nether;
            ((PreparedPacketAccess) scope.connection).wormholes$level(nether);
            assertTrue(travel.pending());
        }

        @SuppressWarnings("unchecked")
        private ArrayDeque<Object> pending() throws ReflectiveOperationException {
            Field field = ClientSeamlessTravel.class.getDeclaredField("pending");
            field.setAccessible(true);
            return (ArrayDeque<Object>) field.get(travel);
        }

        private static Object crossing(TravelMessage.TravelBegin begin, ClientLevel from, ClientLevel to) throws ReflectiveOperationException {
            Pose before = new Pose(new Vec3d(0.5, 0, 0.2), new Vec3d(0.5, 0, 0.5), new Vec3d(0.5, 0, 0.5), new Vec3d(0, 0, -0.3),
                180, 10, 178, 9, 179, 177, 181, 179);
            ClientTravelMotion.Carry carry = new ClientTravelMotion.Carry(180, 10, 178, 9, new Vec3d(0.5, 0, 0.2), new Vec3d(0.5, 0, 0.5));
            Class<?> type = Class.forName(ClientSeamlessTravel.class.getName() + "$Crossing");
            Constructor<?> constructor = type.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            return constructor.newInstance(begin, from, to, before, carry, SeamlessTravelFixtures.DESTINATION, SeamlessTravelFixtures.EXPECTED_ARRIVAL,
                System.currentTimeMillis() + 60_000L, null, 1.0D);
        }

        @Override
        public void close() {
            renderers.close();
            shaders.close();
            clients.close();
            scope.close();
        }
    }
}
