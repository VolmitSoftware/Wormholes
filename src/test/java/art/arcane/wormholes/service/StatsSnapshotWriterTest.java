package art.arcane.wormholes.service;

import art.arcane.wormholes.network.NetworkManager;
import art.arcane.wormholes.network.TraversalService;
import art.arcane.wormholes.network.WireCompression;
import art.arcane.wormholes.network.view.EntityRateScheduler;
import art.arcane.wormholes.network.view.ViewServer;
import art.arcane.wormholes.service.StatsSnapshotWriter.CompressionState;
import art.arcane.wormholes.service.StatsSnapshotWriter.DictState;
import art.arcane.wormholes.service.StatsSnapshotWriter.FailureState;
import art.arcane.wormholes.service.StatsSnapshotWriter.SnapshotData;
import art.arcane.wormholes.service.StatsSnapshotWriter.TransportSettings;
import art.arcane.wormholes.service.StatsSnapshotWriter.ViewMetrics;
import art.arcane.wormholes.service.StatsSnapshotWriter.ViewSettings;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatsSnapshotWriterTest {

    @BeforeEach
    void resetTelemetry() {
        WormholesTelemetry.clear();
    }

    @AfterEach
    void clearTelemetry() {
        WormholesTelemetry.clear();
    }

    @Test
    void renderedSnapshotContainsAllSectionHeaders() {
        SnapshotData data = sampleSnapshot();
        String rendered = StatsSnapshotWriter.render(data);
        assertTrue(rendered.startsWith("=== Wormholes Stats ===\n"), "header line missing");
        assertTrue(rendered.contains("CONFIG\n"), "CONFIG section missing");
        assertTrue(rendered.contains("PEERS (3)\n"), "PEERS section header missing or wrong count");
        assertTrue(rendered.contains("COMPRESSION\n"), "COMPRESSION section missing");
        assertTrue(rendered.contains("VIEW STREAMING\n"), "VIEW STREAMING section missing");
        assertTrue(rendered.contains("TRANSFERS\n"), "TRANSFERS section missing");
        assertTrue(rendered.contains("ERRORS (last 60s)\n"), "ERRORS section missing");
    }

    @Test
    void peerRowsAreEmittedInDeterministicSortedOrder() {
        SnapshotData data = sampleSnapshot();
        String rendered = StatsSnapshotWriter.render(data);
        int idxEast = rendered.indexOf("east-1");
        int idxFallback = rendered.indexOf("fallback-3");
        int idxWest = rendered.indexOf("west-2");
        assertTrue(idxEast > 0 && idxFallback > 0 && idxWest > 0, "all peer names present");
        assertTrue(idxEast < idxFallback, "east-1 sorts before fallback-3");
        assertTrue(idxFallback < idxWest, "fallback-3 sorts before west-2");
    }

    @Test
    void disconnectedPeerStillRendersWithStatusTag() {
        List<NetworkManager.PeerSnapshot> peers = new ArrayList<>(samplePeers());
        peers.add(new NetworkManager.PeerSnapshot("zulu-9", "TCP", "203.0.113.99:25599", "dict",
            7, "ab12cd34", 4_500L, 88L, false, true));
        SnapshotData data = sampleSnapshotWithPeers(peers);
        String rendered = StatsSnapshotWriter.render(data);
        assertTrue(rendered.contains("zulu-9"), "disconnected peer must still render");
        assertTrue(rendered.contains("[disconnected]"), "disconnected tag missing");
    }

    @Test
    void pendingHandshakePeerShowsDashForDict() {
        List<NetworkManager.PeerSnapshot> peers = List.of(
            new NetworkManager.PeerSnapshot("alpha", "TCP", "10.0.0.1:8901", "pending", 0, "-",
                -1L, -1L, false, false)
        );
        SnapshotData data = sampleSnapshotWithPeers(peers);
        String rendered = StatsSnapshotWriter.render(data);
        assertTrue(rendered.contains("alpha"));
        assertTrue(rendered.contains("pending"));
    }

    @Test
    void writeAtomicProducesCompleteFileOnBothCalls(@TempDir Path tempDir) throws IOException {
        Path output = tempDir.resolve("wormholes-stats.txt");
        SnapshotData first = sampleSnapshot();
        StatsSnapshotWriter.writeAtomic(output, StatsSnapshotWriter.render(first));
        String firstRead = Files.readString(output);
        assertTrue(firstRead.contains("=== Wormholes Stats ==="), "first write must include header");
        assertTrue(firstRead.endsWith("- (none)\n"), "first write must end completely");

        SnapshotData second = sampleSnapshotWithPeers(List.of(
            new NetworkManager.PeerSnapshot("solo-peer", "UDS", "/tmp/wh-solo.sock", "dict",
                4, "deadbeef", 12L, 1L, true, false)
        ));
        StatsSnapshotWriter.writeAtomic(output, StatsSnapshotWriter.render(second));
        String secondRead = Files.readString(output);
        assertTrue(secondRead.contains("solo-peer"), "second write must contain new peer");
        assertTrue(secondRead.contains("=== Wormholes Stats ==="), "second write must include header");
        assertFalse(secondRead.contains("east-1"), "second write must NOT contain stale data from first snapshot");
        assertTrue(secondRead.endsWith("- (none)\n"), "second write must also end completely");
    }

    @Test
    void emptyErrorsListRendersAsNonePlaceholder() {
        SnapshotData data = sampleSnapshot();
        String rendered = StatsSnapshotWriter.render(data);
        assertTrue(rendered.contains("- (none)"));
    }

    @Test
    void recentErrorsAreCappedAtTen() {
        List<String> errors = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            errors.add("err-" + i);
        }
        SnapshotData base = sampleSnapshot();
        SnapshotData data = new SnapshotData(
            base.generatedAt(), base.uptime(), base.pluginVersion(), base.intervalSec(),
            base.localName(), base.transport(), base.view(), base.udsDirDisplay(),
            base.peers(), base.compression(), base.viewMetrics(), base.transfers(), errors,
            base.failures()
        );
        String rendered = StatsSnapshotWriter.render(data);
        assertTrue(rendered.contains("err-0"));
        assertTrue(rendered.contains("err-9"));
        assertFalse(rendered.contains("err-10"), "errors list must cap at 10");
    }

    @Test
    void failuresSectionRendersPluginTotalAndRateAlways() {
        String rendered = StatsSnapshotWriter.render(sampleSnapshotWithFailures(sampleFailureState()));
        assertTrue(rendered.contains("FAILURES\n"), "FAILURES section missing");
        assertTrue(rendered.contains("plugin:    total 12"), "plugin failure total missing");
        assertTrue(rendered.contains("rate 2.5/min"), "plugin failure rate missing");
    }

    @Test
    void traversalTerminalAndRecordedCountsAreRenderedAsDistinctNumbers() {
        String rendered = StatsSnapshotWriter.render(sampleSnapshotWithFailures(sampleFailureState()));
        assertTrue(rendered.contains("traversal: terminal 5   recorded 9"),
            "terminal and recorded traversal counts must both render and must not be conflated");
        assertTrue(rendered.contains("recorded also counts recovered paths"),
            "the reason terminal and recorded differ must be stated");
        assertTrue(rendered.contains("doors:     terminal 3   recorded 3"), "door counts missing");
    }

    @Test
    void failureBreakdownIsSortedByReasonAndOmitsZeroCounts() {
        Map<String, Long> traversal = new LinkedHashMap<>();
        traversal.put("SOURCE_BOUNCE_SCHEDULE_REJECTED", Long.valueOf(2L));
        traversal.put("ARRIVAL_EXHAUSTED", Long.valueOf(4L));
        traversal.put("HANDOFF_PEER_OFFLINE", Long.valueOf(3L));
        traversal.put("HANDOFF_DENIED", Long.valueOf(0L));
        FailureState failures = new FailureState(0L, 0.0D, Map.of(), 9L, traversal, 0L, Map.of());

        String rendered = StatsSnapshotWriter.render(sampleSnapshotWithFailures(failures));

        int arrival = rendered.indexOf("ARRIVAL_EXHAUSTED");
        int handoff = rendered.indexOf("HANDOFF_PEER_OFFLINE");
        int bounce = rendered.indexOf("SOURCE_BOUNCE_SCHEDULE_REJECTED");
        assertTrue(arrival > 0 && handoff > 0 && bounce > 0, "all non-zero reasons must render");
        assertTrue(arrival < handoff, "reasons must be sorted");
        assertTrue(handoff < bounce, "reasons must be sorted");
        assertFalse(rendered.contains("HANDOFF_DENIED"), "zero-count reasons must be omitted");
        assertTrue(rendered.contains("traversal reasons (3)"), "reason count must exclude zero-count reasons");
    }

    @Test
    void pluginAndDoorBreakdownsRenderUnderTheirOwnHeadings() {
        FailureState failures = new FailureState(
            7L, 1.0D, Map.of("RENDER_PROJECTION_DROPPED", Long.valueOf(7L)),
            0L, Map.of(),
            2L, Map.of("TRANSIT_SCHEDULE_REJECTED", Long.valueOf(2L))
        );
        String rendered = StatsSnapshotWriter.render(sampleSnapshotWithFailures(failures));
        assertTrue(rendered.contains("plugin reasons (1)"), "plugin reason heading missing");
        assertTrue(rendered.contains("RENDER_PROJECTION_DROPPED"), "plugin reason row missing");
        assertTrue(rendered.contains("door reasons (1)"), "door reason heading missing");
        assertTrue(rendered.contains("TRANSIT_SCHEDULE_REJECTED"), "door reason row missing");
        assertTrue(rendered.contains("traversal reasons (0)"), "empty traversal breakdown must still be labelled");
    }

    @Test
    void aMeasuredAllZeroFailureStateStillRendersTheFailuresSection() {
        String rendered = StatsSnapshotWriter.render(sampleSnapshot());
        assertTrue(rendered.contains("FAILURES\n"), "FAILURES section must render even with nothing to report");
        assertTrue(rendered.contains("plugin:    total 0"), "a measured zero must render as a zero total");
        assertTrue(rendered.contains("traversal reasons (0)"), "a measured zero must render an empty breakdown");
    }

    @Test
    void aSnapshotCannotBeBuiltWithoutFailureData() {
        SnapshotData base = sampleSnapshot();
        assertThrows(NullPointerException.class, () -> new SnapshotData(
            base.generatedAt(), base.uptime(), base.pluginVersion(), base.intervalSec(),
            base.localName(), base.transport(), base.view(), base.udsDirDisplay(),
            base.peers(), base.compression(), base.viewMetrics(), base.transfers(),
            base.recentErrors(), null
        ), "a snapshot with unsupplied failure data must not render as a healthy server");
    }

    @Test
    void writeSnapshotCountsAFailureWhenTheSupplierThrows() {
        boolean written = StatsSnapshotWriter.writeSnapshot(quietLogger(), () -> {
            throw new IllegalStateException("boom");
        }, Path.of("unused-stats.txt"));

        assertFalse(written, "a throwing supplier must not report success");
        assertEquals(1L, WormholesTelemetry.failures());
        assertEquals(Long.valueOf(1L), WormholesTelemetry.failureBreakdown().get("STATS_SNAPSHOT_WRITE_FAILED"));
    }

    @Test
    void writeSnapshotCountsAFailureWhenSnapshotDataIsUnavailable() {
        boolean written = StatsSnapshotWriter.writeSnapshot(quietLogger(), () -> null, Path.of("unused-stats.txt"));

        assertFalse(written, "a null snapshot must not report success");
        assertEquals(1L, WormholesTelemetry.failures());
        assertEquals(Long.valueOf(1L), WormholesTelemetry.failureBreakdown().get("STATS_SNAPSHOT_DATA_UNAVAILABLE"));
    }

    @Test
    void writeSnapshotCountsNoFailureOnASuccessfulWrite(@TempDir Path tempDir) {
        boolean written = StatsSnapshotWriter.writeSnapshot(quietLogger(), StatsSnapshotWriterTest::sampleSnapshot,
            tempDir.resolve("wormholes-stats.txt"));

        assertTrue(written, "a healthy write must report success");
        assertEquals(0L, WormholesTelemetry.failures());
    }

    @Test
    void missingSchedulerRuntimeCountsAFailure() {
        assertFalse(StatsSnapshotWriter.schedulerAvailable(null, quietLogger()));
        assertEquals(1L, WormholesTelemetry.failures());
        assertEquals(Long.valueOf(1L), WormholesTelemetry.failureBreakdown().get("STATS_SNAPSHOT_SCHEDULER_UNAVAILABLE"));
    }

    private static Logger quietLogger() {
        Logger logger = Logger.getLogger("StatsSnapshotWriterTest");
        logger.setLevel(Level.OFF);
        return logger;
    }

    private static FailureState sampleFailureState() {
        Map<String, Long> traversal = new LinkedHashMap<>();
        traversal.put("HANDOFF_PEER_OFFLINE", Long.valueOf(5L));
        traversal.put("SOURCE_BOUNCE_SCHEDULE_REJECTED", Long.valueOf(4L));
        return new FailureState(
            12L, 2.5D, Map.of("TRAVERSAL_SOURCE_BOUNCE_SCHEDULE_REJECTED", Long.valueOf(12L)),
            5L, traversal,
            3L, Map.of("TRANSIT_ABORTED", Long.valueOf(3L))
        );
    }

    private static SnapshotData sampleSnapshotWithFailures(FailureState failures) {
        SnapshotData base = sampleSnapshot();
        return new SnapshotData(
            base.generatedAt(), base.uptime(), base.pluginVersion(), base.intervalSec(),
            base.localName(), base.transport(), base.view(), base.udsDirDisplay(),
            base.peers(), base.compression(), base.viewMetrics(), base.transfers(),
            base.recentErrors(), failures
        );
    }

    private static SnapshotData sampleSnapshot() {
        return sampleSnapshotWithPeers(samplePeers());
    }

    private static SnapshotData sampleSnapshotWithPeers(List<NetworkManager.PeerSnapshot> peers) {
        TransportSettings transport = new TransportSettings(true, 3, 10_485_760L, true, "", 600);
        ViewSettings view = new ViewSettings(
            20.0D, 10.0D, 4.0D, 1.0D
        );
        WireCompression.Stats wireStats = new WireCompression.Stats(
            13_000_000L, 3_600_000L, 19_000_000L, 5_500_000L,
            142L, 0L, 91_204L
        );
        DictState dict = new DictState(true, 3, "a1b2c3d4",
            1_750_000_000_000L, 65_536L,
            4_900_000L, 10_485_760L, 358L);
        CompressionState compression = new CompressionState(wireStats, dict);

        ViewServer.Stats viewStats = new ViewServer.Stats(47, 312, 8_266L, 82_938L, 4_000L, 200L);
        EntityRateScheduler.Stats rateStats = new EntityRateScheduler.Stats(360L, 470L, 356L, 158L);
        EntityRateScheduler.Bands bands = new EntityRateScheduler.Bands(16.0D, 64.0D, 128.0D,
            20.0D, 10.0D, 4.0D, 1.0D);
        ViewMetrics viewMetrics = new ViewMetrics(viewStats, rateStats, bands, 10.0D);

        TraversalService.Stats transfers = new TraversalService.Stats(38L, 0L, 1);

        return new SnapshotData(
            Instant.parse("2026-06-15T14:33:21Z"),
            Duration.ofSeconds((2L * 3600L) + (13L * 60L)),
            "1.0.0-26.2",
            10,
            "hub-1",
            transport,
            view,
            "<plugin-data>/uds",
            peers,
            compression,
            viewMetrics,
            transfers,
            List.of(),
            zeroFailures()
        );
    }

    private static FailureState zeroFailures() {
        return new FailureState(0L, 0.0D, Map.of(), 0L, Map.of(), 0L, Map.of());
    }

    private static List<NetworkManager.PeerSnapshot> samplePeers() {
        return List.of(
            new NetworkManager.PeerSnapshot("east-1", "TCP", "peer-east.example:25599", "dict",
                3, "a1b2c3d4", 300L, 12L, true, false),
            new NetworkManager.PeerSnapshot("west-2", "UDS", "/tmp/wh-west.sock", "dict",
                3, "a1b2c3d4", 100L, 1L, true, false),
            new NetworkManager.PeerSnapshot("fallback-3", "TCP", "203.0.113.7:25599", "none",
                0, "-", 1_200L, 88L, true, false)
        );
    }
}
