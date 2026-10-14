package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.modded.client.render.stencil.PortalBackends;
import com.google.gson.GsonBuilder;
import net.minecraft.SharedConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class CrossingBenchReport {
    private static final Logger LOGGER = LoggerFactory.getLogger("WormholesCrossBench");
    private static final Path OUTPUT = Path.of("crossbench");
    private static final long POST_NANOS = 3_000_000_000L;
    private static final long FRAME_60 = 16_666_667L;
    private static final long FRAME_30 = 33_333_333L;
    private static final long FRAME_20 = 50_000_000L;
    private static final long TICK_BUDGET = 50_000_000L;
    private static final int BASELINE_SKIP = 2;
    private static final int TOP_ENTRIES = 12;

    private final String label;
    private final List<Sample> samples = new ArrayList<>();

    CrossingBenchReport(String label) {
        this.label = label;
    }

    void add(Sample sample) {
        samples.add(sample);
        LOGGER.info("WORMHOLES_CROSSBENCH {}", line(sample));
        write();
    }

    void finish() {
        write();
        StringBuilder table = new StringBuilder("WORMHOLES_CROSSBENCH_TABLE ").append(label).append('\n').append(header());
        for (Sample sample : samples) {
            table.append('\n').append(line(sample));
        }
        LOGGER.info(table.toString());
    }

    static Sample analyze(CrossingBench.Leg leg, CrossingBenchTap.Recording recording, int returnViewTicks) {
        CrossingBenchTap.ClientCrossing crossing = recording.clientCrossings.isEmpty() ? null : recording.clientCrossings.getFirst();
        CrossingBenchTap.Move move = firstMove(recording.moves);
        long anchor = crossing != null ? crossing.start() : move != null ? move.start() : recording.openNanos;
        ClientStats client = clientStats(recording, crossing);
        ServerStats server = serverStats(recording, move, anchor);
        long end = recording.frames.isEmpty() ? anchor : recording.frames.getLast().start() + recording.frames.getLast().nanos();
        return new Sample(leg.label(), leg.route(), leg.direction(), leg.trip(), leg.phase().key(),
            crossing == null ? null : crossing.from(), crossing == null ? null : crossing.to(),
            millis(recording.epochNanos(recording.openNanos)), millis(recording.epochNanos(anchor)), millis(recording.epochNanos(end)),
            crossing == null ? 0.0D : ms(crossing.nanos()), recording.clientDeclines, recording.clientCrossings.size(), returnViewTicks,
            client, server, Map.copyOf(recording.chunkLoads), waits(recording.chunkWaits), packets(recording.packets));
    }

    private static CrossingBenchTap.Move firstMove(List<CrossingBenchTap.Move> moves) {
        for (CrossingBenchTap.Move move : moves) {
            if (move.moved()) {
                return move;
            }
        }
        return moves.isEmpty() ? null : moves.getFirst();
    }

    private static ClientStats clientStats(CrossingBenchTap.Recording recording, CrossingBenchTap.ClientCrossing crossing) {
        List<CrossingBenchTap.Frame> frames = recording.frames;
        if (crossing == null || crossing.frame() >= frames.size()) {
            return null;
        }
        int index = crossing.frame();
        long[] before = new long[Math.max(0, index - BASELINE_SKIP)];
        for (int frame = BASELINE_SKIP; frame < index; frame++) {
            before[frame - BASELINE_SKIP] = frames.get(frame).nanos();
        }
        List<Long> post = new ArrayList<>();
        long worst = 0L;
        int worstOffset = -1;
        int over60 = 0;
        int over30 = 0;
        int over20 = 0;
        for (int frame = index; frame < frames.size() && frames.get(frame).start() < crossing.start() + POST_NANOS; frame++) {
            long nanos = frames.get(frame).nanos();
            post.add(nanos);
            if (nanos > worst) {
                worst = nanos;
                worstOffset = frame - index;
            }
            over60 += nanos > FRAME_60 ? 1 : 0;
            over30 += nanos > FRAME_30 ? 1 : 0;
            over20 += nanos > FRAME_20 ? 1 : 0;
        }
        long[] after = post.stream().mapToLong(Long::longValue).toArray();
        int firstReady = -1;
        int lastPending = index;
        for (int frame = index + 1; frame < frames.size(); frame++) {
            CrossingBenchTap.Frame sample = frames.get(frame);
            boolean ready = sample.ready() && sample.visible() > 0;
            if (ready && firstReady < 0) {
                firstReady = frame;
            }
            if (!ready) {
                lastPending = frame;
            }
        }
        int settled = lastPending + 1 < frames.size() ? lastPending + 1 : -1;
        long settledCost = 0L;
        for (int frame = index; settled >= 0 && frame < settled; frame++) {
            settledCost += frames.get(frame).nanos();
        }
        return new ClientStats(before.length == 0 ? 0.0D : ms(percentile(before, 0.5D)), ms(frames.get(index).nanos()),
            index + 1 < frames.size() ? ms(frames.get(index + 1).nanos()) : 0.0D, ms(worst), worstOffset,
            after.length == 0 ? 0.0D : ms(percentile(after, 0.5D)), after.length == 0 ? 0.0D : ms(percentile(after, 0.99D)),
            after.length, over60, over30, over20,
            firstReady < 0 ? -1 : firstReady - index, firstReady < 0 ? -1.0D : ms(frames.get(firstReady).start() - crossing.start()),
            settled < 0 ? -1 : settled - index, settled < 0 ? -1.0D : ms(frames.get(settled).start() - crossing.start()), ms(settledCost),
            settled < 0 ? 0 : frames.get(settled).visible());
    }

    private static ServerStats serverStats(CrossingBenchTap.Recording recording, CrossingBenchTap.Move move, long anchor) {
        List<CrossingBenchTap.ServerCycle> cycles = recording.cycles;
        if (cycles.isEmpty()) {
            return null;
        }
        long reference = move == null ? anchor : move.start();
        CrossingBenchTap.ServerCycle crossingCycle = null;
        for (CrossingBenchTap.ServerCycle cycle : cycles) {
            if (move != null && cycle.tick == move.tick()) {
                crossingCycle = cycle;
            }
        }
        List<Long> baseline = new ArrayList<>();
        List<Long> postBusy = new ArrayList<>();
        long postMaxTick = 0L;
        long postWormholes = 0L;
        long postBusySum = 0L;
        long postChunkWait = 0L;
        int postOver = 0;
        for (int index = 0; index < cycles.size(); index++) {
            CrossingBenchTap.ServerCycle cycle = cycles.get(index);
            if (cycle.tickNanos == 0L) {
                continue;
            }
            if (cycle.start + cycle.busyNanos() < reference && cycle != crossingCycle) {
                if (index >= BASELINE_SKIP) {
                    baseline.add(cycle.tickNanos);
                }
                continue;
            }
            if (cycle.start >= reference + POST_NANOS) {
                continue;
            }
            postBusy.add(cycle.busyNanos());
            postMaxTick = Math.max(postMaxTick, cycle.tickNanos);
            postWormholes += cycle.wormholesNanos + cycle.inboundNanos;
            postBusySum += cycle.busyNanos();
            postChunkWait += cycle.chunkWaitNanos;
            postOver += cycle.busyNanos() > TICK_BUDGET ? 1 : 0;
        }
        long[] base = baseline.stream().mapToLong(Long::longValue).toArray();
        long[] busy = postBusy.stream().mapToLong(Long::longValue).toArray();
        Cycle crossingStats = crossingCycle == null ? null : new Cycle(crossingCycle.tick, ms(crossingCycle.packetsNanos), ms(crossingCycle.tickNanos),
            ms(crossingCycle.tasksNanos),
            crossingCycle.tasks, ms(crossingCycle.inboundNanos), ms(crossingCycle.wormholesNanos), ms(crossingCycle.chunkWaitNanos),
            millis(recording.epochNanos(crossingCycle.start)), millis(recording.epochNanos(crossingCycle.start + crossingCycle.busyNanos())));
        return new ServerStats(move == null ? -1.0D : ms(move.nanos()), move != null && move.moved(), move == null ? null : move.from(),
            move == null ? null : move.to(), recording.moves.size(), crossingStats, base.length == 0 ? 0.0D : ms(percentile(base, 0.5D)),
            ms(postMaxTick), busy.length == 0 ? 0.0D : ms(Arrays.stream(busy).max().orElse(0L)), busy.length == 0 ? 0.0D : ms(percentile(busy, 0.99D)),
            busy.length, postOver, ms(postBusySum), ms(postWormholes), ms(postChunkWait));
    }

    private static List<Wait> waits(Map<String, long[]> waits) {
        List<Wait> entries = new ArrayList<>(waits.size());
        for (Map.Entry<String, long[]> entry : waits.entrySet()) {
            entries.add(new Wait(entry.getKey(), entry.getValue()[0], ms(entry.getValue()[1]), ms(entry.getValue()[2])));
        }
        entries.sort(Comparator.comparingDouble(Wait::totalMs).reversed());
        return entries.size() > TOP_ENTRIES ? List.copyOf(entries.subList(0, TOP_ENTRIES)) : entries;
    }

    private static Packets packets(Map<CrossingBenchTap.Bucket, Map<String, long[]>> buckets) {
        return new Packets(traffic(buckets.get(CrossingBenchTap.Bucket.SENT_BEFORE)), traffic(buckets.get(CrossingBenchTap.Bucket.SENT_AFTER)),
            traffic(buckets.get(CrossingBenchTap.Bucket.ROUTED_BEFORE)), traffic(buckets.get(CrossingBenchTap.Bucket.ROUTED_AFTER)),
            traffic(buckets.get(CrossingBenchTap.Bucket.ENCODED_BEFORE)), traffic(buckets.get(CrossingBenchTap.Bucket.ENCODED_AFTER)));
    }

    private static Traffic traffic(Map<String, long[]> counts) {
        List<PacketType> types = new ArrayList<>(counts.size());
        long count = 0L;
        long bytes = 0L;
        for (Map.Entry<String, long[]> entry : counts.entrySet()) {
            types.add(new PacketType(entry.getKey(), entry.getValue()[0], entry.getValue()[1]));
            count += entry.getValue()[0];
            bytes += entry.getValue()[1];
        }
        types.sort(Comparator.comparingLong(PacketType::bytes).thenComparingLong(PacketType::count).reversed());
        return new Traffic(count, bytes, types.size() > TOP_ENTRIES ? List.copyOf(types.subList(0, TOP_ENTRIES)) : types);
    }

    private void write() {
        try {
            Files.createDirectories(OUTPUT);
            Path staged = OUTPUT.resolve(label + ".json.tmp");
            Files.writeString(staged, new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(new Results(label, loader(),
                SharedConstants.getCurrentVersion().name(), PortalBackends.sodium() ? "sodium" : "vanilla", samples)));
            Files.move(staged, OUTPUT.resolve(label + ".json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException failure) {
            throw new UncheckedIOException("Crossing bench results could not be written to " + OUTPUT.toAbsolutePath(), failure);
        }
    }

    private static String loader() {
        ClassLoader loader = CrossingBenchReport.class.getClassLoader();
        if (loader.getResource("net/fabricmc/loader/api/FabricLoader.class") != null) {
            return "fabric";
        }
        return loader.getResource("net/neoforged/fml/ModList.class") != null ? "neoforge" : "forge";
    }

    private static String header() {
        return String.format(Locale.ROOT, "%-24s %8s %8s %8s %8s %8s %8s %8s %6s %8s %7s %8s %9s",
            "crossing", "srvTick", "srvPkts", "move", "srvMax", "cliCross", "frame0", "worst", ">33ms", "ready", "chunks", "waitMs", "routedKB");
    }

    private static String line(Sample sample) {
        ServerStats server = sample.server();
        ClientStats client = sample.client();
        Cycle cycle = server == null ? null : server.crossingCycle();
        long chunks = 0L;
        for (long count : sample.chunkLoads().values()) {
            chunks += count;
        }
        double waits = 0.0D;
        for (Wait wait : sample.chunkWaits()) {
            waits += wait.totalMs();
        }
        return String.format(Locale.ROOT, "%-24s %8.2f %8.2f %8.2f %8.2f %8.2f %8.2f %8.2f %6d %8.0f %7d %8.2f %9.1f", sample.label(),
            cycle == null ? -1.0D : cycle.tickMs(), cycle == null ? -1.0D : cycle.packetsMs(), server == null ? -1.0D : server.moveMs(),
            server == null ? -1.0D : server.postMaxBusyMs(), sample.clientCrossMs(), client == null ? -1.0D : client.crossingFrameMs(),
            client == null ? -1.0D : client.postWorstMs(), client == null ? -1 : client.postOver30(), client == null ? -1.0D : client.settledMs(),
            chunks, waits, (sample.packets().routedAfter().bytes() + sample.packets().routedBefore().bytes()) / 1024.0D);
    }

    private static long percentile(long[] values, double fraction) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        int rank = (int) Math.ceil(fraction * sorted.length) - 1;
        return sorted[Math.clamp(rank, 0, sorted.length - 1)];
    }

    private static double ms(long nanos) {
        return Math.round(nanos / 10_000.0D) / 100.0D;
    }

    private static long millis(long epochNanos) {
        return epochNanos / 1_000_000L;
    }

    record Results(String label, String loader, String minecraft, String renderer, List<Sample> samples) {
    }

    record Sample(String label, String route, String direction, int trip, String phase, String from, String to, long openEpochMs,
                  long crossingEpochMs, long endEpochMs, double clientCrossMs, int clientDeclines, int clientCrossings, int returnViewTicks,
                  ClientStats client, ServerStats server, Map<String, Long> chunkLoads, List<Wait> chunkWaits, Packets packets) {
    }

    record ClientStats(double baselineP50Ms, double crossingFrameMs, double nextFrameMs, double postWorstMs, int postWorstOffset,
                       double postP50Ms, double postP99Ms, int postFrames, int postOver16, int postOver30, int postOver50,
                       int firstReadyFrames, double firstReadyMs, int settledFrames, double settledMs, double settledFrameCostMs,
                       int settledVisibleSections) {
    }

    record ServerStats(double moveMs, boolean moved, String from, String to, int moves, Cycle crossingCycle, double baselineTickP50Ms,
                       double postMaxTickMs, double postMaxBusyMs, double postP99BusyMs, int postCycles, int postCyclesOver50,
                       double postBusyMs, double postWormholesMs, double postChunkWaitMs) {
    }

    record Cycle(int tick, double packetsMs, double tickMs, double tasksMs, int tasks, double inboundMs, double wormholesTickMs, double chunkWaitMs,
                 long startEpochMs, long endEpochMs) {
    }

    record Wait(String attribution, long count, double totalMs, double maxMs) {
    }

    record Packets(Traffic sentBefore, Traffic sentAfter, Traffic routedBefore, Traffic routedAfter, Traffic encodedBefore, Traffic encodedAfter) {
    }

    record Traffic(long count, long bytes, List<PacketType> types) {
    }

    record PacketType(String type, long count, long bytes) {
    }
}
