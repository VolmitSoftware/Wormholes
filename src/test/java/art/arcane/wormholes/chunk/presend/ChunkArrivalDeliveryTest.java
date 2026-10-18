package art.arcane.wormholes.chunk.presend;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkArrivalDeliveryTest {
    @Test
    void outstandingWritesRemainGloballyBoundedAcrossCancellation() {
        Fixture fixture = new Fixture();
        fixture.delayWrites = true;
        List<ChunkPreSendTicket<String, Fixture>> tickets = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            Fixture player = new Fixture();
            tickets.add(fixture.service.prepare(fixture.service.capture(player), "source", 320, 0));
        }
        fixture.x = 20;
        for (ChunkPreSendTicket<String, Fixture> ticket : tickets) {
            fixture.service.commit(ticket);
        }
        for (int index = 0; index < 200; index++) {
            fixture.next();
        }
        assertEquals(32, fixture.writes.size());
        for (ChunkPreSendTicket<String, Fixture> ticket : tickets) {
            fixture.service.cancel(ticket);
        }
        fixture.finish();
        ChunkPreSendTicket<String, Fixture> replacement = fixture.prepare("source");
        fixture.service.commit(replacement);
        assertEquals(32, fixture.writes.size());
        fixture.writes.getFirst().complete(true);
        while (fixture.writes.size() == 32) {
            fixture.next();
        }
        assertEquals(33, fixture.writes.size());
        fixture.service.shutdown();
        fixture.finish();
    }

    @Test
    void preparedLeaseLivesUntilWriteCompletionAndClosesOnce() {
        Fixture fixture = new Fixture();
        fixture.delayWrites = true;
        ChunkPreSendTicket<String, Fixture> ticket = fixture.prepare("source");
        fixture.x = 20;
        fixture.service.commit(ticket);
        assertEquals(0, fixture.closedPackets);
        fixture.writes.getFirst().complete(true);
        assertEquals(1, fixture.closedPackets);
        fixture.service.cancel(ticket);
        assertEquals(1, fixture.closedPackets);
    }

    @Test
    void failedWriteStopsContinuationAndReleasesPreparedResources() {
        Fixture fixture = new Fixture();
        fixture.delayWrites = true;
        ChunkPreSendTicket<String, Fixture> ticket = fixture.prepare("source");
        fixture.x = 20;
        fixture.service.commit(ticket);
        fixture.writes.getFirst().completeExceptionally(new IllegalStateException("connection closed"));
        fixture.finish();
        assertEquals(1, fixture.sent());
        assertEquals(1, fixture.closedPackets);
    }

    @Test
    void preparationPreservesSourceAndCrossWorldDeliveryStartsAfterCommit() {
        Fixture fixture = new Fixture();
        ChunkPreSendTicket<String, Fixture> ticket = fixture.prepare("destination");

        assertTrue(fixture.events.isEmpty());
        fixture.world = "destination";
        fixture.x = 20;
        assertTrue(fixture.service.commit(ticket));
        assertEquals(List.of("center:20,0", "capture:20,0", "send:20,0"), fixture.events);
        fixture.finish();
        assertEquals(9, fixture.sent());
    }

    @Test
    void approachCapturesLandingChunksWithoutMovingTheSourceWindow() {
        Fixture fixture = new Fixture();
        ChunkPreSendTicket<String, Fixture> ticket = fixture.prepare("destination");
        fixture.finish();
        assertEquals(4, fixture.events.size());
        assertTrue(fixture.events.stream().allMatch(event -> event.startsWith("capture:")));
        fixture.world = "destination";
        fixture.x = 20;
        fixture.service.commit(ticket);
        assertEquals(1, fixture.sent());
        assertEquals(4, fixture.events.stream().filter(event -> event.startsWith("capture:")).count());
        fixture.finish();
        assertEquals(9, fixture.sent());
    }

    @Test
    void repeatedCommitDoesNotCancelTheRemainingDelivery() {
        Fixture fixture = new Fixture();
        ChunkPreSendTicket<String, Fixture> ticket = fixture.prepare("source");
        fixture.x = 20;
        assertTrue(fixture.service.commit(ticket));
        assertFalse(fixture.service.commit(ticket));
        fixture.finish();
        assertEquals(9, fixture.sent());
    }

    @Test
    void cancellationAndRedirectionNeverDeliverPreparedDestination() {
        Fixture fixture = new Fixture();
        ChunkPreSendTicket<String, Fixture> cancelled = fixture.prepare("destination");
        fixture.service.cancel(cancelled);
        assertFalse(fixture.service.commit(cancelled));
        ChunkPreSendTicket<String, Fixture> redirected = fixture.prepare("destination");
        fixture.world = "another-world";
        fixture.x = 20;
        assertFalse(fixture.service.commit(redirected));
        fixture.finish();
        assertTrue(fixture.events.isEmpty());
    }

    @Test
    void remainingChunksContinueWithinBurstLimitAndStopAfterAnotherTeleport() {
        Fixture fixture = new Fixture();
        ChunkPreSendTicket<String, Fixture> ticket = fixture.prepare("source");
        fixture.x = 20;
        fixture.service.commit(ticket);
        assertEquals(1, fixture.sent());
        while (fixture.sent() < 2) { fixture.next(); }
        assertEquals(2, fixture.sent());
        fixture.x = 100;
        fixture.finish();
        assertEquals(2, fixture.sent());
    }

    @Test
    void regionCaptureReturnsToPlayerOwnerBeforeDelivery() {
        Fixture fixture = new Fixture();
        fixture.regionOwned = false;
        ChunkPreSendTicket<String, Fixture> ticket = fixture.prepare("destination");
        fixture.world = "destination";
        fixture.x = 20;
        fixture.service.commit(ticket);
        fixture.finish();
        assertEquals(9, fixture.sent());
        assertFalse(fixture.wrongOwner);
    }

    @Test
    void supersessionAndShutdownReleasePendingWork() {
        Fixture fixture = new Fixture();
        ChunkPreSendTicket<String, Fixture> first = fixture.prepare("source");
        ChunkPreSendTicket<String, Fixture> second = fixture.prepare("destination");
        fixture.x = 20;
        assertFalse(fixture.service.commit(first));
        fixture.world = "destination";
        assertTrue(fixture.service.commit(second));
        fixture.service.shutdown();
        fixture.finish();
        assertEquals(1, fixture.sent());
    }

    @Test
    void shutdownDuringPreparationCannotPublishNewPendingWork() {
        Fixture fixture = new Fixture();
        fixture.onTime = fixture.service::shutdown;
        ChunkPreSendTicket<String, Fixture> ticket = fixture.prepare("destination");
        assertEquals(ChunkPreSendOutcome.SKIPPED_NO_BUDGET, ticket.outcome());
        assertTrue(fixture.tasks.isEmpty());
        assertTrue(fixture.events.isEmpty());
    }

    private static final class Fixture implements ChunkPreSendPlatform<String, Fixture> {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        private final List<String> events = new ArrayList<>();
        private final ChunkPreSendService<String, Fixture> service = new ChunkPreSendService<>(this,
            () -> ChunkPreSendOptions.of(true, 1, 1, 25_000));
        private String world = "source";
        private int x;
        private boolean regionOwned = true;
        private boolean playerOwner = true;
        private boolean wrongOwner;
        private boolean delayWrites;
        private Runnable onTime;
        private int closedPackets;
        private final List<CompletableFuture<Boolean>> writes = new ArrayList<>();

        ChunkPreSendTicket<String, Fixture> prepare(String destination) {
            return service.prepare(service.capture(this), destination, 320, 0);
        }

        void next() { tasks.removeFirst().run(); }
        void finish() {
            int remaining = 200;
            while (!tasks.isEmpty() && remaining-- > 0) { next(); }
            assertTrue(tasks.isEmpty());
        }
        int sent() { return (int) events.stream().filter(event -> event.startsWith("send:")).count(); }
        @Override public boolean supported() { return true; }
        @Override public boolean online(Fixture player) { wrongOwner |= !playerOwner; return true; }
        @Override public String world(Fixture player) { wrongOwner |= !playerOwner; return world; }
        @Override public int chunkX(Fixture player) { wrongOwner |= !playerOwner; return x; }
        @Override public int chunkZ(Fixture player) { return 0; }
        @Override public int clientViewDistance(Fixture player) { return 2; }
        @Override public int sectionCount(String world) { return 24; }
        @Override public boolean chunkLoaded(String world, int x, int z) { return true; }
        @Override public boolean regionOwned(String world, int minX, int minZ, int maxX, int maxZ) { return regionOwned; }
        @Override public boolean alreadySent(Fixture player, int x, int z) { wrongOwner |= !playerOwner; return false; }
        @Override public boolean announceViewCenter(Fixture player, int x, int z) {
            wrongOwner |= !playerOwner;
            events.add("center:" + x + "," + z);
            return true;
        }
        @Override public boolean sendChunk(Fixture player, String world, int x, int z) { throw new AssertionError("live chunk send"); }
        @Override public ChunkPreparedPacket<Fixture> prepareChunk(String world, int x, int z) {
            wrongOwner |= !regionOwned;
            events.add("capture:" + x + "," + z);
            return new ChunkPreparedPacket<Fixture>() {
                @Override
                public CompletionStage<Boolean> send(Fixture player) {
                    wrongOwner |= !playerOwner;
                    events.add("send:" + x + "," + z);
                    CompletableFuture<Boolean> written = new CompletableFuture<>();
                    writes.add(written);
                    if (!delayWrites) {
                        written.complete(true);
                    }
                    return written;
                }

                @Override
                public void close() { closedPackets++; }
            };
        }
        @Override public boolean scheduleForPlayer(Fixture player, Runnable command, Runnable retired, long delay) {
            tasks.add(() -> { playerOwner = true; command.run(); });
            return true;
        }
        @Override public boolean scheduleForRegion(String world, int x, int z, Runnable command, long delay) {
            tasks.add(() -> {
                playerOwner = false;
                regionOwned = true;
                command.run();
                regionOwned = false;
                playerOwner = true;
            });
            return true;
        }
        @Override public long nanoTime() {
            Runnable callback = onTime;
            onTime = null;
            if (callback != null) {
                callback.run();
            }
            return 0L;
        }
    }
}
