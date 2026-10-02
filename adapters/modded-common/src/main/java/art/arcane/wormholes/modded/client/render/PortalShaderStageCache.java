package art.arcane.wormholes.modded.client.render;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

final class PortalShaderStageCache {
    private final Limits limits;
    private final IntConsumer deletion;
    private final LinkedHashMap<Key, Entry> resident = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<Integer, Entry> handles = new HashMap<>();
    private long context;
    private Object generation;
    private long sourceBytes;
    private long hits;
    private long misses;
    private long compileNanos;
    private long linkNanos;
    private Scope scope;

    PortalShaderStageCache(Limits limits, IntConsumer deletion) {
        this.limits = Objects.requireNonNull(limits);
        this.deletion = Objects.requireNonNull(deletion);
    }

    int compile(Request request, IntSupplier compiler) {
        synchronize(request.context(), request.generation());
        Key key = new Key(request.type(), request.source());
        Entry existing = resident.get(key);
        if (request.reuse() && existing != null) {
            existing.borrowers++;
            hits++;
            borrowed(request.context(), existing.handle);
            return existing.handle;
        }
        misses++;
        int handle;
        long started = System.nanoTime();
        try {
            handle = compiler.getAsInt();
        } finally {
            compileNanos += System.nanoTime() - started;
        }
        long bytes = request.source().length() * 2L;
        if (handle <= 0 || existing != null || bytes > limits.sourceBytes() || !reserveHandle()) {
            return handle;
        }
        Entry entry = new Entry(handle, bytes);
        resident.put(key, entry);
        handles.put(handle, entry);
        sourceBytes += bytes;
        trim();
        borrowed(request.context(), handle);
        return handle;
    }

    boolean release(long currentContext, int handle) {
        boolean released = releaseEntry(currentContext, handle);
        if (released) {
            Borrow borrow = new Borrow(currentContext, handle);
            for (Scope current = scope; current != null; current = current.previous) {
                if (current.returned(borrow)) {
                    break;
                }
            }
        }
        return released;
    }

    Scope scope() {
        scope = new Scope(scope);
        return scope;
    }

    private boolean releaseEntry(long currentContext, int handle) {
        if (context != currentContext) {
            return false;
        }
        Entry entry = handles.get(handle);
        if (entry == null) {
            return false;
        }
        if (entry.borrowers <= 0) {
            throw new IllegalStateException("shader stage already released");
        }
        entry.borrowers--;
        if (!entry.resident && entry.borrowers == 0) {
            delete(entry);
        }
        return true;
    }

    void clear(long currentContext) {
        if (context != currentContext) {
            return;
        }
        Iterator<Entry> iterator = resident.values().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            iterator.remove();
            retire(entry);
        }
        generation = null;
    }

    void linkTime(long nanos) {
        linkNanos += nanos;
    }

    Stats stats() {
        int borrowed = 0;
        for (Entry entry : handles.values()) {
            if (entry.borrowers > 0) {
                borrowed++;
            }
        }
        return new Stats(hits, misses, compileNanos, linkNanos, resident.size(), borrowed, sourceBytes);
    }

    private void synchronize(long currentContext, Object currentGeneration) {
        if (context != currentContext) {
            resident.clear();
            handles.clear();
            sourceBytes = 0;
            context = currentContext;
            generation = currentGeneration;
        } else if (generation != currentGeneration) {
            clear(currentContext);
            generation = currentGeneration;
        }
    }

    private void borrowed(long currentContext, int handle) {
        if (scope != null) {
            scope.borrows.merge(new Borrow(currentContext, handle), 1, Integer::sum);
        }
    }

    private void trim() {
        Iterator<Entry> iterator = resident.values().iterator();
        while ((resident.size() > limits.stages() || sourceBytes > limits.sourceBytes()) && iterator.hasNext()) {
            Entry entry = iterator.next();
            iterator.remove();
            retire(entry);
        }
    }

    private boolean reserveHandle() {
        while (handles.size() >= limits.stages()) {
            Entry candidate = null;
            Iterator<Entry> iterator = resident.values().iterator();
            while (iterator.hasNext()) {
                Entry entry = iterator.next();
                if (entry.borrowers == 0) {
                    candidate = entry;
                    iterator.remove();
                    break;
                }
            }
            if (candidate == null) {
                return false;
            }
            retire(candidate);
        }
        return true;
    }

    private void retire(Entry entry) {
        entry.resident = false;
        sourceBytes -= entry.bytes;
        if (entry.borrowers == 0) {
            delete(entry);
        }
    }

    private void delete(Entry entry) {
        handles.remove(entry.handle);
        deletion.accept(entry.handle);
    }

    record Limits(int stages, long sourceBytes) {
        Limits {
            if (stages <= 0 || sourceBytes <= 0) {
                throw new IllegalArgumentException("shader stage cache limits");
            }
        }
    }

    record Request(long context, Object generation, int type, String source, boolean reuse) {
        Request {
            Objects.requireNonNull(generation);
            Objects.requireNonNull(source);
        }
    }

    record Stats(long hits, long misses, long compileNanos, long linkNanos, int residentStages, int borrowedStages, long sourceBytes) {
    }

    private record Key(int type, String source) {
    }

    private record Borrow(long context, int handle) {
    }

    final class Scope implements AutoCloseable {
        private final Scope previous;
        private final Map<Borrow, Integer> borrows = new LinkedHashMap<>();
        private boolean closed;

        private Scope(Scope previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            if (scope != this) {
                throw new IllegalStateException("shader stage borrow scope order");
            }
            closed = true;
            scope = previous;
            Throwable failure = null;
            for (Map.Entry<Borrow, Integer> entry : borrows.entrySet()) {
                for (int borrower = 0; borrower < entry.getValue(); borrower++) {
                    try {
                        releaseEntry(entry.getKey().context(), entry.getKey().handle());
                    } catch (RuntimeException | Error cleanup) {
                        if (failure == null) {
                            failure = cleanup;
                        } else {
                            failure.addSuppressed(cleanup);
                        }
                    }
                }
            }
            borrows.clear();
            if (failure instanceof Error error) {
                throw error;
            }
            if (failure instanceof RuntimeException exception) {
                throw exception;
            }
        }

        private boolean returned(Borrow borrow) {
            Integer count = borrows.get(borrow);
            if (count == null) {
                return false;
            }
            if (count == 1) {
                borrows.remove(borrow);
            } else {
                borrows.put(borrow, count - 1);
            }
            return true;
        }
    }

    private static final class Entry {
        private final int handle;
        private final long bytes;
        private int borrowers = 1;
        private boolean resident = true;

        private Entry(int handle, long bytes) {
            this.handle = handle;
            this.bytes = bytes;
        }
    }
}
