package art.arcane.optics.stream;

public final class ClientViewAckWindow {
    private static final int UNBOUNDED_TRACKING = 64;

    private final int capacity;
    private final int[] sequences;
    private final long[] sentNanos;
    private final boolean[] closed;
    private int size;
    private long acked;
    private long lastRttNanos;
    private long appliedCells;

    ClientViewAckWindow(int capacity) {
        this.capacity = Math.max(0, capacity);
        int slots = this.capacity == 0 ? UNBOUNDED_TRACKING : this.capacity;
        this.sequences = new int[slots];
        this.sentNanos = new long[slots];
        this.closed = new boolean[slots];
    }

    synchronized void record(int sequence, long nanos) {
        append(sequence, nanos, true);
    }

    synchronized void open(int sequence, long nanos) {
        append(sequence, nanos, false);
    }

    synchronized boolean close(int openSequence, int closeSequence, long nanos) {
        int slot = openSlot(openSequence);
        if (slot < 0) {
            return false;
        }
        sequences[slot] = closeSequence;
        sentNanos[slot] = nanos;
        closed[slot] = true;
        return true;
    }

    synchronized boolean abandon(int openSequence) {
        int slot = openSlot(openSequence);
        if (slot < 0) {
            return false;
        }
        remove(slot);
        return true;
    }

    synchronized boolean full() {
        return capacity > 0 && size >= capacity;
    }

    synchronized boolean ack(int sequence, int applied, long nanos) {
        appliedCells += Math.max(0, applied);
        boolean freed = false;
        int slot = 0;
        while (slot < size) {
            if (!closed[slot] || sequences[slot] - sequence > 0) {
                slot++;
                continue;
            }
            lastRttNanos = Math.max(0L, nanos - sentNanos[slot]);
            remove(slot);
            acked++;
            freed = true;
        }
        return freed;
    }

    synchronized void clear() {
        size = 0;
    }

    synchronized int outstanding() {
        return size;
    }

    synchronized long acked() {
        return acked;
    }

    synchronized long lastRttNanos() {
        return lastRttNanos;
    }

    synchronized long appliedCells() {
        return appliedCells;
    }

    private void append(int sequence, long nanos, boolean isClosed) {
        if (size == sequences.length) {
            remove(0);
        }
        sequences[size] = sequence;
        sentNanos[size] = nanos;
        closed[size] = isClosed;
        size++;
    }

    private int openSlot(int sequence) {
        for (int slot = 0; slot < size; slot++) {
            if (!closed[slot] && sequences[slot] == sequence) {
                return slot;
            }
        }
        return -1;
    }

    private void remove(int slot) {
        int tail = size - slot - 1;
        if (tail > 0) {
            System.arraycopy(sequences, slot + 1, sequences, slot, tail);
            System.arraycopy(sentNanos, slot + 1, sentNanos, slot, tail);
            System.arraycopy(closed, slot + 1, closed, slot, tail);
        }
        size--;
    }
}
