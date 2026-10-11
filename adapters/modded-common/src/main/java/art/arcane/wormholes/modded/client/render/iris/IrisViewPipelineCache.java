package art.arcane.wormholes.modded.client.render.iris;

import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

final class IrisViewPipelineCache<K, V> implements AutoCloseable {
    private final Options<K, V> options;
    private long hits;
    private long creations;
    private long preparations;
    private long evictions;
    private long expirations;
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
            entry = create(key, frame);
        } else {
            hits++;
        }
        entry.frame = frame;
        entry.rendered = true;
        entry.lastUsed = options.clock().getAsLong();
        entry.users++;
        return entry.value;
    }

    boolean contains(K key) {
        return entries.containsKey(key);
    }

    void transfer(Function<K, K> replacement, Predicate<K> retained, int frame) {
        Map<K, K> moves = new LinkedHashMap<>();
        Set<K> targets = new HashSet<>();
        Set<K> collisions = new HashSet<>();
        for (Map.Entry<K, Entry<V>> candidate : entries.entrySet()) {
            Entry<V> entry = candidate.getValue();
            K key = candidate.getKey();
            if (retained.test(key) || entry.users != 0 || entry.rendered && entry.frame == frame) {
                continue;
            }
            K target = replacement.apply(key);
            if (target == null || target.equals(key) || entries.containsKey(target)) {
                continue;
            }
            if (!targets.add(target)) {
                collisions.add(target);
            }
            moves.put(key, target);
        }
        for (Map.Entry<K, K> move : moves.entrySet()) {
            if (!collisions.contains(move.getValue())) {
                entries.put(move.getValue(), entries.remove(move.getKey()));
            }
        }
    }

    boolean retain(K key) {
        Entry<V> entry = entries.get(key);
        if (entry == null) {
            return false;
        }
        entry.lastUsed = options.clock().getAsLong();
        return true;
    }

    boolean prepare(K key, int frame, Set<K> planned) {
        if (retain(key)) {
            return true;
        }
        if (entries.size() >= options.capacity() && !evict(frame, true, planned::contains)
            && !evict(frame, false, planned::contains)) {
            return false;
        }
        create(key, frame);
        preparations++;
        return true;
    }

    Statistics statistics() {
        return new Statistics(hits, creations, preparations, evictions, expirations, entries.size());
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
        removeEntries((key, entry) -> {
            if (entry.users != 0 || entry.lastUsed - cutoff >= 0) {
                return false;
            }
            expirations++;
            return true;
        });
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

    private Entry<V> create(K key, int frame) {
        Entry<V> entry = new Entry<>(Objects.requireNonNull(options.create().apply(key)), frame);
        entry.lastUsed = options.clock().getAsLong();
        entries.put(key, entry);
        creations++;
        return entry;
    }

    private void makeRoom(int frame) {
        if (entries.size() < options.capacity()) {
            return;
        }
        if (evict(frame, true, key -> false) || evict(frame, false, key -> false)) {
            return;
        }
        throw new IllegalStateException("The portal pipeline cache cannot exceed its frame view budget");
    }

    private boolean evict(int frame, boolean preparedOnly, Predicate<K> retained) {
        Iterator<Map.Entry<K, Entry<V>>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<K, Entry<V>> candidate = iterator.next();
            Entry<V> entry = candidate.getValue();
            if (retained.test(candidate.getKey()) || entry.users != 0 || entry.rendered && (preparedOnly || entry.frame == frame)) {
                continue;
            }
            iterator.remove();
            evictions++;
            options.release().accept(entry.value);
            return true;
        }
        return false;
    }

    record Statistics(long hits, long creations, long preparations, long evictions, long expirations, int size) {
    }

    record Options<K, V>(int capacity, Function<K, V> create, Consumer<V> release, LongSupplier clock) {
        Options {
            Objects.requireNonNull(create);
            Objects.requireNonNull(release);
            Objects.requireNonNull(clock);
        }
    }

    private static final class Entry<V> {
        private final V value;
        private int frame;
        private int users;
        private boolean rendered;
        private long lastUsed;

        private Entry(V value, int frame) {
            this.value = value;
            this.frame = frame;
        }
    }
}
