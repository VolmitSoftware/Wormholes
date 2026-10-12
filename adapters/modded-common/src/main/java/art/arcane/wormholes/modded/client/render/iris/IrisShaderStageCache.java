package art.arcane.wormholes.modded.client.render.iris;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

final class IrisShaderStageCache {
    private final Options options;
    private final LinkedHashMap<Key, Entry> entries = new LinkedHashMap<>(16, 0.75F, true);
    private final Map<Integer, Entry> handles = new HashMap<>();
    private long hits;
    private long compilations;
    private long uncachedCompilations;
    private long evictions;
    private long releases;
    private long sourceCharacters;
    private int leases;

    IrisShaderStageCache(Options options) {
        this.options = Objects.requireNonNull(options);
        if (options.capacity() < 1 || options.maxSourceCharacters() < 1) {
            throw new IllegalArgumentException("Shader stage cache limits must be positive");
        }
    }

    int acquire(Key key, IntSupplier compile) {
        Entry existing = entries.get(key);
        if (existing != null) {
            existing.leases++;
            leases++;
            hits++;
            return existing.handle;
        }
        boolean retained = key.source().length() <= options.maxSourceCharacters() && makeRoom(key.source().length());
        int handle = compile.getAsInt();
        if (handle < 1) {
            throw new IllegalStateException("A compiled shader stage must have a valid handle");
        }
        compilations++;
        if (!retained) {
            uncachedCompilations++;
            return handle;
        }
        Entry entry = new Entry(key, handle);
        entries.put(key, entry);
        handles.put(handle, entry);
        sourceCharacters += key.source().length();
        leases++;
        return handle;
    }

    boolean release(int handle) {
        Entry entry = handles.get(handle);
        if (entry == null) {
            return false;
        }
        if (entry.leases == 0) {
            throw new IllegalStateException("Shader stage lease was released twice for " + handle);
        }
        entry.leases--;
        leases--;
        releases++;
        if (!entry.retained && entry.leases == 0) {
            delete(entry);
        }
        return true;
    }

    void clear() {
        Throwable failure = null;
        Iterator<Entry> iterator = entries.values().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            iterator.remove();
            entry.retained = false;
            if (entry.leases != 0) {
                continue;
            }
            try {
                delete(entry);
            } catch (RuntimeException | Error deletionFailure) {
                if (failure == null) {
                    failure = deletionFailure;
                } else {
                    failure.addSuppressed(deletionFailure);
                }
            }
        }
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    Statistics statistics() {
        return new Statistics(hits, compilations, uncachedCompilations, evictions, releases, entries.size(), handles.size() - entries.size(),
            leases, sourceCharacters);
    }

    private boolean makeRoom(int characters) {
        while (handles.size() >= options.capacity() || sourceCharacters + characters > options.maxSourceCharacters()) {
            Iterator<Entry> iterator = entries.values().iterator();
            Entry available = null;
            while (iterator.hasNext()) {
                Entry candidate = iterator.next();
                if (candidate.leases == 0) {
                    available = candidate;
                    iterator.remove();
                    break;
                }
            }
            if (available == null) {
                return false;
            }
            available.retained = false;
            evictions++;
            delete(available);
        }
        return true;
    }

    private void delete(Entry entry) {
        handles.remove(entry.handle);
        sourceCharacters -= entry.key.source().length();
        options.delete().accept(entry.handle);
    }

    record Key(int type, String source) {
        Key {
            Objects.requireNonNull(source);
        }
    }

    record Options(int capacity, long maxSourceCharacters, IntConsumer delete) {
        Options {
            Objects.requireNonNull(delete);
        }
    }

    record Statistics(long hits, long compilations, long uncachedCompilations, long evictions, long releases,
                      int cached, int retired, int leases, long sourceCharacters) {
    }

    private static final class Entry {
        private final Key key;
        private final int handle;
        private int leases = 1;
        private boolean retained = true;

        private Entry(Key key, int handle) {
            this.key = key;
            this.handle = handle;
        }
    }
}
