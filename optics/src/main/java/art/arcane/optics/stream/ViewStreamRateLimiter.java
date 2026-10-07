package art.arcane.optics.stream;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.IntFunction;

public final class ViewStreamRateLimiter {
    private static final long SECOND_MILLIS = 1000L;

    private final int maxMessagesPerSecond;
    private final int maxPayloadBytes;
    private final int violationLimit;
    private final long violationWindowMillis;
    private final IntFunction<String> names;
    private final long[] messageTimes;
    private final int[] messageTypes;
    private final long[] violationTimes;
    private int messageCursor;
    private int violationCursor;
    private long dropped;
    private long admitted;
    private String lastViolation;

    public ViewStreamRateLimiter(IntFunction<String> names) {
        this(ViewStreamLimits.MAX_C2S_MESSAGES_PER_SECOND, ViewStreamLimits.MAX_C2S_BYTES, ViewStreamLimits.C2S_VIOLATION_LIMIT,
            ViewStreamLimits.C2S_VIOLATION_WINDOW_MILLIS, names);
    }

    public ViewStreamRateLimiter(int maxMessagesPerSecond, int maxPayloadBytes, int violationLimit, long violationWindowMillis,
                                 IntFunction<String> names) {
        this.names = Objects.requireNonNull(names, "names");
        this.maxMessagesPerSecond = Math.max(1, maxMessagesPerSecond);
        this.maxPayloadBytes = Math.max(1, maxPayloadBytes);
        this.violationLimit = Math.max(1, violationLimit);
        this.violationWindowMillis = Math.max(1L, violationWindowMillis);
        this.messageTimes = new long[this.maxMessagesPerSecond];
        this.messageTypes = new int[this.maxMessagesPerSecond];
        this.violationTimes = new long[Math.max(1, this.violationLimit - 1)];
        Arrays.fill(messageTimes, Long.MIN_VALUE);
        Arrays.fill(violationTimes, Long.MIN_VALUE);
    }

    public synchronized Verdict admit(long nowMillis, int payloadBytes, int messageType) {
        if (payloadBytes > maxPayloadBytes || payloadBytes < 0) {
            return violation(nowMillis, ViolationReason.PAYLOAD_SIZE, messageType, payloadBytes);
        }
        long oldest = messageTimes[messageCursor];
        if (oldest != Long.MIN_VALUE && nowMillis - oldest < SECOND_MILLIS) {
            return violation(nowMillis, ViolationReason.MESSAGE_RATE, messageType, payloadBytes);
        }
        messageTimes[messageCursor] = nowMillis;
        messageTypes[messageCursor] = messageType;
        messageCursor = (messageCursor + 1) % messageTimes.length;
        admitted++;
        return Verdict.ACCEPT;
    }

    public synchronized Verdict violation(long nowMillis, int messageType, int payloadBytes) {
        return violation(nowMillis, ViolationReason.PROTOCOL, messageType, payloadBytes);
    }

    private Verdict violation(long nowMillis, ViolationReason reason, int messageType, int payloadBytes) {
        dropped++;
        long oldest = violationTimes[violationCursor];
        violationTimes[violationCursor] = nowMillis;
        violationCursor = (violationCursor + 1) % violationTimes.length;
        if (oldest != Long.MIN_VALUE && nowMillis - oldest < violationWindowMillis) {
            lastViolation = violationSummary(nowMillis, reason, messageType, payloadBytes);
            reset();
            return Verdict.RESET;
        }
        return Verdict.DROP;
    }

    public synchronized void reset() {
        Arrays.fill(messageTimes, Long.MIN_VALUE);
        Arrays.fill(violationTimes, Long.MIN_VALUE);
        messageCursor = 0;
        violationCursor = 0;
    }

    public synchronized long dropped() {
        return dropped;
    }

    public synchronized long admitted() {
        return admitted;
    }

    public synchronized String lastViolation() {
        return lastViolation;
    }

    private String violationSummary(long nowMillis, ViolationReason reason, int messageType, int payloadBytes) {
        int[] counts = new int[256];
        int recent = 0;
        for (int index = 0; index < messageTimes.length; index++) {
            if (messageTimes[index] == Long.MIN_VALUE || nowMillis - messageTimes[index] >= SECOND_MILLIS) {
                continue;
            }
            recent++;
            if (messageTypes[index] >= 0 && messageTypes[index] < counts.length) {
                counts[messageTypes[index]]++;
            }
        }
        StringBuilder summary = new StringBuilder(reason.name()).append(", packet ").append(messageName(messageType))
            .append(", bytes ").append(payloadBytes).append(", accepted in last second ").append(recent).append(", by type");
        for (int type = 0; type < counts.length; type++) {
            if (counts[type] != 0) {
                summary.append(' ').append(messageName(type)).append('=').append(counts[type]);
            }
        }
        return summary.toString();
    }

    private String messageName(int id) {
        return id < 0 ? "UNKNOWN(" + id + ")" : names.apply(id);
    }

    public enum Verdict {
        ACCEPT,
        DROP,
        RESET
    }

    private enum ViolationReason {
        PAYLOAD_SIZE, MESSAGE_RATE, PROTOCOL
    }
}
