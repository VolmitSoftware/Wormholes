package art.arcane.wormholes.network.client;

import art.arcane.optics.math.Vec3d;

import art.arcane.wormholes.render.client.session.ClientPreparedTravelServer;
import art.arcane.optics.view.WorldChangeTracker;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.lang.reflect.Field;
import java.util.Collection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientPreparedTravelServerTest {
    @Test
    void readyRequiresEveryFragmentAndTheExactManifestBarrier() {
        ClientPreparedTravelServer tracker = new ClientPreparedTravelServer();
        TravelMessage.TravelBegin begin = begin();
        tracker.begin(begin, 0);
        List<TravelMessage> sent = new ArrayList<>();
        assertFalse(tracker.ready(new TravelMessage.TravelReady(begin.token(), begin.generation(), 1)));
        byte[] payload = new byte[TravelMessage.TRAVEL_FRAGMENT_BYTES + 17];
        for (TravelMessage.TravelCoordinate position : begin.chunks()) {
            assertTrue(tracker.column(position, 1, payload));
        }
        tracker.tick(1, TravelMessage.TRAVEL_FRAGMENT_BYTES, sent::add);
        assertInstanceOf(TravelMessage.TravelBegin.class, sent.getFirst());
        assertEquals(1, sent.stream().filter(TravelMessage.TravelChunk.class::isInstance).count());
        assertTrue(sent.stream().noneMatch(TravelMessage.TravelEnd.class::isInstance));
        for (int tick = 2; tick < 30; tick++) {
            tracker.tick(tick, TravelMessage.TRAVEL_FRAGMENT_BYTES * 2, sent::add);
        }
        TravelMessage.TravelEnd end = assertInstanceOf(TravelMessage.TravelEnd.class, sent.getLast());
        assertFalse(tracker.ready(new TravelMessage.TravelReady(UUID.randomUUID(), begin.generation(), end.contentRevision())));
        assertFalse(tracker.ready(new TravelMessage.TravelReady(begin.token(), begin.generation() + 1, end.contentRevision())));
        assertFalse(tracker.ready(new TravelMessage.TravelReady(begin.token(), begin.generation(), end.contentRevision() - 1)));
        assertTrue(tracker.ready(new TravelMessage.TravelReady(begin.token(), begin.generation(), end.contentRevision())));
        assertTrue(tracker.commit(commit(begin, new TravelMessage.TravelPose(1.25, 64, 2.5, 90, 12))).isPresent());
        assertTrue(tracker.preparing().isEmpty());
    }

    @Test
    void dirtyManifestColumnRevokesReadinessButRetainsUnchangedColumnsAndToken() {
        ClientPreparedTravelServer tracker = new ClientPreparedTravelServer();
        TravelMessage.TravelBegin begin = begin();
        tracker.begin(begin, 0);
        List<TravelMessage> sent = new ArrayList<>();
        for (TravelMessage.TravelCoordinate position : begin.chunks()) {
            tracker.column(position, 1, new byte[]{1});
        }
        tracker.tick(1, TravelMessage.TRAVEL_FRAGMENT_BYTES * 9, sent::add);
        TravelMessage.TravelEnd end = assertInstanceOf(TravelMessage.TravelEnd.class, sent.getLast());
        assertTrue(tracker.ready(new TravelMessage.TravelReady(begin.token(), begin.generation(), end.contentRevision())));
        TravelMessage.TravelCoordinate dirty = begin.chunks().getFirst();
        tracker.invalidate(dirty);
        assertTrue(tracker.ready(new TravelMessage.TravelReady(begin.token(), begin.generation(), end.contentRevision())));
        assertTrue(tracker.readyRoute(begin.sourcePortal(), 2));
        sent.clear();
        assertTrue(tracker.column(dirty, tracker.nextRevision(dirty), new byte[]{2}));
        tracker.tick(2, TravelMessage.TRAVEL_FRAGMENT_BYTES * 9, sent::add);
        assertEquals(2, sent.size());
        TravelMessage.TravelChunk chunk = assertInstanceOf(TravelMessage.TravelChunk.class, sent.getFirst());
        assertEquals(begin.token(), chunk.token());
        assertEquals(2, chunk.revision());
        TravelMessage.TravelEnd refreshed = assertInstanceOf(TravelMessage.TravelEnd.class, sent.getLast());
        assertTrue(tracker.ready(new TravelMessage.TravelReady(begin.token(), begin.generation(), refreshed.contentRevision())));
    }

    @Test
    void unrelatedRouteAndOutsideCoveredArrivalCannotConsumeAReadyPreparation() {
        ClientPreparedTravelServer tracker = new ClientPreparedTravelServer();
        TravelMessage.TravelBegin begin = begin();
        tracker.begin(begin, 0);
        List<TravelMessage> sent = new ArrayList<>();
        for (TravelMessage.TravelCoordinate position : begin.chunks()) {
            tracker.column(position, 1, new byte[]{1});
        }
        tracker.tick(1, TravelMessage.TRAVEL_FRAGMENT_BYTES * 9, sent::add);
        TravelMessage.TravelEnd end = assertInstanceOf(TravelMessage.TravelEnd.class, sent.getLast());
        tracker.ready(new TravelMessage.TravelReady(begin.token(), begin.generation(), end.contentRevision()));
        assertTrue(tracker.commit(new ClientPreparedTravelServer.Commit(UUID.randomUUID(), begin.sourceWorld(),
            begin.world().dimension(), begin.arrival(), new Vec3d(0, 0, 0), 2)).isEmpty());
        assertTrue(tracker.commit(new ClientPreparedTravelServer.Commit(begin.sourcePortal(), begin.world().dimension(),
            begin.sourceWorld(), begin.arrival(), new Vec3d(0, 0, 0), 2)).isEmpty());
        assertTrue(tracker.commit(commit(begin, new TravelMessage.TravelPose(17, 64, 0, 0, 0))).isEmpty());
        assertTrue(tracker.commit(commit(begin, begin.arrival())).isPresent());
    }

    @Test
    void expirationAndCancellationReleaseTheTransactionWithoutGameplayChanges() {
        ClientPreparedTravelServer tracker = new ClientPreparedTravelServer();
        TravelMessage.TravelBegin begin = begin();
        tracker.begin(begin, 0);
        List<TravelMessage> sent = new ArrayList<>();
        tracker.tick(begin.expiresMillis(), 0, sent::add);
        assertInstanceOf(TravelMessage.TravelCancel.class, sent.getFirst());
        assertTrue(tracker.preparing().isEmpty());
        tracker.begin(begin, 10);
        assertEquals(begin.token(), tracker.cancel().orElseThrow().token());
        assertTrue(tracker.cancel().isEmpty());
    }

    @Test
    void destinationWorldClearRevokesReadyBeforeTheOwningTickAndCancelsTheTransaction() throws Exception {
        ClientPreparedTravelServer tracker = new ClientPreparedTravelServer();
        WorldChangeTracker changes = new WorldChangeTracker();
        UUID world = UUID.randomUUID();
        TravelMessage.TravelBegin begin = begin();
        tracker.begin(begin, 0);
        tracker.watchWorld(changes, world);
        List<TravelMessage> sent = new ArrayList<>();
        for (TravelMessage.TravelCoordinate coordinate : begin.chunks()) {
            assertTrue(tracker.column(coordinate, 1, new byte[]{1}));
        }
        tracker.tick(1, TravelMessage.TRAVEL_FRAGMENT_BYTES * 9, sent::add);
        TravelMessage.TravelEnd end = assertInstanceOf(TravelMessage.TravelEnd.class, sent.getLast());
        TravelMessage.TravelReady ready = new TravelMessage.TravelReady(begin.token(), begin.generation(), end.contentRevision());
        assertTrue(tracker.ready(ready));
        changes.clearWorld(world);
        assertFalse(tracker.ready(ready));
        assertTrue(tracker.commit(commit(begin, begin.arrival())).isEmpty());
        assertFalse(tracker.needs(begin.chunks().getFirst()));
        assertFalse(tracker.column(begin.chunks().getFirst(), 2, new byte[]{2}));
        sent.clear();
        tracker.tick(2, TravelMessage.TRAVEL_FRAGMENT_BYTES, sent::add);
        assertEquals(List.of(new TravelMessage.TravelCancel(begin.token(), begin.generation())), sent);
        assertTrue(tracker.preparing().isEmpty());
        assertEquals(0, listenerCount(changes));
    }

    @Test
    void normalDirtyColumnsAndOtherWorldClearsDoNotRevokePreparationAndRebindingReleasesListeners() throws Exception {
        ClientPreparedTravelServer tracker = new ClientPreparedTravelServer();
        WorldChangeTracker changes = new WorldChangeTracker();
        WorldChangeTracker replacement = new WorldChangeTracker();
        UUID world = UUID.randomUUID();
        UUID nextWorld = UUID.randomUUID();
        TravelMessage.TravelBegin begin = begin();
        tracker.begin(begin, 0);
        tracker.watchWorld(changes, world);
        changes.markChanged(world, 0, 64, 0);
        changes.markChanged(world, 0, 0);
        changes.clearWorld(nextWorld);
        assertTrue(tracker.column(begin.chunks().getFirst(), 1, new byte[]{1}));
        tracker.begin(begin, 1);
        assertEquals(0, listenerCount(changes));
        tracker.watchWorld(replacement, nextWorld);
        changes.clearWorld(world);
        replacement.clearWorld(world);
        assertTrue(tracker.column(begin.chunks().getFirst(), 1, new byte[]{1}));
        tracker.cancel();
        assertEquals(0, listenerCount(replacement));
        tracker.begin(begin, 1);
        tracker.watchWorld(replacement, nextWorld);
        tracker.tick(begin.expiresMillis() + 1, 0, ignored -> true);
        assertEquals(0, listenerCount(replacement));
    }

    private static int listenerCount(WorldChangeTracker changes) throws ReflectiveOperationException {
        Field field = WorldChangeTracker.class.getDeclaredField("listeners");
        field.setAccessible(true);
        return ((Collection<?>) field.get(changes)).size();
    }

    private static ClientPreparedTravelServer.Commit commit(TravelMessage.TravelBegin begin, TravelMessage.TravelPose arrival) {
        return new ClientPreparedTravelServer.Commit(begin.sourcePortal(), begin.sourceWorld(), begin.world().dimension(), arrival, new Vec3d(0, 0, 0), 2);
    }

    private static TravelMessage.TravelBegin begin() {
        TravelMessage.TravelBegin fixture = ClientViewFixtures.travelBegin();
        List<TravelMessage.TravelCoordinate> coordinates = new ArrayList<>();
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                coordinates.add(new TravelMessage.TravelCoordinate(x, z));
            }
        }
        return new TravelMessage.TravelBegin(fixture.token(), fixture.generation(), fixture.sourcePortal(), fixture.sourceWorld(),
            fixture.sourceGeometry(), fixture.destinationToSource(), 1.0F, fixture.world(), new TravelMessage.TravelPose(0, 64, 0, 0, 0), coordinates, fixture.environment(), fixture.expiresMillis(), TravelMessage.ArrivalRules.FRAME, false, 0, false);
    }
}
