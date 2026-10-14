package art.arcane.wormholes.modded.clientview;

import java.util.Objects;
import java.util.function.LongSupplier;

final class MinecraftClientViewClock implements LongSupplier {
    static final long MAX_STEP_NANOS = 1_000_000_000L;

    private final LongSupplier source;
    private long previous;
    private long excluded;

    MinecraftClientViewClock(LongSupplier source) {
        this.source = Objects.requireNonNull(source);
        previous = source.getAsLong();
    }

    @Override
    public synchronized long getAsLong() {
        long now = source.getAsLong();
        long step = now - previous;
        if (step > MAX_STEP_NANOS) {
            excluded += step - MAX_STEP_NANOS;
        }
        if (step > 0L) {
            previous = now;
        }
        return previous - excluded;
    }
}
