package art.arcane.wormholes.modded.clientview;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;

public class MinecraftClientViewClockTest {
    @Test
    public void ordinaryTickStepsAdvanceAtTheSourceRate() {
        AtomicLong source = new AtomicLong(5_000_000_000L);
        MinecraftClientViewClock clock = new MinecraftClientViewClock(source::get);
        long start = clock.getAsLong();
        for (int tick = 0; tick < 1200; tick++) {
            source.addAndGet(50_000_000L);
            clock.getAsLong();
        }
        assertEquals(60_000_000_000L, clock.getAsLong() - start);
    }

    @Test
    public void aPausedServerOnlyCountsOneStepOfItsStall() {
        AtomicLong source = new AtomicLong(0L);
        MinecraftClientViewClock clock = new MinecraftClientViewClock(source::get);
        long start = clock.getAsLong();
        source.addAndGet(50_000_000L);
        clock.getAsLong();
        source.addAndGet(114_000_000_000L);
        long resumed = clock.getAsLong();
        assertEquals(50_000_000L + MinecraftClientViewClock.MAX_STEP_NANOS, resumed - start);
        source.addAndGet(50_000_000L);
        assertEquals(resumed + 50_000_000L, clock.getAsLong());
    }

    @Test
    public void readsNeverMoveBackwards() {
        AtomicLong source = new AtomicLong(10_000_000_000L);
        MinecraftClientViewClock clock = new MinecraftClientViewClock(source::get);
        long first = clock.getAsLong();
        source.addAndGet(-1_000L);
        assertEquals(first, clock.getAsLong());
        source.addAndGet(2_000L);
        assertEquals(first + 1_000L, clock.getAsLong());
    }
}
