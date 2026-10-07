package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.client.render.ClientPortalRenderer;
import art.arcane.wormholes.modded.client.render.PortalIrisMainPipelines;
import art.arcane.wormholes.modded.mixin.client.PreparedPacketAccess;
import art.arcane.wormholes.network.client.TravelMessage;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.UUID;

import static art.arcane.wormholes.modded.client.ClientTravelTestFixtures.set;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ClientPreparedTravelSeamlessAcceptTest extends MinecraftTestBase {
    @Test
    public void acceptWithinToleranceAdoptsTheResidentLevelWithoutARespawn() throws ReflectiveOperationException {
        Crossing crossing = new Crossing();
        try (crossing) {
            crossing.travel.receive(SeamlessTravelFixtures.accept(0.0004D, 0.2F));
            assertTrue(crossing.travel.adopted());
            assertTrue(crossing.travel.positionConfirmed());
            assertFalse(crossing.travel.pendingCrossing());
            assertNull(crossing.residents.crossingSource());
            assertTrue(crossing.residents.resident(crossing.source));
            assertSame(crossing.nether, crossing.scope.minecraft.level);
            assertSame(crossing.nether, crossing.scope.connection.getLevel());
            verify(crossing.scope.minecraft, never()).setLevel(any());
            assertPosition(crossing.player, 100.5004D);
            verify(crossing.player).setYRot(185.0F);
            ArgumentCaptor<Vec3> velocity = ArgumentCaptor.forClass(Vec3.class);
            verify(crossing.player).setDeltaMovement(velocity.capture());
            assertEquals(-0.25D, velocity.getValue().z, 0.000001D);
            verify(crossing.client).dropProjectedEntities(crossing.begin.sourceGeometry());
            assertTrue(crossing.scope.sent.stream().noneMatch(TravelMessage.TravelCancel.class::isInstance));
        }
    }

    @Test
    public void acceptOutsideToleranceCorrectsToTheServerPose() throws ReflectiveOperationException {
        Crossing crossing = new Crossing();
        try (crossing) {
            crossing.travel.receive(SeamlessTravelFixtures.accept(0.5D, 10.0F));
            assertTrue(crossing.travel.positionConfirmed());
            assertFalse(crossing.travel.pendingCrossing());
            assertPosition(crossing.player, 101.0D);
            verify(crossing.player).setYRot(195.0F);
            assertSame(crossing.nether, crossing.scope.minecraft.level);
            assertTrue(crossing.scope.sent.stream().noneMatch(TravelMessage.TravelCancel.class::isInstance));
        }
    }

    @Test
    public void foreignOrStaleAcceptsLeaveThePredictionPending() throws ReflectiveOperationException {
        Crossing crossing = new Crossing();
        try (crossing) {
            TravelMessage.TravelAccept valid = SeamlessTravelFixtures.accept(0.0D, 0.0F);
            crossing.travel.receive(new TravelMessage.TravelAccept(new UUID(9, 9), valid.generation(), valid.contentRevision(), valid.pose(),
                valid.velocity(), valid.levelHandle(), valid.dimensionChanged(), valid.serverTick()));
            crossing.travel.receive(new TravelMessage.TravelAccept(valid.token(), valid.generation(), valid.contentRevision() + 1, valid.pose(),
                valid.velocity(), valid.levelHandle(), valid.dimensionChanged(), valid.serverTick()));
            assertTrue(crossing.travel.pendingCrossing());
            assertFalse(crossing.travel.positionConfirmed());
            assertSame(crossing.source, crossing.residents.crossingSource());
        }
    }

    @Test
    public void cancelDuringThePendingWindowRestoresTheSourceLevel() throws ReflectiveOperationException {
        Crossing crossing = new Crossing();
        try (crossing) {
            crossing.travel.receive(new TravelMessage.TravelCancel(SeamlessTravelFixtures.TOKEN, SeamlessTravelFixtures.GENERATION));
            assertFalse(crossing.travel.pendingCrossing());
            assertNull(crossing.residents.crossingSource());
            assertFalse(crossing.residents.resident(crossing.source));
            assertSame(crossing.source, crossing.scope.connection.getLevel());
            verify(crossing.nether).removeEntity(42, Entity.RemovalReason.CHANGED_DIMENSION);
            verify(crossing.source).addEntity(crossing.player);
            verify((PreparedChunkColumns) crossing.source.getChunkSource()).wormholes$announceColumns();
        }
    }

    private static void assertPosition(LocalPlayer player, double x) {
        ArgumentCaptor<Vec3> position = ArgumentCaptor.forClass(Vec3.class);
        verify(player).setPos(position.capture());
        assertEquals(x, position.getValue().x, 0.000001D);
        assertEquals(64.0D, position.getValue().y, 0.000001D);
        assertEquals(99.0D, position.getValue().z, 0.000001D);
    }

    static final class Crossing implements AutoCloseable {
        final ClientLevel source = ResidentTestFixtures.level(ResidentTestFixtures.OVERWORLD);
        final ResidentLevelsOpenCloseTest.Scope scope = new ResidentLevelsOpenCloseTest.Scope(source);
        final ResidentLevels residents = new ResidentLevels(scope.sent::add, 512L << 20);
        final ClientPreparedTravel travel = new ClientPreparedTravel(scope.sent::add, residents);
        final TravelMessage.TravelBegin begin = SeamlessTravelFixtures.begin(true, true);
        final LocalPlayer player = SeamlessTravelFixtures.player();
        final MockedStatic<PortalIrisMainPipelines> shaders = mockStatic(PortalIrisMainPipelines.class);
        final WormholesClient client = mock(WormholesClient.class);
        final MockedStatic<WormholesClient> clients = mockStatic(WormholesClient.class);
        final MockedStatic<ClientPortalRenderer> renderers = mockStatic(ClientPortalRenderer.class);
        final ClientLevel nether;

        Crossing() throws ReflectiveOperationException {
            when(client.preparedTravel()).thenReturn(travel);
            ClientViewSession session = mock(ClientViewSession.class);
            when(session.has(any())).thenReturn(true);
            when(client.session()).thenReturn(session);
            clients.when(WormholesClient::instance).thenReturn(client);
            renderers.when(ClientPortalRenderer::instance).thenReturn(mock(ClientPortalRenderer.class));
            when(player.getId()).thenReturn(42);
            scope.minecraft.player = player;
            nether = ResidentTestFixtures.level(ResidentTestFixtures.NETHER);
            ResidentTestFixtures.loaded(nether, 6, 6);
            residents.retire(nether);
            assertSame(nether, residents.open(ResidentTestFixtures.open(3, ResidentTestFixtures.NETHER, 6, 6)));
            set(travel, "begin", begin);
            set(travel, "chunks", SeamlessTravelFixtures.chunks(begin));
            set(travel, "staged", nether);
            set(travel, "deadline", System.currentTimeMillis() + 60_000L);
            set(travel, "prediction", SeamlessTravelFixtures.prediction(source, scope.connection, true));
            residents.beginCrossing(source);
            scope.minecraft.level = nether;
            ((PreparedPacketAccess) scope.connection).wormholes$level(nether);
            assertTrue(travel.pendingCrossing());
            assertFalse(travel.suppressesMovement());
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
