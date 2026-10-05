package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.client.render.PortalEnvironmentTest;
import art.arcane.wormholes.network.client.ClientViewEnvironment;
import art.arcane.wormholes.network.client.ClientViewMessage;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ClientPreparedTravelPendingTest {
    private static final ClientViewMessage.TravelCoordinate COLUMN = new ClientViewMessage.TravelCoordinate(0, 0);

    @Test
    public void nextBeginPreservesAdoptedArrivalUntilActualMainFrameAndKeepsLatestDataBarrier() throws ReflectiveOperationException {
        ClientPreparedTravel travel = arrival();
        Object original = field(travel, "begin");
        ClientViewMessage.TravelBegin next = begin(4);
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
        ClientViewMessage.TravelBegin first = begin(4);
        ClientViewMessage.TravelBegin second = begin(5);
        defer(travel, first);
        Object pending = field(travel, "pendingPreparation");
        defer(travel, first);
        assertSame(pending, field(travel, "pendingPreparation"));
        defer(travel, second);
        assertFalse(defer(travel, column(first, 1)));
        assertTrue(defer(travel, new ClientViewMessage.TravelCancel(second.token(), second.generation())));
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
        ClientViewMessage.TravelBegin next = begin(4);
        defer(travel, next);
        assertTrue(defer(travel, new ClientViewMessage.TravelEnd(next.token(), next.generation(), 1,
            List.of(new ClientViewMessage.TravelChunkRevision(1, 0, 1)))));
        assertNull(field(travel, "pendingPreparation"));
        assertSame(original, field(travel, "begin"));
        assertTrue(travel.adopted());
        assertEquals(37, travel.readyRevision());
    }

    private static ClientPreparedTravel arrival() throws ReflectiveOperationException {
        ClientPreparedTravel travel = new ClientPreparedTravel(ignored -> { });
        ClientViewMessage.TravelBegin active = mock(ClientViewMessage.TravelBegin.class);
        when(active.world()).thenReturn(new ClientViewMessage.TravelWorld("minecraft:the_nether", "minecraft:the_nether",
            7, false, false, 32, 0, 256));
        set(travel, "begin", active);
        set(travel, "adopted", true);
        set(travel, "positionConfirmed", true);
        set(travel, "acknowledgedRevision", 37L);
        return travel;
    }

    private static ClientViewMessage.TravelBegin begin(long nonce) {
        return new ClientViewMessage.TravelBegin(new UUID(3, nonce), nonce, new UUID(2, 9), "minecraft:the_nether",
            ClientTravelTestFixtures.geometry(), ClientViewEnvironment.Transform.IDENTITY, new ClientViewMessage.TravelWorld("minecraft:overworld", "minecraft:overworld", 7, false, false, 63, -64, 384),
            new ClientViewMessage.TravelPose(0, 80, 0, 0, 0), List.of(COLUMN),
            PortalEnvironmentTest.environment(ClientViewEnvironment.Transform.IDENTITY), 30_000);
    }

    private static ClientViewMessage.TravelChunk column(ClientViewMessage.TravelBegin begin, int revision) {
        return new ClientViewMessage.TravelChunk(begin.token(), begin.generation(), 0, 0, revision, 0, 1, 1, new byte[]{(byte) revision});
    }

    private static ClientViewMessage.TravelEnd end(ClientViewMessage.TravelBegin begin, int revision) {
        return new ClientViewMessage.TravelEnd(begin.token(), begin.generation(), revision,
            List.of(new ClientViewMessage.TravelChunkRevision(0, 0, revision)));
    }

    private static boolean defer(ClientPreparedTravel travel, ClientViewMessage message) throws ReflectiveOperationException {
        Method method = ClientPreparedTravel.class.getDeclaredMethod("deferPreparation", ClientViewMessage.class);
        method.setAccessible(true);
        return (boolean) method.invoke(travel, message);
    }

    private static Object next(ClientPreparedTravel travel) throws ReflectiveOperationException {
        Method method = ClientPreparedTravel.class.getDeclaredMethod("nextPreparation");
        method.setAccessible(true);
        return method.invoke(travel);
    }

    private static Object field(Object object, String name) throws ReflectiveOperationException {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static void set(Object object, String name, Object value) throws ReflectiveOperationException {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }
}
