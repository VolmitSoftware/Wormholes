package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.mixin.client.PreparedPacketAccess;
import art.arcane.wormholes.modded.mixin.client.CrossingWorldPacketsMixin;
import art.arcane.wormholes.network.client.TravelMessage;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.world.level.ChunkPos;
import org.junit.Test;
import org.mockito.InOrder;
import org.mockito.MockedStatic;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ResidentLevelsRoutingTest extends MinecraftTestBase {
    @Test
    public void routedPacketAppliesToItsHandlesLevelInsideASwappedScopeAndIsAcknowledged() {
        ClientLevel current = ResidentTestFixtures.level(ResidentTestFixtures.OVERWORLD);
        try (ResidentLevelsOpenCloseTest.Scope scope = new ResidentLevelsOpenCloseTest.Scope(current)) {
            ResidentLevels residents = new ResidentLevels(scope.sent::add, 512L << 20);
            ClientLevel nether = residents.open(ResidentTestFixtures.open(3, ResidentTestFixtures.NETHER, 12, -4));
            AtomicReference<ClientLevel> minecraftLevel = new AtomicReference<>();
            AtomicReference<ClientLevel> listenerLevel = new AtomicReference<>();
            AtomicReference<ClientLevel> active = new AtomicReference<>();
            doAnswer(call -> {
                minecraftLevel.set(scope.minecraft.level);
                listenerLevel.set(scope.connection.getLevel());
                active.set(residents.activeLevel());
                return null;
            }).when(scope.connection).handleForgetLevelChunk(any());
            for (TravelMessage.RoutedPacket fragment : ResidentTestFixtures.routed(3, 0, new ClientboundForgetLevelChunkPacket(new ChunkPos(5, 6)))) {
                residents.route(fragment);
            }
            assertSame(nether, minecraftLevel.get());
            assertSame(nether, listenerLevel.get());
            assertSame(current, active.get());
            assertSame(current, scope.minecraft.level);
            assertSame(current, scope.connection.getLevel());
            assertTrue(scope.sent.isEmpty());
            residents.tick();
            TravelMessage.RemoteViewAck ack = (TravelMessage.RemoteViewAck) scope.sent.getLast();
            assertEquals(3, ack.levelHandle());
            assertEquals(0, ack.lastSequence());
            assertTrue(ack.chunksPerTickHint() >= 1 && ack.chunksPerTickHint() <= TravelMessage.MAX_CHUNKS_PER_TICK_HINT);
            scope.sent.clear();
            residents.tick();
            assertTrue(scope.sent.isEmpty());
        }
    }

    @Test
    public void fragmentsReassembleAndApplyInSequenceOrderAndStaleSequencesAreIgnored() {
        ClientLevel current = ResidentTestFixtures.level(ResidentTestFixtures.OVERWORLD);
        try (ResidentLevelsOpenCloseTest.Scope scope = new ResidentLevelsOpenCloseTest.Scope(current)) {
            ResidentLevels residents = new ResidentLevels(scope.sent::add, 512L << 20);
            residents.open(ResidentTestFixtures.open(3, ResidentTestFixtures.NETHER, 12, -4));
            IntArrayList ids = new IntArrayList(40_000);
            for (int id = 1_000_000; id < 1_040_000; id++) {
                ids.add(id);
            }
            List<TravelMessage.RoutedPacket> large = ResidentTestFixtures.routed(3, 4, new ClientboundRemoveEntitiesPacket(ids));
            List<TravelMessage.RoutedPacket> small = ResidentTestFixtures.routed(3, 5, new ClientboundForgetLevelChunkPacket(new ChunkPos(1, 2)));
            assertTrue(large.size() > 1);
            residents.route(large.get(1));
            for (TravelMessage.RoutedPacket fragment : small) {
                residents.route(fragment);
            }
            verify(scope.connection, never()).handleForgetLevelChunk(any());
            residents.route(large.getFirst());
            for (int index = 2; index < large.size(); index++) {
                residents.route(large.get(index));
            }
            InOrder order = inOrder(scope.connection);
            order.verify(scope.connection).handleRemoveEntities(any());
            order.verify(scope.connection).handleForgetLevelChunk(any());
            for (TravelMessage.RoutedPacket fragment : small) {
                residents.route(fragment);
            }
            verify(scope.connection).handleForgetLevelChunk(any());
            residents.tick();
            assertEquals(5, ((TravelMessage.RemoteViewAck) scope.sent.getLast()).lastSequence());
        }
    }

    @Test
    public void aHandleReopenedForAnotherLevelRestartsItsSequence() {
        ClientLevel current = ResidentTestFixtures.level(ResidentTestFixtures.OVERWORLD);
        try (ResidentLevelsOpenCloseTest.Scope scope = new ResidentLevelsOpenCloseTest.Scope(current)) {
            ResidentLevels residents = new ResidentLevels(scope.sent::add, 512L << 20);
            ClientLevel nether = residents.open(ResidentTestFixtures.open(3, ResidentTestFixtures.NETHER, 12, -4));
            List<ClientLevel> applied = new ArrayList<>();
            doAnswer(call -> {
                applied.add(scope.connection.getLevel());
                return null;
            }).when(scope.connection).handleForgetLevelChunk(any());
            for (TravelMessage.RoutedPacket fragment : ResidentTestFixtures.routed(3, 40, new ClientboundForgetLevelChunkPacket(new ChunkPos(5, 6)))) {
                residents.route(fragment);
            }
            residents.crossing(current);
            scope.minecraft.level = nether;
            residents.crossing(null);
            residents.retire(current);
            ResidentTestFixtures.loaded(current, 1, 1);
            residents.open(ResidentTestFixtures.open(3, ResidentTestFixtures.OVERWORLD, 1, 2));
            for (TravelMessage.RoutedPacket fragment : ResidentTestFixtures.routed(3, 0, new ClientboundForgetLevelChunkPacket(new ChunkPos(1, 1)))) {
                residents.route(fragment);
            }
            assertEquals(List.of(nether, current), applied);
        }
    }

    @Test
    public void routedPacketsForUnopenedHandlesAskTheServerToReopenOnce() {
        ClientLevel current = ResidentTestFixtures.level(ResidentTestFixtures.OVERWORLD);
        try (ResidentLevelsOpenCloseTest.Scope scope = new ResidentLevelsOpenCloseTest.Scope(current)) {
            ResidentLevels residents = new ResidentLevels(scope.sent::add, 512L << 20);
            for (int repeat = 0; repeat < 2; repeat++) {
                for (TravelMessage.RoutedPacket fragment : ResidentTestFixtures.routed(8, repeat, new ClientboundForgetLevelChunkPacket(new ChunkPos(5, 6)))) {
                    residents.route(fragment);
                }
            }
            verify(scope.connection, never()).handleForgetLevelChunk(any());
            residents.tick();
            assertEquals(List.of(new TravelMessage.RemoteLevelReopen(8)), scope.sent);
            residents.open(ResidentTestFixtures.open(8, ResidentTestFixtures.NETHER, 5, 6));
            scope.sent.clear();
            residents.close(new TravelMessage.RemoteLevelClose(8));
            for (TravelMessage.RoutedPacket fragment : ResidentTestFixtures.routed(8, 2, new ClientboundForgetLevelChunkPacket(new ChunkPos(5, 6)))) {
                residents.route(fragment);
            }
            assertEquals(List.of(new TravelMessage.RemoteLevelReopen(8)), scope.sent);
        }
    }

    @Test
    public void pendingWindowRoutesUnwrappedWorldPacketsToThePreCrossLevelUntilAccept() throws ReflectiveOperationException {
        ClientLevel current = ResidentTestFixtures.level(ResidentTestFixtures.OVERWORLD);
        try (ResidentLevelsOpenCloseTest.Scope scope = new ResidentLevelsOpenCloseTest.Scope(current);
             MockedStatic<WormholesClient> clients = mockStatic(WormholesClient.class)) {
            ResidentLevels residents = new ResidentLevels(scope.sent::add, 512L << 20);
            ClientSeamlessTravel travel = new ClientSeamlessTravel(scope.sent::add, residents);
            WormholesClient client = mock(WormholesClient.class);
            when(client.seamlessTravel()).thenReturn(travel);
            clients.when(WormholesClient::instance).thenReturn(client);
            ClientLevel nether = residents.open(ResidentTestFixtures.open(3, ResidentTestFixtures.NETHER, 12, -4));
            residents.crossing(current);
            activate(scope, nether);
            List<ClientLevel> applied = new ArrayList<>();
            Operation<Void> original = recording(scope, applied);
            Packet<?> packet = new ClientboundForgetLevelChunkPacket(new ChunkPos(5, 6));
            dispatch(packet, original);
            assertSame(current, applied.getLast());
            assertSame(nether, scope.minecraft.level);
            assertSame(nether, scope.connection.getLevel());
            residents.withLevel(nether, () -> invoke(packet, original));
            assertSame(nether, applied.getLast());
            residents.crossing(null);
            residents.retire(current);
            assertNull(residents.redirectTarget());
            dispatch(packet, original);
            assertSame(nether, applied.getLast());
            assertTrue(residents.resident(current));
        }
    }

    @Test
    public void rejectedCrossingEndsTheWindowWithoutRetiringTheSource() throws ReflectiveOperationException {
        ClientLevel current = ResidentTestFixtures.level(ResidentTestFixtures.OVERWORLD);
        try (ResidentLevelsOpenCloseTest.Scope scope = new ResidentLevelsOpenCloseTest.Scope(current);
             MockedStatic<WormholesClient> clients = mockStatic(WormholesClient.class)) {
            ResidentLevels residents = new ResidentLevels(scope.sent::add, 512L << 20);
            ClientSeamlessTravel travel = new ClientSeamlessTravel(scope.sent::add, residents);
            WormholesClient client = mock(WormholesClient.class);
            when(client.seamlessTravel()).thenReturn(travel);
            clients.when(WormholesClient::instance).thenReturn(client);
            ClientLevel nether = residents.open(ResidentTestFixtures.open(3, ResidentTestFixtures.NETHER, 12, -4));
            residents.crossing(current);
            activate(scope, nether);
            residents.crossing(null);
            activate(scope, current);
            List<ClientLevel> applied = new ArrayList<>();
            dispatch(new ClientboundForgetLevelChunkPacket(new ChunkPos(5, 6)), recording(scope, applied));
            assertSame(current, applied.getLast());
            assertFalse(residents.resident(current));
            assertTrue(residents.resident(nether));
        }
    }

    private static void activate(ResidentLevelsOpenCloseTest.Scope scope, ClientLevel level) {
        scope.minecraft.level = level;
        ((PreparedPacketAccess) scope.connection).wormholes$level(level);
    }

    @SuppressWarnings("unchecked")
    private static Operation<Void> recording(ResidentLevelsOpenCloseTest.Scope scope, List<ClientLevel> applied) {
        Operation<Void> original = mock(Operation.class);
        doAnswer(call -> {
            applied.add(scope.connection.getLevel());
            assertSame(scope.connection.getLevel(), scope.minecraft.level);
            return null;
        }).when(original).call(any());
        return original;
    }

    private static void dispatch(Packet<?> packet, Operation<Void> original) throws ReflectiveOperationException {
        CrossingWorldPacketsMixin mixin = mock(CrossingWorldPacketsMixin.class, CALLS_REAL_METHODS);
        Method method = CrossingWorldPacketsMixin.class.getDeclaredMethod("wormholes$sourceWorld", Packet.class, Operation.class);
        method.setAccessible(true);
        method.invoke(mixin, packet, original);
    }

    private static void invoke(Packet<?> packet, Operation<Void> original) {
        try {
            dispatch(packet, original);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
