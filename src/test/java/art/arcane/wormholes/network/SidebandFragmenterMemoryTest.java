package art.arcane.wormholes.network;

import art.arcane.wormholes.config.toml.NetworkConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SidebandFragmenterMemoryTest {
    private static final Logger LOGGER = Logger.getLogger("SidebandFragmenterMemoryTest");

    @TempDir
    Path tempDir;

    private NetworkManager network;
    private SidebandFragmenter fragmenter;

    @BeforeEach
    void setUp() {
        NetworkConfig config = new NetworkConfig();
        config.enabled = true;
        config.listenEnabled = false;
        config.serverName = "receiver";
        network = new NetworkManager(LOGGER, config, "26.2", "test", 25565, tempDir);
        fragmenter = network.fragmenter();
    }

    @AfterEach
    void tearDown() {
        network.stop();
    }

    @Test
    void retainedJumboReservationsAreBoundedAndReleasedByPeerForgetAndClear() {
        for (int index = 0; index < 8; index++) {
            SidebandFragmenter.Result result = fragmenter.receive(
                "peer-" + index,
                fragment(index, 0, SidebandFragmenter.MAX_COUNT, SidebandFragmenter.MAX_FRAME_BYTES)
            );
            assertTrue(result.accepted());
        }

        assertEquals(SidebandFragmenter.MAX_RETAINED_BYTES, fragmenter.retainedBytes());
        assertEquals(8, fragmenter.assemblyCount());
        assertFalse(fragmenter.receive(
            "peer-over-limit",
            fragment(100L, 0, SidebandFragmenter.MAX_COUNT, SidebandFragmenter.MAX_FRAME_BYTES)
        ).accepted());

        assertTrue(fragmenter.receive(
            "peer-0",
            fragment(0L, 0, SidebandFragmenter.MAX_COUNT, SidebandFragmenter.MAX_FRAME_BYTES)
        ).accepted());
        assertEquals(SidebandFragmenter.MAX_RETAINED_BYTES, fragmenter.retainedBytes());

        fragmenter.forget("peer-0");
        assertEquals(SidebandFragmenter.MAX_RETAINED_BYTES - SidebandFragmenter.MAX_FRAME_BYTES,
            fragmenter.retainedBytes());
        assertTrue(fragmenter.receive(
            "peer-after-forget",
            fragment(101L, 0, SidebandFragmenter.MAX_COUNT, SidebandFragmenter.MAX_FRAME_BYTES)
        ).accepted());

        fragmenter.clear();
        assertEquals(0L, fragmenter.retainedBytes());
        assertEquals(0, fragmenter.assemblyCount());
    }

    @Test
    void concurrentAdmissionCannotExceedTheRetainedLimit() throws Exception {
        int attempts = 32;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(16);
        List<Callable<Boolean>> tasks = new ArrayList<>(attempts);
        for (int index = 0; index < attempts; index++) {
            int taskIndex = index;
            tasks.add(() -> {
                start.await();
                return fragmenter.receive(
                    "peer-" + taskIndex,
                    fragment(taskIndex, 0, SidebandFragmenter.MAX_COUNT, SidebandFragmenter.MAX_FRAME_BYTES)
                ).accepted();
            });
        }

        try {
            List<Future<Boolean>> futures = new ArrayList<>(attempts);
            for (Callable<Boolean> task : tasks) {
                futures.add(executor.submit(task));
            }
            start.countDown();
            int accepted = 0;
            for (Future<Boolean> future : futures) {
                if (future.get(5L, TimeUnit.SECONDS)) {
                    accepted++;
                }
            }
            assertEquals(8, accepted);
            assertEquals(8, fragmenter.assemblyCount());
            assertEquals(SidebandFragmenter.MAX_RETAINED_BYTES, fragmenter.retainedBytes());
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void replacementAndExpiryReleaseTheirReservation() {
        assertTrue(fragmenter.receive("peer", fragment(1L, 0, 2, SidebandFragmenter.CHUNK_BYTES * 2)).accepted());
        assertEquals(SidebandFragmenter.CHUNK_BYTES * 2L, fragmenter.retainedBytes());

        assertTrue(fragmenter.receive("peer", fragment(1L, 0, 3, SidebandFragmenter.CHUNK_BYTES * 3)).accepted());
        assertEquals(1, fragmenter.assemblyCount());
        assertEquals(SidebandFragmenter.CHUNK_BYTES * 3L, fragmenter.retainedBytes());

        fragmenter.expire(Long.MAX_VALUE);
        assertEquals(0, fragmenter.assemblyCount());
        assertEquals(0L, fragmenter.retainedBytes());
    }

    @Test
    void malformedAndCorruptAssembliesDoNotRetainReservations() {
        WireMessage.SidebandFragment malformed = new WireMessage.SidebandFragment(
            1L,
            0,
            2,
            SidebandFragmenter.CHUNK_BYTES,
            new byte[SidebandFragmenter.CHUNK_BYTES]
        );
        SidebandFragmenter.Result malformedResult = fragmenter.receive("peer", malformed);
        assertTrue(malformedResult.accepted());
        assertNull(malformedResult.completed());
        assertEquals(0L, fragmenter.retainedBytes());

        byte[] corrupt = new byte[64];
        SidebandFragmenter.Result corruptResult = fragmenter.receive(
            "peer",
            new WireMessage.SidebandFragment(2L, 0, 1, corrupt.length, corrupt)
        );
        assertTrue(corruptResult.accepted());
        assertNull(corruptResult.completed());
        assertEquals(0, fragmenter.assemblyCount());
        assertEquals(0L, fragmenter.retainedBytes());
    }

    @Test
    void completedAssemblyReleasesItsReservationAfterDelivery() throws IOException {
        WireMessage.PortalDirectory directory = new WireMessage.PortalDirectory(List.of());
        byte[] plainFrame = WireCodec.encodeFrame(directory);
        byte[] compressedFrame = network.compression().encode(plainFrame, false);
        int total = (compressedFrame.length + SidebandFragmenter.CHUNK_BYTES - 1) / SidebandFragmenter.CHUNK_BYTES;
        SidebandFragmenter.Result result = null;
        WireMessage.SidebandFragment lastFragment = null;
        for (int index = 0; index < total; index++) {
            int offset = index * SidebandFragmenter.CHUNK_BYTES;
            int length = Math.min(SidebandFragmenter.CHUNK_BYTES, compressedFrame.length - offset);
            byte[] chunk = new byte[length];
            System.arraycopy(compressedFrame, offset, chunk, 0, length);
            lastFragment = new WireMessage.SidebandFragment(3L, index, total, compressedFrame.length, chunk);
            result = fragmenter.receive(
                "peer",
                lastFragment
            );
        }

        assertNotNull(result);
        assertTrue(result.accepted());
        assertNotNull(result.completed());
        assertEquals(directory, result.completed().message());
        assertEquals(compressedFrame.length, fragmenter.retainedBytes());
        assertEquals(1, fragmenter.assemblyCount());

        SidebandFragmenter.Result retransmit = fragmenter.receive("peer", lastFragment);
        assertNotNull(retransmit.completed());
        assertEquals(compressedFrame.length, fragmenter.retainedBytes());

        fragmenter.discard(retransmit.completed());
        assertEquals(0, fragmenter.assemblyCount());
        assertEquals(0L, fragmenter.retainedBytes());
    }

    private static WireMessage.SidebandFragment fragment(long messageId, int index, int total, int frameLength) {
        int expectedLength = index == total - 1
            ? frameLength - (index * SidebandFragmenter.CHUNK_BYTES)
            : SidebandFragmenter.CHUNK_BYTES;
        return new WireMessage.SidebandFragment(messageId, index, total, frameLength, new byte[expectedLength]);
    }
}
