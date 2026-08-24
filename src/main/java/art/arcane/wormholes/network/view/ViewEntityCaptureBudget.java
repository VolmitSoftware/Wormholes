package art.arcane.wormholes.network.view;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class ViewEntityCaptureBudget<K> {
    private final int maxAdmissionsPerPass;
    private final int maxInFlight;
    private final Map<K, CaptureState<K>> states;
    private final ArrayDeque<CaptureState<K>> pending;
    private long leaseSequence;
    private int inFlight;
    private boolean closed;

    ViewEntityCaptureBudget(int maxAdmissionsPerPass, int maxInFlight) {
        if (maxAdmissionsPerPass <= 0) {
            throw new IllegalArgumentException("maxAdmissionsPerPass must be positive");
        }
        if (maxInFlight <= 0) {
            throw new IllegalArgumentException("maxInFlight must be positive");
        }
        this.maxAdmissionsPerPass = maxAdmissionsPerPass;
        this.maxInFlight = maxInFlight;
        this.states = new HashMap<K, CaptureState<K>>();
        this.pending = new ArrayDeque<CaptureState<K>>();
    }

    synchronized void request(K key) {
        Objects.requireNonNull(key, "key");
        if (closed) {
            return;
        }
        CaptureState<K> state = states.computeIfAbsent(key, CaptureState<K>::new);
        if (!state.inFlight && !state.retired) {
            if (!state.pending) {
                state.pending = true;
                pending.addLast(state);
            }
        }
    }

    synchronized List<Admission<K>> acquire() {
        int capacity = Math.min(maxAdmissionsPerPass, maxInFlight - inFlight);
        if (closed || capacity <= 0) {
            return List.of();
        }
        List<Admission<K>> admissions = new ArrayList<Admission<K>>(capacity);
        while (admissions.size() < capacity) {
            CaptureState<K> state = pending.pollFirst();
            if (state == null) {
                break;
            }
            if (!state.pending || state.inFlight || state.retired) {
                continue;
            }
            long leaseToken = ++leaseSequence;
            state.pending = false;
            state.inFlight = true;
            state.leaseToken = leaseToken;
            inFlight++;
            admissions.add(new Admission<K>(state.key, leaseToken));
        }
        return List.copyOf(admissions);
    }

    synchronized void complete(Admission<K> admission) {
        CaptureState<K> state = matchingState(admission);
        if (state == null) {
            return;
        }
        state.inFlight = false;
        inFlight--;
        if (state.retired || closed) {
            states.remove(state.key, state);
        }
    }

    synchronized void reject(Admission<K> admission) {
        CaptureState<K> state = matchingState(admission);
        if (state == null) {
            return;
        }
        state.inFlight = false;
        inFlight--;
        if (state.retired || closed) {
            states.remove(state.key, state);
            return;
        }
        state.pending = true;
        pending.addLast(state);
    }

    synchronized void retire(K key) {
        CaptureState<K> state = states.get(key);
        if (state == null) {
            return;
        }
        if (state.pending) {
            pending.remove(state);
        }
        state.pending = false;
        state.retired = true;
        if (!state.inFlight) {
            states.remove(key, state);
        }
    }

    synchronized void close() {
        closed = true;
        pending.clear();
        List<CaptureState<K>> retired = new ArrayList<CaptureState<K>>(states.values());
        for (CaptureState<K> state : retired) {
            state.pending = false;
            state.retired = true;
            if (!state.inFlight) {
                states.remove(state.key, state);
            }
        }
    }

    synchronized int pendingCount() {
        int pending = 0;
        for (CaptureState<K> state : states.values()) {
            if (state.pending) {
                pending++;
            }
        }
        return pending;
    }

    synchronized int inFlightCount() {
        return inFlight;
    }

    record Admission<K>(K key, long leaseToken) {
    }

    private CaptureState<K> matchingState(Admission<K> admission) {
        if (admission == null) {
            return null;
        }
        CaptureState<K> state = states.get(admission.key());
        return state != null && state.inFlight && state.leaseToken == admission.leaseToken()
            ? state
            : null;
    }

    private static final class CaptureState<K> {
        private final K key;
        private boolean pending;
        private boolean inFlight;
        private boolean retired;
        private long leaseToken;

        private CaptureState(K key) {
            this.key = key;
        }
    }
}
