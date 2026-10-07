package art.arcane.wormholes.modded.client.render;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PortalShaderWarmupTest {
    @Test
    public void slowLinkSpendsTheWholeFrameSoNoSecondPipelineLinksBeforeTheNextFrame() {
        AtomicLong clock = new AtomicLong();
        PortalShaderWarmup warmups = new PortalShaderWarmup(clock::get);
        warmups.beginFrame();
        assertTrue(warmups.permit());
        long started = warmups.start();
        clock.addAndGet(70_000_000L);
        warmups.spend(started);
        assertFalse(warmups.permit());
        warmups.beginFrame();
        assertTrue(warmups.permit());
    }

    @Test
    public void quickStepsShareOneFrameBudgetAcrossPipelines() {
        AtomicLong clock = new AtomicLong();
        PortalShaderWarmup warmups = new PortalShaderWarmup(clock::get);
        warmups.beginFrame();
        long first = warmups.start();
        clock.addAndGet(1_000_000L);
        warmups.spend(first);
        assertTrue(warmups.permit());
        assertEquals(3_000_000L, warmups.remaining());
        long second = warmups.start();
        clock.addAndGet(3_000_000L);
        warmups.spend(second);
        assertFalse(warmups.permit());
        assertEquals(1L, warmups.remaining());
    }

    @Test
    public void pipelineConstructionTakesTheRestOfTheFrame() {
        PortalShaderWarmup warmups = new PortalShaderWarmup(new AtomicLong()::get);
        warmups.beginFrame();
        assertTrue(warmups.permit());
        warmups.exhaust();
        assertFalse(warmups.permit());
    }

    @Test
    public void levelSwapHoldsEveryStepUntilTheHoldExpires() {
        AtomicLong clock = new AtomicLong(1_000L);
        PortalShaderWarmup warmups = new PortalShaderWarmup(clock::get);
        warmups.hold();
        warmups.beginFrame();
        assertFalse(warmups.permit());
        clock.addAndGet(499_000_000L);
        warmups.beginFrame();
        assertFalse(warmups.permit());
        clock.addAndGet(1_000_000L);
        warmups.beginFrame();
        assertTrue(warmups.permit());
    }

    @Test
    public void rapidLevelSwapsCannotHoldWarmupForMoreThanHalfOfEachSecond() {
        AtomicLong clock = new AtomicLong(1_000L);
        PortalShaderWarmup warmups = new PortalShaderWarmup(clock::get);
        warmups.hold();
        clock.addAndGet(300_000_000L);
        warmups.hold();
        clock.addAndGet(200_000_000L);
        warmups.beginFrame();
        assertTrue(warmups.permit());
        clock.addAndGet(500_000_000L);
        warmups.hold();
        warmups.beginFrame();
        assertFalse(warmups.permit());
    }
}
