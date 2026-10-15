package art.arcane.wormholes.clientgametest;

final class ReadbackWatchdog {
    private final long timeoutNanos;
    private int issued;
    private int completed;
    private long deadline;

    ReadbackWatchdog(long timeoutNanos) {
        this.timeoutNanos = timeoutNanos;
    }

    void issue(long now) {
        if (issued == completed) {
            deadline = now + timeoutNanos;
        }
        issued++;
    }

    void complete(long now) {
        completed++;
        deadline = now + timeoutNanos;
    }

    boolean expired(long now) {
        return issued > completed && now - deadline > 0L;
    }

    int issued() {
        return issued;
    }

    int completed() {
        return completed;
    }

    int outstanding() {
        return issued - completed;
    }
}
