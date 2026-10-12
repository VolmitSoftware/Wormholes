package art.arcane.wormholes.modded.client;

import art.arcane.optics.crossing.Pose;
import art.arcane.optics.crossing.GravityFrame;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.stream.ViewStreamCapability;
import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.mixin.client.ParticleEngineAccess;
import art.arcane.wormholes.modded.client.render.ClientPortalRenderer;
import art.arcane.wormholes.modded.client.render.stencil.PortalViews;
import art.arcane.wormholes.modded.client.world.ClientWorldLoader;
import art.arcane.wormholes.modded.mixin.client.PreparedPacketAccess;
import art.arcane.wormholes.network.client.TravelMessage;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.UUID;

import static net.minecraft.world.entity.Pose.STANDING;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ClientSeamlessTravelTest extends MinecraftTestBase {
    @Test
    public void predictedCrossingNotifiesReturnOwnershipWithTheActualArmAndLevels() throws ReflectiveOperationException {
        try (Crossing crossing = new Crossing(false)) {
            double nearZ = crossing.begin.sourceGeometry().signedDistance(0.5D, 1.62D, 0.2D) > 0.0D ? 0.2D : 0.8D;
            double farZ = 1.0D - nearZ;
            when(crossing.scope.minecraft.getCameraEntity()).thenReturn(crossing.player);
            when(crossing.player.getEyePosition(0.0F)).thenReturn(new Vec3(0.5D, 1.62D, nearZ));
            when(crossing.player.getPosition(0.0F)).thenReturn(new Vec3(0.5D, 0.0D, nearZ));
            when(crossing.player.position()).thenReturn(new Vec3(0.5D, 0.0D, farZ));
            crossing.travel.afterTick();
            verify(crossing.portalViews, never()).crossed(any(), any(), any(), anyBoolean());
            when(crossing.player.getEyePosition(0.0F)).thenReturn(new Vec3(0.5D, 1.62D, farZ));
            when(crossing.player.getPosition(0.0F)).thenReturn(new Vec3(0.5D, 0.0D, farZ));
            when(crossing.player.getEyePosition()).thenReturn(new Vec3(0.5D, 1.62D, farZ));
            crossing.travel.afterTick();
            assertTrue(crossing.travel.pending());
            assertSame(crossing.nether, crossing.scope.minecraft.level);
            verify(crossing.portalViews).crossed(crossing.begin, crossing.source, crossing.nether, true);
        }
    }

    @Test
    public void anOlderConfirmationDoesNotReplaceTheLatestPredictedReturn() throws ReflectiveOperationException {
        try (Crossing crossing = new Crossing(true)) {
            TravelMessage.TravelBegin next = mock(TravelMessage.TravelBegin.class);
            crossing.pending().add(Crossing.crossing(next, crossing.nether, crossing.source));
            crossing.scope.minecraft.level = crossing.source;
            crossing.travel.receive(SeamlessTravelFixtures.accept(0.0D, 0.0F));
            assertTrue(crossing.travel.pending());
            assertSame(crossing.source, crossing.scope.minecraft.level);
            verify(crossing.portalViews, never()).crossed(any(), any(), any(), anyBoolean());
            verify(crossing.portalViews, never()).resetReturn(any());
        }
    }

    @Test
    public void serverCrossingWithoutAnArmCannotBorrowTheLastRenderedPortal() throws ReflectiveOperationException {
        try (Crossing crossing = new Crossing(false)) {
            crossing.travel.receive(new TravelMessage.TravelCancel(crossing.begin.token(), crossing.begin.generation()));
            crossing.travel.receive(SeamlessTravelFixtures.accept(0.0D, 0.0F));
            assertSame(crossing.nether, crossing.scope.minecraft.level);
            verify(crossing.portalViews).crossed(null, crossing.source, crossing.nether, true);
        }
    }

    @Test
    public void serverCrossingCarriesTheConfiguredRecursionChoice() throws ReflectiveOperationException {
        try (Crossing crossing = new Crossing(false)) {
            WormholesClientConfig config = new WormholesClientConfig();
            config.clientRecursion = false;
            when(crossing.client.config()).thenReturn(config);
            crossing.travel.receive(SeamlessTravelFixtures.accept(0.0D, 0.0F));
            verify(crossing.portalViews).crossed(crossing.begin, crossing.source, crossing.nether, false);
        }
    }

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
            crossing.scope.worlds.verify(ClientWorldLoader::forceFullSectionDiscovery);
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
            verify(crossing.portalViews).resetReturn(crossing.source);
            crossing.scope.worlds.verify(ClientWorldLoader::forceFullSectionDiscovery);
        }
    }

    @Test
    public void anUnclaimedServerCrossingSwapsIntoTheResidentLevelWithoutReloadingTheClient() throws ReflectiveOperationException {
        try (Crossing crossing = new Crossing(false)) {
            crossing.travel.receive(SeamlessTravelFixtures.accept(0.0D, 0.0F));
            verify(crossing.scope.minecraft, never()).setLevel(any());
            assertSame(crossing.nether, crossing.scope.minecraft.level);
            verify(crossing.scope.minecraft.levelExtractor, never()).setLevel(any());
            crossing.scope.worlds.verify(() -> ClientWorldLoader.changeLevel(same(crossing.nether), any()));
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
            verify(crossing.portalViews).crossed(crossing.begin, crossing.source, crossing.nether, true);
            crossing.scope.worlds.verify(ClientWorldLoader::forceFullSectionDiscovery);
        }
    }

    @Test
    public void anUnclaimedServerCrossingIntoAnUnknownLevelAsksForItToBeReopened() throws ReflectiveOperationException {
        try (Crossing crossing = new Crossing(false)) {
            crossing.residents.close(new TravelMessage.RemoteLevelClose(3));
            crossing.travel.receive(SeamlessTravelFixtures.accept(0.0D, 0.0F));
            assertSame(crossing.source, crossing.scope.minecraft.level);
            assertTrue(crossing.scope.sent.contains(new TravelMessage.RemoteLevelReopen(3)));
            verify(crossing.portalViews, never()).crossed(any(), any(), any(), anyBoolean());
            crossing.scope.worlds.verify(ClientWorldLoader::forceFullSectionDiscovery, never());
        }
    }

    @Test
    public void preparationReportsOnlyAMissingArmedDestinationLevel() throws ReflectiveOperationException {
        try (Crossing crossing = new Crossing(false)) {
            assertNull(crossing.travel.unprepared());
            crossing.travel.receive(new TravelMessage.TravelCancel(SeamlessTravelFixtures.TOKEN, SeamlessTravelFixtures.GENERATION));
            assertEquals("no armed destination", crossing.travel.unprepared());
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
        final TravelMessage.TravelBegin begin = SeamlessTravelFixtures.begin(true);
        final LocalPlayer player = SeamlessTravelFixtures.player();
        final WormholesClient client = mock(WormholesClient.class);
        final PortalViews portalViews = mock(PortalViews.class);
        final MockedStatic<WormholesClient> clients = mockStatic(WormholesClient.class);
        final MockedStatic<ClientPortalRenderer> renderers = mockStatic(ClientPortalRenderer.class);
        final ClientPortalRenderer renderer = mock(ClientPortalRenderer.class);
        final ClientLevel nether;

        Crossing(boolean predicted) throws ReflectiveOperationException {
            ClientViewSession session = mock(ClientViewSession.class);
            when(session.has(any(ViewStreamCapability.class))).thenReturn(true);
            when(session.has(anyLong())).thenReturn(true);
            when(client.session()).thenReturn(session);
            when(client.portalViews()).thenReturn(portalViews);
            clients.when(WormholesClient::instance).thenReturn(client);
            renderers.when(ClientPortalRenderer::instance).thenReturn(renderer);
            worldGravity(player);
            when(player.getPose()).thenReturn(STANDING);
            when(player.getDimensions(STANDING)).thenReturn(EntityDimensions.scalable(0.6F, 1.8F));
            when(player.getId()).thenReturn(42);
            when(player.getEyeHeight()).thenReturn(1.62F);
            when(player.getEyePosition()).thenReturn(new Vec3(100.5D, 65.62D, 99.0D));
            scope.minecraft.player = player;
            nether = ResidentTestFixtures.level(ResidentTestFixtures.NETHER);
            when(nether.noCollision(any(Entity.class), any(AABB.class))).thenReturn(true);
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
                System.currentTimeMillis() + 60_000L, 1.0D, GravityFrame.WORLD);
        }

        @Override
        public void close() {
            renderers.close();
            clients.close();
            scope.close();
        }
    }
}
