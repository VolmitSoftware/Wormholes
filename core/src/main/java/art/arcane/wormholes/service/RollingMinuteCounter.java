package art.arcane.wormholes.service;

import java.util.concurrent.atomic.AtomicLongArray;

final class RollingMinuteCounter {
    private static final int WINDOW_SECONDS = 60;
    private static final long MAX_SLOT_COUNT = 0xFFFFFFFFL;

    private final AtomicLongArray slots = new AtomicLongArray(WINDOW_SECONDS);

    void add(long nowMillis, long amount) {
        if (amount <= 0L) {
            return;
        }
        long second = Math.floorDiv(nowMillis, 1000L);
        int slot = (int) Math.floorMod(second, (long) WINDOW_SECONDS);
        int stamp = (int) second;
        while (true) {
            long packed = slots.get(slot);
            long base = unpackSecond(packed) == stamp ? unpackCount(packed) : 0L;
            long next = pack(stamp, Math.min(MAX_SLOT_COUNT, base + amount));
            if (slots.compareAndSet(slot, packed, next)) {
                return;
            }
        }
    }

    long sum(long nowMillis) {
        int stamp = (int) Math.floorDiv(nowMillis, 1000L);
        long total = 0L;
        for (int slot = 0; slot < WINDOW_SECONDS; slot++) {
            long packed = slots.get(slot);
            long age = Integer.toUnsignedLong(stamp - unpackSecond(packed));
            if (age < WINDOW_SECONDS) {
                total += unpackCount(packed);
            }
        }
        return total;
    }

    void clear() {
        for (int slot = 0; slot < WINDOW_SECONDS; slot++) {
            slots.set(slot, 0L);
        }
    }

    private static long pack(int second, long count) {
        return (Integer.toUnsignedLong(second) << 32) | count;
    }

    private static int unpackSecond(long packed) {
        return (int) (packed >>> 32);
    }

    private static long unpackCount(long packed) {
        return packed & MAX_SLOT_COUNT;
    }
}
