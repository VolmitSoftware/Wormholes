package qa;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.render.PortalProjector;
import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Map;
import java.lang.reflect.Field;

final class TickTimingFixture implements Listener {
    private static final double[] DURATIONS = new double[24000];
    private static final int[] PASS_COUNTS = new int[DURATIONS.length];
    private static final int[] COMPLETION_COUNTS = new int[DURATIONS.length];
    private static final double[] PASS_MILLIS = new double[DURATIONS.length];
    private static final int[] SLICE_COUNTS = new int[DURATIONS.length];
    private static final double[] SCAN_MILLIS = new double[DURATIONS.length];
    private static final double[] FINALIZE_MILLIS = new double[DURATIONS.length];
    private static final Field BLOCK_PASSES = field(PortalProjector.class, "blockPasses");
    private static final Field LAST_PROJECT_NANOS = field(PortalProjector.class, "lastProjectNanos");
    private static final Field COMPLETED_SCANS = field(PortalProjector.class, "completedScans");
    private static final Field SCAN_SLICES = field(PortalProjector.class, "scanSlices");
    private static final Field LAST_SCAN_NANOS = field(PortalProjector.class, "lastScanNanos");
    private static final Field LAST_FINALIZE_NANOS = field(PortalProjector.class, "lastFinalizeNanos");
    private static final Map<PortalProjector, Long> PASS_VERSIONS = new IdentityHashMap<>();
    private static final Map<PortalProjector, Long> COMPLETED_VERSIONS = new IdentityHashMap<>();
    private static final Map<PortalProjector, Long> SLICE_VERSIONS = new IdentityHashMap<>();
    private static Field interestField;
    private static Field projectorsField;
    private static int count;
    private static long startedAt;

    static void reset(Player player) {
        count = 0;
        PASS_VERSIONS.clear();
        COMPLETED_VERSIONS.clear();
        SLICE_VERSIONS.clear();
        startedAt = System.nanoTime();
        player.sendMessage("HORIZONTAL ticksReset");
    }

    static void sample(Player player) {
        double[] sorted = Arrays.copyOf(DURATIONS, count);
        Arrays.sort(sorted);
        double total = 0;
        int over50 = 0;
        int measuredPasses = 0;
        int pairedTicks = 0;
        int pairedCompletedTicks = 0;
        int completedScans = 0;
        int scanSlices = 0;
        double scanMillis = 0;
        double finalizeMillis = 0;
        double maxScanMillis = 0;
        double maxFinalizeMillis = 0;
        double pairedTickMillis = 0;
        double pairedCompletedMillis = 0;
        double observedProjectMillis = 0;
        double maxUnaccountedMillis = 0;
        for (int index = 0; index < count; index++) {
            measuredPasses += PASS_COUNTS[index];
            completedScans += COMPLETION_COUNTS[index];
            scanSlices += SLICE_COUNTS[index];
            scanMillis += SCAN_MILLIS[index];
            finalizeMillis += FINALIZE_MILLIS[index];
            maxScanMillis = Math.max(maxScanMillis, SCAN_MILLIS[index]);
            maxFinalizeMillis = Math.max(maxFinalizeMillis, FINALIZE_MILLIS[index]);
            observedProjectMillis += PASS_MILLIS[index];
            if (PASS_COUNTS[index] >= 2) {
                pairedTicks++;
                pairedTickMillis += DURATIONS[index];
            }
            if (COMPLETION_COUNTS[index] >= 2) {
                pairedCompletedTicks++;
                pairedCompletedMillis += DURATIONS[index];
            }
            if (PASS_COUNTS[index] > 0) {
                maxUnaccountedMillis = Math.max(maxUnaccountedMillis, DURATIONS[index] - PASS_MILLIS[index]);
            }
        }
        for (double duration : sorted) {
            total += duration;
            if (duration > 50) {
                over50++;
            }
        }
        player.sendMessage("HORIZONTAL ticks count=" + count + " meanMillis=" + (count == 0 ? 0 : total / count)
            + " p50Millis=" + percentile(sorted, 0.5) + " p95Millis=" + percentile(sorted, 0.95)
            + " p99Millis=" + percentile(sorted, 0.99) + " maxMillis=" + percentile(sorted, 1)
            + " over50=" + over50 + " elapsedMillis=" + (System.nanoTime() - startedAt) / 1_000_000D
            + " measuredPasses=" + measuredPasses + " pairedTicks=" + pairedTicks
            + " pairedTickMean=" + (pairedTicks == 0 ? 0 : pairedTickMillis / pairedTicks)
            + " completedScans=" + completedScans + " pairedCompletedTicks=" + pairedCompletedTicks
            + " pairedCompletedMean=" + (pairedCompletedTicks == 0 ? 0 : pairedCompletedMillis / pairedCompletedTicks)
            + " scanSlices=" + scanSlices + " scanMillis=" + scanMillis + " finalizeMillis=" + finalizeMillis
            + " maxScanMillis=" + maxScanMillis + " maxFinalizeMillis=" + maxFinalizeMillis
            + " observedProjectMillis=" + observedProjectMillis + " maxUnaccountedMillis=" + maxUnaccountedMillis);
    }

    @EventHandler
    public void onTick(ServerTickEndEvent event) {
        if (count < DURATIONS.length) {
            DURATIONS[count] = event.getTickDuration();
            recordProjectors(count);
            count++;
        }
    }

    private static void recordProjectors(int index) {
        PASS_COUNTS[index] = 0;
        COMPLETION_COUNTS[index] = 0;
        PASS_MILLIS[index] = 0;
        SLICE_COUNTS[index] = 0;
        SCAN_MILLIS[index] = 0;
        FINALIZE_MILLIS[index] = 0;
        if (Wormholes.projectionManager == null) {
            return;
        }
        try {
            if (interestField == null) {
                interestField = field(Wormholes.projectionManager.getClass(), "interestSet");
            }
            Object interest = interestField.get(Wormholes.projectionManager);
            if (projectorsField == null) {
                projectorsField = field(interest.getClass(), "projectors");
            }
            Map<?, ?> portals = (Map<?, ?>) projectorsField.get(interest);
            for (Object value : portals.values()) {
                Map<?, ?> observers = (Map<?, ?>) value;
                for (Object candidate : observers.values()) {
                    PortalProjector projector = (PortalProjector) candidate;
                    long passes = BLOCK_PASSES.getLong(projector);
                    long completed = COMPLETED_SCANS.getLong(projector);
                    long slices = SCAN_SLICES.getLong(projector);
                    Long previous = PASS_VERSIONS.put(projector, passes);
                    Long previousCompleted = COMPLETED_VERSIONS.put(projector, completed);
                    Long previousSlices = SLICE_VERSIONS.put(projector, slices);
                    if (previous == null || previousCompleted == null || previousSlices == null) {
                        continue;
                    }
                    int completionDelta = (int) (completed - previousCompleted);
                    int sliceDelta = (int) (slices - previousSlices);
                    COMPLETION_COUNTS[index] += completionDelta;
                    SLICE_COUNTS[index] += sliceDelta;
                    if (previous != passes || completionDelta > 0 || sliceDelta > 0) {
                        PASS_COUNTS[index]++;
                        PASS_MILLIS[index] += LAST_PROJECT_NANOS.getLong(projector) / 1_000_000D;
                    }
                    if (completionDelta > 0 || sliceDelta > 0) {
                        SCAN_MILLIS[index] += LAST_SCAN_NANOS.getLong(projector) / 1_000_000D;
                    }
                    if (completionDelta > 0) {
                        FINALIZE_MILLIS[index] += LAST_FINALIZE_NANOS.getLong(projector) / 1_000_000D;
                    }
                }
            }
        } catch (IllegalAccessException error) {
            throw new IllegalStateException("Projection pass timing unavailable", error);
        }
    }

    private static Field field(Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Projection timing field unavailable: " + name, error);
        }
    }

    private static double percentile(double[] sorted, double quantile) {
        return sorted.length == 0 ? 0 : sorted[Math.max(0, (int) Math.ceil(sorted.length * quantile) - 1)];
    }
}
