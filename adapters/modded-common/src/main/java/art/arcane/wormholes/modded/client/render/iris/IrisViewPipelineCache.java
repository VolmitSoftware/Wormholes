package art.arcane.wormholes.modded.client.render.iris;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
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
    private long adoptions;
    private long evictions;
    private long expirations;
    private final LinkedHashMap<K, Entry<V>> entries = new LinkedHashMap<>(16, 0.75F, true);
    private Set<K> planned = Set.of();
    private Set<K> reserved = Set.of();
    private Set<K> deferred = Set.of();
    private Set<K> continuing = Set.of();

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

    V peek(K key) {
        for (Map.Entry<K, Entry<V>> candidate : entries.entrySet()) {
            if (candidate.getKey().equals(key)) {
                return candidate.getValue().value;
            }
        }
        return null;
    }

    boolean rendered(K key) {
        for (Map.Entry<K, Entry<V>> candidate : entries.entrySet()) {
            if (candidate.getKey().equals(key)) {
                return candidate.getValue().rendered;
            }
        }
        return false;
    }

    Set<K> keys() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(entries.keySet()));
    }

    List<Snapshot<K>> snapshots() {
        List<Snapshot<K>> snapshots = new ArrayList<>(entries.size());
        for (Map.Entry<K, Entry<V>> candidate : entries.entrySet()) {
            Entry<V> entry = candidate.getValue();
            snapshots.add(new Snapshot<>(candidate.getKey(), entry.identity, entry.frame, entry.users, entry.rendered, entry.lastUsed));
        }
        return List.copyOf(snapshots);
    }

    void plan(Set<K> keys, Map<K, K> continuations, Set<K> awaiting) {
        planned = Set.copyOf(keys);
        Set<K> protectedContinuations = new HashSet<>(awaiting);
        protectedContinuations.addAll(continuations.keySet());
        protectedContinuations.addAll(continuations.values());
        continuing = Set.copyOf(protectedContinuations);
        Map<K, K> owners = new LinkedHashMap<>();
        Set<K> collisions = new HashSet<>();
        for (Map.Entry<K, K> continuation : continuations.entrySet()) {
            K source = continuation.getKey();
            K target = continuation.getValue();
            if (!entries.containsKey(source) || source.equals(target)) {
                continue;
            }
            if (owners.putIfAbsent(target, source) != null) {
                collisions.add(target);
            }
        }
        Set<K> sources = new HashSet<>(awaiting);
        sources.retainAll(entries.keySet());
        Set<K> targets = new HashSet<>();
        for (Map.Entry<K, K> owner : owners.entrySet()) {
            K target = owner.getKey();
            K source = owner.getValue();
            if (!collisions.contains(target) && !planned.contains(source) && !entries.containsKey(target)) {
                sources.add(source);
                targets.add(target);
            }
        }
        reserved = Set.copyOf(sources);
        deferred = Set.copyOf(targets);
    }

    void transfer(Function<K, K> replacement, Predicate<K> retained, int frame) {
        Map<K, K> moves = new LinkedHashMap<>();
        Set<K> targets = new HashSet<>();
        Set<K> collisions = new HashSet<>();
        for (Map.Entry<K, Entry<V>> candidate : entries.entrySet()) {
            K key = candidate.getKey();
            K target = replacement.apply(key);
            if (target == null || target.equals(key)) {
                continue;
            }
            if (!targets.add(target)) {
                collisions.add(target);
            }
            moves.put(key, target);
        }
        for (Map.Entry<K, K> move : moves.entrySet()) {
            if (collisions.contains(move.getValue()) || retained.test(move.getKey())) {
                continue;
            }
            Entry<V> source = entries.get(move.getKey());
            Entry<V> occupied = entries.get(move.getValue());
            if (source.users != 0 || source.rendered && source.frame == frame
                || occupied != null && (occupied.users != 0 || occupied.rendered || occupied.frame == frame
                || moves.containsKey(move.getValue()))) {
                continue;
            }
            entries.remove(move.getKey());
            entries.put(move.getValue(), source);
            if (occupied != null) {
                evictions++;
                options.release().accept(occupied.value);
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

    boolean adopt(K key, int frame, Adoption<K> adoption) {
        Objects.requireNonNull(adoption);
        if (entries.containsKey(key) || deferred.contains(key) || continuing.contains(key) || reserved.contains(key)) {
            return false;
        }
        Iterator<Map.Entry<K, Entry<V>>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<K, Entry<V>> candidate = iterator.next();
            K source = candidate.getKey();
            Entry<V> entry = candidate.getValue();
            if (entry.users != 0 || entry.rendered || entry.frame == frame || entry.adopted
                || planned.contains(source) || reserved.contains(source) || deferred.contains(source) || continuing.contains(source)
                || adoption.retained().test(source) || !adoption.compatible().test(source, key)) {
                continue;
            }
            long now = options.clock().getAsLong();
            iterator.remove();
            entries.put(key, entry);
            entry.adopted = true;
            entry.frame = frame;
            entry.lastUsed = now;
            adoptions++;
            return true;
        }
        return false;
    }

    boolean adoptNext(Set<K> pending, int frame, Set<K> actual, Adoption<K> adoption) {
        K key = nextPreparation(pending, actual);
        if (key == null || !adopt(key, frame, adoption)) {
            return false;
        }
        pending.remove(key);
        return true;
    }

    boolean prepare(K key, int frame, Set<K> planned, Preparation preparation) {
        Objects.requireNonNull(preparation);
        if (retain(key)) {
            return true;
        }
        if (deferred.contains(key)) {
            return false;
        }
        if (entries.size() >= options.capacity() && (preparation == Preparation.SPECULATIVE
            || !evict(frame, true, candidate -> planned.contains(candidate) || reserved.contains(candidate))
            && !evict(frame, false, candidate -> planned.contains(candidate) || reserved.contains(candidate)))) {
            return false;
        }
        create(key, frame);
        preparations++;
        return true;
    }

    boolean prepareNext(Set<K> pending, int frame, Set<K> planned, Set<K> actual) {
        K key = nextPreparation(pending, actual);
        if (key == null) {
            return true;
        }
        pending.remove(key);
        if (prepare(key, frame, planned, actual.contains(key) ? Preparation.ACTUAL : Preparation.SPECULATIVE)) {
            return true;
        }
        pending.add(key);
        return false;
    }

    Statistics statistics() {
        return new Statistics(hits, creations, preparations, evictions, expirations, entries.size(), adoptions);
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
        planned = Set.of();
        reserved = Set.of();
        deferred = Set.of();
        continuing = Set.of();
        removeIf(key -> true);
    }

    private Entry<V> create(K key, int frame) {
        Entry<V> entry = new Entry<>(Objects.requireNonNull(options.create().apply(key)), frame, creations + 1L);
        entry.lastUsed = options.clock().getAsLong();
        entries.put(key, entry);
        creations++;
        return entry;
    }

    private K nextPreparation(Set<K> pending, Set<K> actual) {
        Iterator<K> iterator = pending.iterator();
        if (!iterator.hasNext()) {
            return null;
        }
        K key = iterator.next();
        for (K candidate : pending) {
            if (actual.contains(candidate) && !deferred.contains(candidate)) {
                return candidate;
            }
        }
        return key;
    }

    private void makeRoom(int frame) {
        if (entries.size() < options.capacity()) {
            return;
        }
        if (evict(frame, true, key -> planned.contains(key) || reserved.contains(key))
            || evict(frame, false, key -> planned.contains(key) || reserved.contains(key))
            || evict(frame, true, key -> false) || evict(frame, false, key -> false)) {
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

    record Statistics(long hits, long creations, long preparations, long evictions, long expirations, int size, long adoptions) {
    }

    record Snapshot<K>(K key, long identity, int frame, int users, boolean rendered, long lastUsedNanos) {
    }

    enum Preparation {
        ACTUAL,
        SPECULATIVE
    }

    record Adoption<K>(Predicate<K> retained, BiPredicate<K, K> compatible) {
        Adoption {
            Objects.requireNonNull(retained);
            Objects.requireNonNull(compatible);
        }
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
        private final long identity;
        private int frame;
        private int users;
        private boolean rendered;
        private boolean adopted;
        private long lastUsed;

        private Entry(V value, int frame, long identity) {
            this.value = value;
            this.frame = frame;
            this.identity = identity;
        }
    }
}
