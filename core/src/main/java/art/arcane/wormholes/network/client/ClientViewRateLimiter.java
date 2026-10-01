package art.arcane.wormholes.network.client;

import java.util.Arrays;

public final class ClientViewRateLimiter {
    private static final long SECOND_MILLIS = 1000L;

    private final int maxMessagesPerSecond;
    private final int maxPayloadBytes;
    private final int violationLimit;
    private final long violationWindowMillis;
    private final long[] messageTimes;
    private final long[] violationTimes;
    private int messageCursor;
    private int violationCursor;
    private long dropped;
    private long admitted;

    public ClientViewRateLimiter() {
        this(ClientViewProtocol.MAX_C2S_MESSAGES_PER_SECOND, ClientViewProtocol.MAX_C2S_BYTES, ClientViewProtocol.C2S_VIOLATION_LIMIT,
            ClientViewProtocol.C2S_VIOLATION_WINDOW_MILLIS);
    }

    public ClientViewRateLimiter(int maxMessagesPerSecond, int maxPayloadBytes, int violationLimit, long violationWindowMillis) {
        this.maxMessagesPerSecond = Math.max(1, maxMessagesPerSecond);
        this.maxPayloadBytes = Math.max(1, maxPayloadBytes);
        this.violationLimit = Math.max(1, violationLimit);
        this.violationWindowMillis = Math.max(1L, violationWindowMillis);
        this.messageTimes = new long[this.maxMessagesPerSecond];
        this.violationTimes = new long[Math.max(1, this.violationLimit - 1)];
        Arrays.fill(messageTimes, Long.MIN_VALUE);
        Arrays.fill(violationTimes, Long.MIN_VALUE);
    }

    public synchronized Verdict admit(long nowMillis, int payloadBytes) {
        if (payloadBytes > maxPayloadBytes || payloadBytes < 0) {
            return violation(nowMillis);
        }
        long oldest = messageTimes[messageCursor];
        if (oldest != Long.MIN_VALUE && nowMillis - oldest < SECOND_MILLIS) {
            return violation(nowMillis);
        }
        messageTimes[messageCursor] = nowMillis;
        messageCursor = (messageCursor + 1) % messageTimes.length;
        admitted++;
        return Verdict.ACCEPT;
    }

    public synchronized Verdict violation(long nowMillis) {
        dropped++;
        long oldest = violationTimes[violationCursor];
        violationTimes[violationCursor] = nowMillis;
        violationCursor = (violationCursor + 1) % violationTimes.length;
        if (oldest != Long.MIN_VALUE && nowMillis - oldest < violationWindowMillis) {
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

    public enum Verdict {
        ACCEPT,
        DROP,
        RESET
    }
}
