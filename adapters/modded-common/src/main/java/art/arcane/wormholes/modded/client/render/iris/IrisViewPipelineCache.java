package art.arcane.wormholes.modded.client.render.iris;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;

final class IrisViewPipelineCache<K, V> implements AutoCloseable {
    private final Options<K, V> options;
    private final LinkedHashMap<K, Entry<V>> entries = new LinkedHashMap<>(16, 0.75F, true);

    IrisViewPipelineCache(Options<K, V> options) {
        this.options = Objects.requireNonNull(options);
        if (options.capacity() < 1) {
            throw new IllegalArgumentException("Pipeline capacity must be positive");
        }
    }

    V enter(K key, int frame) {
        Entry<V> entry = entries.get(key);
        if (entry == null) {
            makeRoom(frame);
            entry = new Entry<>(Objects.requireNonNull(options.create().apply(key)), frame);
            entries.put(key, entry);
        }
        entry.frame = frame;
        entry.lastUsed = System.nanoTime();
        entry.users++;
        return entry.value;
    }

    void exit(K key) {
        Entry<V> entry = entries.get(key);
        if (entry == null || entry.users == 0) {
            throw new IllegalStateException("No portal pipeline scope is open for " + key);
        }
        entry.users--;
    }

    void removeIf(Predicate<K> predicate) {
        removeEntries((key, entry) -> predicate.test(key));
    }

    boolean hasExpired(long cutoff) {
        for (Entry<V> entry : entries.values()) {
            if (entry.users == 0 && entry.lastUsed - cutoff < 0) {
                return true;
            }
        }
        return false;
    }

    void expire(long cutoff) {
        removeEntries((key, entry) -> entry.users == 0 && entry.lastUsed - cutoff < 0);
    }

    private void removeEntries(BiPredicate<K, Entry<V>> predicate) {
        Throwable failure = null;
        Iterator<Map.Entry<K, Entry<V>>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<K, Entry<V>> entry = iterator.next();
            if (!predicate.test(entry.getKey(), entry.getValue())) {
                continue;
            }
            if (entry.getValue().users != 0) {
                throw new IllegalStateException("A portal pipeline was removed while rendering " + entry.getKey());
            }
            iterator.remove();
            try {
                options.release().accept(entry.getValue().value);
            } catch (RuntimeException | Error releaseFailure) {
                if (failure == null) {
                    failure = releaseFailure;
                } else {
                    failure.addSuppressed(releaseFailure);
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

    int size() {
        return entries.size();
    }

    @Override
    public void close() {
        removeIf(key -> true);
    }

    private void makeRoom(int frame) {
        if (entries.size() < options.capacity()) {
            return;
        }
        Iterator<Map.Entry<K, Entry<V>>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Entry<V> entry = iterator.next().getValue();
            if (entry.users != 0 || entry.frame == frame) {
                continue;
            }
            iterator.remove();
            options.release().accept(entry.value);
            return;
        }
        throw new IllegalStateException("The portal pipeline cache cannot exceed its frame view budget");
    }

    record Options<K, V>(int capacity, Function<K, V> create, Consumer<V> release) {
        Options {
            Objects.requireNonNull(create);
            Objects.requireNonNull(release);
        }
    }

    private static final class Entry<V> {
        private final V value;
        private int frame;
        private int users;
        private long lastUsed;

        private Entry(V value, int frame) {
            this.value = value;
            this.frame = frame;
        }
    }
}
