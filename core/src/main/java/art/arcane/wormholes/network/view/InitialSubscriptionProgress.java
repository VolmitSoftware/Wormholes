package art.arcane.wormholes.network.view;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class InitialSubscriptionProgress {
    @FunctionalInterface
    public interface TerminalAction {
        void run(InitialSubscriptionProgress progress);
    }

    private final AtomicInteger remaining;
    private final AtomicBoolean terminal = new AtomicBoolean(false);
    private final TerminalAction success;
    private final TerminalAction failure;

    public InitialSubscriptionProgress(int columnCount, TerminalAction success, TerminalAction failure) {
        if (columnCount <= 0) {
            throw new IllegalArgumentException("columnCount must be positive");
        }
        this.remaining = new AtomicInteger(columnCount);
        this.success = success;
        this.failure = failure;
    }

    public void complete(Boolean accepted, Throwable error) {
        if (terminal.get()) {
            return;
        }
        if (error != null || !Boolean.TRUE.equals(accepted)) {
            fail();
            return;
        }
        if (remaining.decrementAndGet() == 0 && terminal.compareAndSet(false, true)) {
            success.run(this);
        }
    }

    public void fail() {
        if (terminal.compareAndSet(false, true)) {
            failure.run(this);
        }
    }

    public void cancel() {
        terminal.set(true);
    }
}
