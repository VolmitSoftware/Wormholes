package art.arcane.wormholes.integration.fawe;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

final class FaweEditQuiescence {
    private int sessions;
    private int applications;
    private boolean accepting = true;
    private CompletableFuture<Void> waiting;

    synchronized Session admit() {
        if (!accepting) {
            return null;
        }
        sessions++;
        return new Session();
    }

    CompletableFuture<Void> prepare() {
        CompletableFuture<Void> completion;
        boolean idle;
        synchronized (this) {
            accepting = false;
            if (waiting == null || waiting.isCompletedExceptionally()) {
                waiting = new CompletableFuture<>();
            }
            completion = waiting;
            idle = sessions == 0 && applications == 0;
        }
        if (idle) {
            completion.complete(null);
        }
        return completion;
    }

    synchronized void resume() {
        accepting = true;
        waiting = null;
    }

    synchronized void applicationStarted(Session session) {
        if (session != null && session.closed.get()) {
            throw new IllegalStateException("Cannot apply changes from a closed FAWE edit session");
        }
        applications++;
    }

    void applicationCompleted() {
        CompletableFuture<Void> completion;
        synchronized (this) {
            applications--;
            completion = ready();
        }
        if (completion != null) {
            completion.complete(null);
        }
    }

    private CompletableFuture<Void> ready() {
        return !accepting && sessions == 0 && applications == 0 ? waiting : null;
    }

    final class Session implements AutoCloseable {
        private final AtomicBoolean closed = new AtomicBoolean();

        @Override
        public void close() {
            CompletableFuture<Void> completion;
            synchronized (FaweEditQuiescence.this) {
                if (!closed.compareAndSet(false, true)) {
                    return;
                }
                sessions--;
                completion = ready();
            }
            if (completion != null) {
                completion.complete(null);
            }
        }
    }
}
