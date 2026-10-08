package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.client.render.PortalEnvironmentTest;
import art.arcane.optics.frame.OpticTransform;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.HashMap;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

import static art.arcane.wormholes.modded.client.ClientTravelTestFixtures.field;
import static art.arcane.wormholes.modded.client.ClientTravelTestFixtures.set;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import art.arcane.wormholes.network.client.TravelMessage;

public class ClientPreparedTravelPendingTest {
    private static final TravelMessage.TravelCoordinate COLUMN = new TravelMessage.TravelCoordinate(0, 0);

    @Test
    public void nextBeginPreservesAdoptedArrivalUntilActualMainFrameAndKeepsLatestDataBarrier() throws ReflectiveOperationException {
        ClientPreparedTravel travel = arrival();
        Object original = field(travel, "begin");
        TravelMessage.TravelBegin next = begin(4);
        assertTrue(defer(travel, next));
        Object pending = field(travel, "pendingPreparation");
        long deadline = (long) field(pending, "deadline");
        assertTrue(defer(travel, column(next, 1)));
        assertTrue(defer(travel, end(next, 1)));
        assertTrue(defer(travel, column(next, 2)));
        assertTrue(defer(travel, end(next, 2)));
        assertSame(original, field(travel, "begin"));
        assertTrue(travel.adopted());
        assertTrue(travel.positionConfirmed());
        assertEquals(37, travel.readyRevision());
        assertNull(next(travel));
        assertSame(pending, field(travel, "pendingPreparation"));
        assertFalse((boolean) field(travel, "mainCompiled"));
        assertNull(next(travel));
        set(travel, "mainCompiled", true);
        assertSame(pending, next(travel));
        assertEquals(deadline, field(pending, "deadline"));
        assertEquals(2, ((ClientTravelChunks) field(pending, "chunks")).completeRevision());
        Map<?, ?> columns = (Map<?, ?>) field(pending, "columns");
        assertEquals(1, columns.size());
        assertEquals(2, field(columns.get(COLUMN), "revision"));
        assertNull(field(travel, "pendingPreparation"));
    }

    @Test
    public void pendingCancelReplacementAndExpiryNeverCancelTheAdoptedArrival() throws ReflectiveOperationException {
        ClientPreparedTravel travel = arrival();
        Object original = field(travel, "begin");
        TravelMessage.TravelBegin first = begin(4);
        TravelMessage.TravelBegin second = begin(5);
        defer(travel, first);
        Object pending = field(travel, "pendingPreparation");
        defer(travel, first);
        assertSame(pending, field(travel, "pendingPreparation"));
        defer(travel, second);
        assertFalse(defer(travel, column(first, 1)));
        assertTrue(defer(travel, new TravelMessage.TravelCancel(second.token(), second.generation())));
        assertNull(field(travel, "pendingPreparation"));
        defer(travel, first);
        pending = field(travel, "pendingPreparation");
        set(pending, "deadline", 1L);
        set(travel, "mainCompiled", true);
        assertNull(next(travel));
        assertSame(original, field(travel, "begin"));
        assertTrue(travel.adopted());
        assertTrue(travel.positionConfirmed());
    }

    @Test
    public void invalidPendingBarrierDropsOnlyUpcomingPreparation() throws ReflectiveOperationException {
        ClientPreparedTravel travel = arrival();
        Object original = field(travel, "begin");
        TravelMessage.TravelBegin next = begin(4);
        defer(travel, next);
        assertTrue(defer(travel, new TravelMessage.TravelEnd(next.token(), next.generation(), 1,
            List.of(new TravelMessage.TravelChunkRevision(1, 0, 1)))));
        assertNull(field(travel, "pendingPreparation"));
        assertSame(original, field(travel, "begin"));
        assertTrue(travel.adopted());
        assertEquals(37, travel.readyRevision());
    }

    @Test
    public void adoptingPendingPreparationQueuesOnlyColumnsNewerThanInstalledRevisions() throws ReflectiveOperationException {
        for (int installed : new int[] {1, 2, 3}) {
            ClientPreparedTravel travel = arrival();
            TravelMessage.TravelBegin begin = begin(4);
            assertTrue(defer(travel, begin));
            assertTrue(defer(travel, column(begin, 2)));
            Object pending = field(travel, "pendingPreparation");
            set(pending, "decoded", new HashMap<>(Map.of(COLUMN, installed)));
            set(pending, "drawnRevision", 19L);
            ClientTravelChunks chunks = (ClientTravelChunks) field(pending, "chunks");
            long deadline = (long) field(pending, "deadline");
            Method adopt = ClientPreparedTravel.class.getDeclaredMethod("adoptPreparation", pending.getClass());
            adopt.setAccessible(true);
            adopt.invoke(travel, pending);
            assertSame(begin, field(travel, "begin"));
            assertSame(chunks, field(travel, "chunks"));
            assertEquals(deadline, field(travel, "deadline"));
            assertEquals(19L, field(travel, "drawnRevision"));
            assertEquals(installed, ((Map<?, ?>) field(travel, "decoded")).get(COLUMN));
            Collection<?> queued = (Collection<?>) field(travel, "decoding");
            assertEquals(installed < 2 ? 1 : 0, queued.size());
            if (!queued.isEmpty()) {
                assertEquals(2, field(queued.iterator().next(), "revision"));
            }
        }
    }

    private static ClientPreparedTravel arrival() throws ReflectiveOperationException {
        ClientPreparedTravel travel = ClientTravelTestFixtures.travel(ignored -> { });
        TravelMessage.TravelBegin active = mock(TravelMessage.TravelBegin.class);
        when(active.world()).thenReturn(new TravelMessage.TravelWorld("minecraft:the_nether", "minecraft:the_nether",
            7, false, false, 32, 0, 256));
        set(travel, "begin", active);
        set(travel, "adopted", true);
        set(travel, "positionConfirmed", true);
        set(travel, "acknowledgedRevision", 37L);
        return travel;
    }

    private static TravelMessage.TravelBegin begin(long nonce) {
        return new TravelMessage.TravelBegin(new UUID(3, nonce), nonce, new UUID(2, 9), "minecraft:the_nether",
            ClientTravelTestFixtures.geometry(), OpticTransform.IDENTITY, 1.0F, new TravelMessage.TravelWorld("minecraft:overworld", "minecraft:overworld", 7, false, false, 63, -64, 384),
            new TravelMessage.TravelPose(0, 80, 0, 0, 0), List.of(COLUMN),
            PortalEnvironmentTest.environment(OpticTransform.IDENTITY), 30_000, TravelMessage.ArrivalRules.FRAME, false, 0);
    }

    private static TravelMessage.TravelChunk column(TravelMessage.TravelBegin begin, int revision) {
        return new TravelMessage.TravelChunk(begin.token(), begin.generation(), 0, 0, revision, 0, 1, 1, new byte[]{(byte) revision});
    }

    private static TravelMessage.TravelEnd end(TravelMessage.TravelBegin begin, int revision) {
        return new TravelMessage.TravelEnd(begin.token(), begin.generation(), revision,
            List.of(new TravelMessage.TravelChunkRevision(0, 0, revision)));
    }

    private static boolean defer(ClientPreparedTravel travel, TravelMessage message) throws ReflectiveOperationException {
        Method method = ClientPreparedTravel.class.getDeclaredMethod("deferPreparation", TravelMessage.class);
        method.setAccessible(true);
        return (boolean) method.invoke(travel, message);
    }

    private static Object next(ClientPreparedTravel travel) throws ReflectiveOperationException {
        Method method = ClientPreparedTravel.class.getDeclaredMethod("nextPreparation");
        method.setAccessible(true);
        return method.invoke(travel);
    }
}
