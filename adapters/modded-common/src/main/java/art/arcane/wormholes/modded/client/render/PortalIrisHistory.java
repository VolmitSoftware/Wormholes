package art.arcane.wormholes.modded.client.render;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class PortalIrisHistory implements AutoCloseable {
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    private final List<State> states = new ArrayList<>();
    private boolean closed;

    public Scope constructing() {
        if (closed) {
            throw new IllegalStateException("Shader history closed");
        }
        return new Scope(this);
    }

    public static void register(State state) {
        Scope scope = CURRENT.get();
        if (scope != null) {
            if (scope.history.closed) {
                throw new IllegalStateException("Shader history closed");
            }
            scope.history.states.add(Objects.requireNonNull(state));
        }
    }

    public static boolean capturing() {
        return CURRENT.get() != null;
    }

    public void reset() {
        if (closed) {
            throw new IllegalStateException("Shader history closed");
        }
        visit(false);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            visit(true);
        } finally {
            states.clear();
        }
    }

    private void visit(boolean release) {
        Throwable failure = null;
        for (State state : states) {
            try {
                if (release) {
                    state.close();
                } else {
                    state.wormholes$resetHistory();
                }
            } catch (RuntimeException | Error caught) {
                if (failure == null) {
                    failure = caught;
                } else if (failure != caught) {
                    failure.addSuppressed(caught);
                }
            }
        }
        if (failure instanceof RuntimeException exception) {
            throw exception;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    public interface State extends AutoCloseable {
        void wormholes$resetHistory();

        @Override
        default void close() {
        }
    }

    public static final class Scope implements AutoCloseable {
        private final PortalIrisHistory history;
        private final Scope previous;
        private boolean closed;

        private Scope(PortalIrisHistory history) {
            this.history = history;
            previous = CURRENT.get();
            CURRENT.set(this);
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            if (CURRENT.get() != this) {
                throw new IllegalStateException("Shader history scope order");
            }
            closed = true;
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
