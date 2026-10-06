package art.arcane.optics.plate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

final class PlateCaptureJobTest {
    private static final String WORLD = "destination";

    @Test
    void cachedSiblingFootprintsCompleteWithoutSpendingTheFreshCaptureBudget() {
        FakeSource source = new FakeSource();
        source.reuse = true;
        source.loadAll(0, 0, 2, 0);
        RecordingHost host = new RecordingHost();
        PlateCaptureQueue<String, String> queue = new PlateCaptureQueue<String, String>(host);
        List<PlateCaptureJob<String, String, String>> jobs = new ArrayList<>();
        for (int index = 0; index < 100; index++) {
            PlateCaptureJob<String, String, String> job = job(source, new ViewPlateBuilder.Footprint(0, 0, 2, 0, 64L),
                new ArrayList<PlateCaptureJob.Captured<String>>());
            queue.submit(job);
            jobs.add(job);
        }
        queue.tick(3, 3);
        assertEquals(3, source.captures.size());
        assertEquals(jobs, host.built);
        assertEquals(0, queue.size());
    }

    @Test
    void partiallyCapturedWorkRemainsBudgetQueuedButUnsettledLoadsDoNot() {
        FakeSource source = new FakeSource();
        source.loadAll(0, 0, 1, 0);
        PlateCaptureJob<String, String, String> job = job(source, new ViewPlateBuilder.Footprint(0, 0, 1, 0, 64L),
            new ArrayList<PlateCaptureJob.Captured<String>>());
        assertEquals(1, job.capture(1));
        assertTrue(job.waitingForBudget());
        for (int tick = 0; tick < PlateCaptureJob.MAX_CAPTURE_TICKS + 1; tick++) {
            assertEquals(0, job.capture(0));
            assertTrue(job.waitingForBudget());
        }
        source.loaded.remove("1,0");
        job.capture(0);
        assertFalse(job.waitingForBudget());
        assertEquals(1, job.heldChunks());
    }

    @Test
    void loadedChunksAreSnapshottedWithinTheTickBudgetAndHandedToTheBuildOnce() {
        FakeSource source = new FakeSource();
        source.loadAll(0, 0, 2, 0);
        RecordingHost host = new RecordingHost();
        PlateCaptureQueue<String, String> queue = new PlateCaptureQueue<String, String>(host);
        List<PlateCaptureJob.Captured<String>> handedOff = new ArrayList<PlateCaptureJob.Captured<String>>();
        PlateCaptureJob<String, String, String> job = job(source, new ViewPlateBuilder.Footprint(0, 0, 2, 0, 64L), handedOff);
        queue.submit(job);

        queue.tick(2, 2);
        assertEquals(2, source.captures.size());
        assertEquals(PlateCaptureJob.Phase.CAPTURING, job.phase());
        assertTrue(host.built.isEmpty());

        queue.tick(2, 2);
        assertEquals(3, source.captures.size());
        assertEquals(PlateCaptureJob.Phase.CAPTURED, job.phase());
        assertEquals(List.of(job), host.built);
        assertEquals(1, handedOff.size());
        assertEquals("snapshot:1,0", handedOff.get(0).chunk(1, 0));
        assertEquals(0, queue.size());

        queue.tick(2, 2);
        assertEquals(3, source.captures.size(), "a finished capture is never snapshotted again");
        assertEquals(1, host.built.size());
        assertTrue(job.step(Integer.MAX_VALUE));
        assertNotNull(job.result());
        assertEquals(64L, job.predictedBytes());
    }

    @Test
    void unloadedChunksWaitForTheirLeaseAndAreNeverSnapshottedWhileUnloaded() {
        FakeSource source = new FakeSource();
        source.loadAll(0, 0, 0, 0);
        RecordingHost host = new RecordingHost();
        PlateCaptureQueue<String, String> queue = new PlateCaptureQueue<String, String>(host);
        PlateCaptureJob<String, String, String> job = job(source, new ViewPlateBuilder.Footprint(0, 0, 1, 0, 64L), new ArrayList<PlateCaptureJob.Captured<String>>());
        queue.submit(job);

        queue.tick(8, 8);
        assertEquals(List.of("0,0"), source.captures);
        assertEquals(1, source.holds.size(), "the unloaded chunk is leased");
        assertEquals(1, job.heldChunks());
        queue.tick(8, 8);
        assertEquals(1, source.holds.size(), "a pending lease is not requested twice");
        assertEquals(PlateCaptureJob.Phase.CAPTURING, job.phase());

        FakeHold hold = source.holds.get("1,0");
        hold.settled = true;
        hold.ready = true;
        source.loaded.add("1,0");
        queue.tick(8, 8);

        assertEquals(List.of("0,0", "1,0"), source.captures);
        assertTrue(hold.released, "the lease is released as soon as the snapshot is taken");
        assertEquals(PlateCaptureJob.Phase.CAPTURED, job.phase());
        assertEquals(List.of(job), host.built);
    }

    @Test
    void aFailedLeaseAbortsTheCaptureAndReleasesEveryHold() {
        FakeSource source = new FakeSource();
        RecordingHost host = new RecordingHost();
        PlateCaptureQueue<String, String> queue = new PlateCaptureQueue<String, String>(host);
        PlateCaptureJob<String, String, String> job = job(source, new ViewPlateBuilder.Footprint(0, 0, 1, 0, 64L), new ArrayList<PlateCaptureJob.Captured<String>>());
        queue.submit(job);
        queue.tick(8, 8);
        assertEquals(2, source.holds.size());

        FakeHold failed = source.holds.get("0,0");
        failed.settled = true;
        failed.ready = false;
        queue.tick(8, 8);

        assertEquals(PlateCaptureJob.Phase.FAILED, job.phase());
        assertEquals(List.of(job.key()), host.failed);
        assertTrue(host.built.isEmpty());
        for (FakeHold hold : source.holds.values()) {
            assertTrue(hold.released);
        }
        assertEquals(0, queue.size());
        assertThrows(IllegalStateException.class, () -> job.step(1));
        assertNull(job.result());
    }

    @Test
    void theTickBudgetIsSharedAcrossCapturesInSubmissionOrder() {
        FakeSource source = new FakeSource();
        source.loadAll(0, 0, 3, 0);
        RecordingHost host = new RecordingHost();
        PlateCaptureQueue<String, String> queue = new PlateCaptureQueue<String, String>(host);
        PlateCaptureJob<String, String, String> first = job(source, new ViewPlateBuilder.Footprint(0, 0, 1, 0, 64L), new ArrayList<PlateCaptureJob.Captured<String>>());
        PlateCaptureJob<String, String, String> second = job(source, new ViewPlateBuilder.Footprint(2, 0, 3, 0, 64L), new ArrayList<PlateCaptureJob.Captured<String>>());
        queue.submit(first);
        queue.submit(second);

        queue.tick(2, 2);
        assertEquals(PlateCaptureJob.Phase.CAPTURED, first.phase());
        assertEquals(PlateCaptureJob.Phase.CAPTURING, second.phase());
        assertEquals(2, source.captures.size());

        queue.tick(2, 2);
        assertEquals(PlateCaptureJob.Phase.CAPTURED, second.phase());
        assertEquals(List.of(first, second), host.built);
    }

    @Test
    void urgentCapturesDrawFromTheirOwnBoundedBudgetWithoutSpendingTheSharedOne() {
        FakeSource source = new FakeSource();
        source.loadAll(0, 0, 13, 0);
        RecordingHost host = new RecordingHost();
        PlateCaptureQueue<String, String> queue = new PlateCaptureQueue<String, String>(host);
        PlateCaptureJob<String, String, String> first = job(source, new ViewPlateBuilder.Footprint(0, 0, 3, 0, 64L), new ArrayList<PlateCaptureJob.Captured<String>>());
        PlateCaptureJob<String, String, String> urgent = job(source, new ViewPlateBuilder.Footprint(4, 0, 9, 0, 64L), new ArrayList<PlateCaptureJob.Captured<String>>());
        PlateCaptureJob<String, String, String> promoted = job(source, new ViewPlateBuilder.Footprint(10, 0, 13, 0, 64L), new ArrayList<PlateCaptureJob.Captured<String>>());
        urgent.markUrgent();
        queue.submit(first);
        queue.submit(urgent);
        queue.submit(promoted);

        queue.tick(2, 4);

        assertEquals(2, urgent.pendingChunks(), "an urgent capture takes at most the urgent budget in one tick");
        assertEquals(2, first.pendingChunks(), "the shared budget is untouched by the urgent capture");
        assertEquals(4, promoted.pendingChunks(), "the shared budget was spent by the capture ahead of it");
        assertEquals(6, source.captures.size());
        assertTrue(host.built.isEmpty());

        promoted.markUrgent();
        queue.tick(2, 4);

        assertEquals(List.of(first, urgent), host.built);
        assertEquals(2, promoted.pendingChunks(), "a promoted capture shares what is left of the urgent budget");

        queue.tick(2, 4);
        assertEquals(List.of(first, urgent, promoted), host.built);
        assertTrue(host.failed.isEmpty());
    }

    @Test
    void clearingTheQueueAbortsInFlightCapturesAndReleasesTheirLeases() {
        FakeSource source = new FakeSource();
        RecordingHost host = new RecordingHost();
        PlateCaptureQueue<String, String> queue = new PlateCaptureQueue<String, String>(host);
        PlateCaptureJob<String, String, String> job = job(source, new ViewPlateBuilder.Footprint(0, 0, 0, 0, 64L), new ArrayList<PlateCaptureJob.Captured<String>>());
        queue.submit(job);
        queue.tick(8, 8);

        queue.clear();

        assertEquals(PlateCaptureJob.Phase.FAILED, job.phase());
        assertTrue(source.holds.get("0,0").released);
        assertEquals(List.of(job.key()), host.failed);
        assertEquals(0, queue.size());
    }

    @Test
    void aCaptureThatThrowsFailsTheBuildWithAWarning() {
        FakeSource source = new FakeSource();
        source.loadAll(0, 0, 0, 0);
        source.explode = true;
        RecordingHost host = new RecordingHost();
        PlateCaptureQueue<String, String> queue = new PlateCaptureQueue<String, String>(host);
        PlateCaptureJob<String, String, String> job = job(source, new ViewPlateBuilder.Footprint(0, 0, 0, 0, 64L), new ArrayList<PlateCaptureJob.Captured<String>>());
        queue.submit(job);

        queue.tick(8, 8);

        assertEquals(PlateCaptureJob.Phase.FAILED, job.phase());
        assertEquals(List.of(job.key()), host.failed);
        assertEquals(1, host.warnings);
        assertFalse(host.built.contains(job));
    }

    @Test
    void aCaptureTheCacheNoLongerWantsIsAbortedAndReleasesItsLeases() {
        FakeSource source = new FakeSource();
        RecordingHost host = new RecordingHost();
        PlateCaptureQueue<String, String> queue = new PlateCaptureQueue<String, String>(host);
        PlateCaptureJob<String, String, String> job = job(source, new ViewPlateBuilder.Footprint(0, 0, 1, 0, 64L), new ArrayList<PlateCaptureJob.Captured<String>>());
        queue.submit(job);
        queue.tick(8, 8);
        assertEquals(2, source.holds.size());

        host.unwanted.add(job.key());
        source.loadAll(0, 0, 1, 0);
        queue.tick(8, 8);

        assertEquals(PlateCaptureJob.Phase.FAILED, job.phase());
        assertTrue(source.captures.isEmpty(), "a retired capture takes no more snapshots");
        for (FakeHold hold : source.holds.values()) {
            assertTrue(hold.released);
        }
        assertTrue(host.built.isEmpty());
        assertTrue(host.failed.isEmpty(), "a cancelled capture is not reported as a failure");
        assertEquals(0, queue.size());
    }

    @Test
    void aHeldLeaseExpiresWhileTheSnapshotBudgetIsStarved() {
        FakeSource source = new FakeSource();
        PlateCaptureJob<String, String, String> job = job(source, new ViewPlateBuilder.Footprint(0, 0, 0, 0, 64L),
            new ArrayList<PlateCaptureJob.Captured<String>>());
        job.capture(1);
        FakeHold hold = source.holds.get("0,0");
        assertNotNull(hold);

        for (int tick = 1; tick < PlateCaptureJob.MAX_CAPTURE_TICKS; tick++) {
            job.capture(0);
        }
        assertEquals(PlateCaptureJob.Phase.CAPTURING, job.phase());
        assertFalse(hold.released);

        job.capture(0);

        assertEquals(PlateCaptureJob.Phase.FAILED, job.phase());
        assertTrue(hold.released);
        assertEquals(0, job.heldChunks());
        assertTrue(source.captures.isEmpty());
    }

    @Test
    void aCaptureStarvedOfTheSharedBudgetDoesNotTimeOut() {
        FakeSource source = new FakeSource();
        int hogChunks = (PlateCaptureJob.MAX_CAPTURE_TICKS / 2) + 100;
        int starvedTicks = hogChunks * 2;
        source.loadAll(0, 0, starvedTicks, 0);
        RecordingHost host = new RecordingHost();
        PlateCaptureQueue<String, String> queue = new PlateCaptureQueue<String, String>(host);
        PlateCaptureJob<String, String, String> first = job(source, new ViewPlateBuilder.Footprint(0, 0, hogChunks - 1, 0, 64L), new ArrayList<PlateCaptureJob.Captured<String>>());
        PlateCaptureJob<String, String, String> second = job(source, new ViewPlateBuilder.Footprint(hogChunks, 0, starvedTicks - 1, 0, 64L), new ArrayList<PlateCaptureJob.Captured<String>>());
        PlateCaptureJob<String, String, String> starved = job(source, new ViewPlateBuilder.Footprint(starvedTicks, 0, starvedTicks, 0, 64L), new ArrayList<PlateCaptureJob.Captured<String>>());
        queue.submit(first);
        queue.submit(second);
        queue.submit(starved);

        for (int tick = 0; tick < starvedTicks; tick++) {
            queue.tick(1, 1);
        }
        assertTrue(starvedTicks > PlateCaptureJob.MAX_CAPTURE_TICKS);
        assertEquals(List.of(first, second), host.built, "each capture only counts the ticks it had budget for");
        assertEquals(PlateCaptureJob.Phase.CAPTURING, starved.phase(), "ticks without budget do not count toward the timeout");

        queue.tick(1, 1);

        assertEquals(PlateCaptureJob.Phase.CAPTURED, starved.phase());
        assertTrue(host.failed.isEmpty());
    }

    private static PlateCaptureJob<String, String, String> job(FakeSource source, ViewPlateBuilder.Footprint footprint,
                                                               List<PlateCaptureJob.Captured<String>> handedOff) {
        ViewPlateKey key = new ViewPlateKey(UUID.randomUUID(), WORLD, true, 0, 7L);
        return new PlateCaptureJob<String, String, String>(new PlateCaptureJob.Plan<String, String, String>(key, WORLD, footprint, source,
            captured -> {
                handedOff.add(captured);
                return new BuiltJob(key);
            }));
    }

    private static final class BuiltJob extends ViewPlateBuilder.Job<String, String> {
        private BuiltJob(ViewPlateKey key) {
            super(key);
        }

        @Override
        public boolean step(int cellBudget) {
            return true;
        }

        @Override
        public ViewPlate<String> result() {
            return new ViewPlate<String>(key(), PlateGrid.empty(), 0L, 0L, null, Long.MIN_VALUE, 0, 0, 0, 0, 0L, null);
        }
    }

    private static final class FakeHold implements PlateCaptureJob.Hold {
        private boolean settled;
        private boolean ready;
        private boolean released;

        @Override
        public boolean settled() {
            return settled;
        }

        @Override
        public boolean ready() {
            return ready;
        }

        @Override
        public void release() {
            released = true;
        }
    }

    private static final class FakeSource implements PlateCaptureJob.Source<String, String> {
        private final Set<String> loaded = new HashSet<String>();
        private final Map<String, FakeHold> holds = new HashMap<String, FakeHold>();
        private final List<String> captures = new ArrayList<String>();
        private final Map<String, String> cached = new HashMap<>();
        private boolean reuse;
        private boolean explode;

        @Override
        public String cached(String world, int chunkX, int chunkZ) {
            return reuse ? cached.get(chunkX + "," + chunkZ) : null;
        }

        void loadAll(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                    loaded.add(chunkX + "," + chunkZ);
                }
            }
        }

        @Override
        public boolean loaded(String world, int chunkX, int chunkZ) {
            assertSame(WORLD, world);
            return loaded.contains(chunkX + "," + chunkZ);
        }

        @Override
        public PlateCaptureJob.Hold hold(String world, int chunkX, int chunkZ) {
            FakeHold hold = new FakeHold();
            holds.put(chunkX + "," + chunkZ, hold);
            return hold;
        }

        @Override
        public String capture(String world, int chunkX, int chunkZ) {
            String chunk = chunkX + "," + chunkZ;
            assertTrue(loaded.contains(chunk), "snapshots are only taken of loaded chunks");
            if (explode) {
                throw new IllegalStateException("snapshot failed");
            }
            captures.add(chunk);
            cached.put(chunk, "snapshot:" + chunk);
            return "snapshot:" + chunk;
        }
    }

    private static final class RecordingHost implements PlateCaptureQueue.Host<String, String> {
        private final List<ViewPlateBuilder.Job<String, String>> built = new ArrayList<ViewPlateBuilder.Job<String, String>>();
        private final List<ViewPlateKey> failed = new ArrayList<ViewPlateKey>();
        private int warnings;

        @Override
        public void build(ViewPlateBuilder.Job<String, String> job) {
            built.add(job);
        }

        private final Set<ViewPlateKey> unwanted = new HashSet<ViewPlateKey>();

        @Override
        public void failed(ViewPlateBuilder.Job<String, String> job) {
            failed.add(job.key());
        }

        @Override
        public boolean wanted(ViewPlateBuilder.Job<String, String> job) {
            return !unwanted.contains(job.key());
        }

        @Override
        public void warning(ViewPlateKey key, RuntimeException failure) {
            warnings++;
        }
    }
}
