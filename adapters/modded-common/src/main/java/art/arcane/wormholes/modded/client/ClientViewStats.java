package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.wormholes.network.client.ClientViewProtocol;

import java.util.Arrays;

public final class ClientViewStats {
    private static final int SAMPLE_WINDOW = 64;

    private final long[] sweepMicros;
    private final long[] applyMicros;
    private final long[] sorted;
    private int sweepCursor;
    private int sweepCount;
    private int applyCursor;
    private int applyCount;
    private long lastReportMillis;
    private long framesReceived;
    private long bytesReceived;
    private long acksSent;
    private long brickMissesSent;
    private long statsSent;
    private long decodeFailures;
    private long appliedCellsThisTick;
    private long appliedCellsTotal;
    private long payloadBytesSinceMark;

    public ClientViewStats() {
        this.sweepMicros = new long[SAMPLE_WINDOW];
        this.applyMicros = new long[SAMPLE_WINDOW];
        this.sorted = new long[SAMPLE_WINDOW];
    }

    public void sweep(long nanos) {
        sweepMicros[sweepCursor] = nanos / 1000L;
        sweepCursor = (sweepCursor + 1) % SAMPLE_WINDOW;
        sweepCount = Math.min(SAMPLE_WINDOW, sweepCount + 1);
    }

    public void apply(long nanos) {
        applyMicros[applyCursor] = nanos / 1000L;
        applyCursor = (applyCursor + 1) % SAMPLE_WINDOW;
        applyCount = Math.min(SAMPLE_WINDOW, applyCount + 1);
    }

    public int sweepMicrosP50() {
        return percentile50(sweepMicros, sweepCount);
    }

    public int applyMicrosP50() {
        return percentile50(applyMicros, applyCount);
    }

    public void frame(int bytes) {
        framesReceived++;
        bytesReceived += bytes;
        payloadBytesSinceMark += bytes;
    }

    public void decodeFailure() {
        decodeFailures++;
    }

    public void ack() {
        acksSent++;
    }

    public void brickMiss() {
        brickMissesSent++;
    }

    public void appliedCells(long cells) {
        appliedCellsThisTick += cells;
        appliedCellsTotal += cells;
    }

    public long takeAppliedCellsThisTick() {
        long value = appliedCellsThisTick;
        appliedCellsThisTick = 0L;
        return value;
    }

    public long markPayloadBytes() {
        long value = payloadBytesSinceMark;
        payloadBytesSinceMark = 0L;
        return value;
    }

    public long payloadBytesSinceMark() {
        return payloadBytesSinceMark;
    }

    public boolean reportDue(long nowMillis) {
        return nowMillis - lastReportMillis >= ClientViewProtocol.VIEW_STATS_MIN_INTERVAL_MILLIS;
    }

    public ClientViewMessage.ViewStats report(long nowMillis, int clientTick, int attended, int overlayCells, int unknownStates, int plateMb) {
        lastReportMillis = nowMillis;
        statsSent++;
        return new ClientViewMessage.ViewStats(clientTick, clamp16(attended), overlayCells, clamp16(unknownStates),
            clamp16(sweepMicrosP50()), clamp16(applyMicrosP50()), clamp16(plateMb));
    }

    public long framesReceived() {
        return framesReceived;
    }

    public long bytesReceived() {
        return bytesReceived;
    }

    public long acksSent() {
        return acksSent;
    }

    public long brickMissesSent() {
        return brickMissesSent;
    }

    public long statsSent() {
        return statsSent;
    }

    public long decodeFailures() {
        return decodeFailures;
    }

    public long appliedCellsTotal() {
        return appliedCellsTotal;
    }

    public void reset() {
        Arrays.fill(sweepMicros, 0L);
        Arrays.fill(applyMicros, 0L);
        sweepCursor = 0;
        sweepCount = 0;
        applyCursor = 0;
        applyCount = 0;
        appliedCellsThisTick = 0L;
        payloadBytesSinceMark = 0L;
    }

    private int percentile50(long[] samples, int count) {
        if (count == 0) {
            return 0;
        }
        System.arraycopy(samples, 0, sorted, 0, count);
        Arrays.sort(sorted, 0, count);
        return (int) Math.min(Integer.MAX_VALUE, sorted[count / 2]);
    }

    private static int clamp16(int value) {
        return Math.max(0, Math.min(65535, value));
    }
}
