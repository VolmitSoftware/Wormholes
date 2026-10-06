package art.arcane.wormholes;

import art.arcane.wormholes.portal.PortalConstruction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import art.arcane.optics.math.Face;

public final class ConstructionManagerTest {
    @Test
    public void constructDelayMustNotBePositive() {
        long delayTicks = ConstructionManager.constructRegionDelayTicks();
        assertFalse(delayTicks > 0L);
        assertEquals(0L, delayTicks);
    }

    @Test
    public void anOwnedRegionRunsConstructImmediatelyWithoutAHop() {
        AtomicInteger constructs = new AtomicInteger();
        AtomicInteger hops = new AtomicInteger();
        AtomicLong delay = new AtomicLong(-1L);
        AtomicBoolean settledOpen = new AtomicBoolean(false);

        boolean started = ConstructionManager.dispatchConstruct(
                () -> true,
                (task, delayTicks) -> {
                    hops.incrementAndGet();
                    delay.set(delayTicks);
                    return true;
                },
                () -> {
                    constructs.incrementAndGet();
                    return true;
                },
                opened -> settledOpen.set(opened.booleanValue()));

        assertTrue(started);
        assertEquals(1, constructs.get());
        assertEquals(0, hops.get());
        assertEquals(-1L, delay.get());
        assertTrue(settledOpen.get());
    }

    @Test
    public void aRequiredRegionHopUsesZeroDelayAndDoesNotSettleUntilConstructRuns() {
        AtomicBoolean reserved = new AtomicBoolean(true);
        AtomicBoolean settled = new AtomicBoolean(false);
        AtomicLong delay = new AtomicLong(-1L);
        Runnable[] pending = new Runnable[1];

        boolean started = ConstructionManager.dispatchConstruct(
                () -> false,
                (task, delayTicks) -> {
                    delay.set(delayTicks);
                    pending[0] = task;
                    return true;
                },
                () -> true,
                opened -> {
                    settled.set(true);
                    if(opened.booleanValue())
                    {
                        reserved.set(false);
                    }
                });

        assertTrue(started);
        assertFalse(delay.get() > 0L);
        assertEquals(0L, delay.get());
        assertTrue(reserved.get());
        assertFalse(settled.get());

        pending[0].run();

        assertTrue(settled.get());
        assertFalse(reserved.get());
    }

    @Test
    public void aRejectedConstructHopLeavesReservationsUntilTheCallerRollsBack() {
        AtomicBoolean reserved = new AtomicBoolean(true);
        AtomicBoolean settled = new AtomicBoolean(false);

        boolean started = ConstructionManager.dispatchConstruct(
                () -> false,
                (task, delayTicks) -> false,
                () -> true,
                opened -> {
                    settled.set(true);
                    reserved.set(!opened.booleanValue());
                });

        assertFalse(started);
        assertFalse(settled.get());
        assertTrue(reserved.get());
    }

    @Test
    public void aFailedInlineConstructSettlesClosedWithoutAHop() {
        AtomicInteger hops = new AtomicInteger();
        AtomicBoolean settledOpen = new AtomicBoolean(true);

        boolean started = ConstructionManager.dispatchConstruct(
                () -> true,
                (task, delayTicks) -> {
                    hops.incrementAndGet();
                    return true;
                },
                () -> false,
                opened -> settledOpen.set(opened.booleanValue()));

        assertTrue(started);
        assertEquals(0, hops.get());
        assertFalse(settledOpen.get());
    }

    @Test
    public void coplanarAreaAcceptsPlanesLinesAndPoints() {
        assertTrue(PortalConstruction.isCoplanarPortalArea(0, 3, 4));
        assertTrue(PortalConstruction.isCoplanarPortalArea(5, 0, 2));
        assertTrue(PortalConstruction.isCoplanarPortalArea(5, 2, 0));
        assertTrue(PortalConstruction.isCoplanarPortalArea(0, 0, 4));
        assertTrue(PortalConstruction.isCoplanarPortalArea(5, 0, 0));
        assertTrue(PortalConstruction.isCoplanarPortalArea(0, 0, 0));
    }

    @Test
    public void coplanarAreaRejectsVolumes() {
        assertFalse(PortalConstruction.isCoplanarPortalArea(2, 3, 4));
        assertFalse(PortalConstruction.isCoplanarPortalArea(1, 1, 1));
    }

    @Test
    public void planarNormalFollowsFlatAxis() {
        assertEquals(Face.E, PortalConstruction.derivePortalNormal(0, 3, 4, 1.0D, 0.0D, 0.0D));
        assertEquals(Face.W, PortalConstruction.derivePortalNormal(0, 3, 4, -0.2D, 0.9D, 0.1D));
        assertEquals(Face.U, PortalConstruction.derivePortalNormal(5, 0, 2, 0.0D, 1.0D, 0.0D));
        assertEquals(Face.D, PortalConstruction.derivePortalNormal(5, 0, 2, 0.9D, -0.1D, 0.3D));
        assertEquals(Face.S, PortalConstruction.derivePortalNormal(5, 2, 0, 0.0D, 0.0D, 1.0D));
        assertEquals(Face.N, PortalConstruction.derivePortalNormal(5, 2, 0, 0.1D, 0.2D, -0.9D));
    }

    @Test
    public void lineNormalPicksLookDominantFlatAxis() {
        assertEquals(Face.N, PortalConstruction.derivePortalNormal(2, 0, 0, 0.0D, 0.0D, -1.0D));
        assertEquals(Face.D, PortalConstruction.derivePortalNormal(2, 0, 0, 0.0D, -1.0D, 0.0D));
        assertEquals(Face.E, PortalConstruction.derivePortalNormal(0, 2, 0, 1.0D, 0.5D, 0.0D));
        assertEquals(Face.S, PortalConstruction.derivePortalNormal(0, 2, 0, 0.1D, 0.5D, 1.0D));
    }

    @Test
    public void pointNormalPicksLookDominantAxis() {
        assertEquals(Face.U, PortalConstruction.derivePortalNormal(0, 0, 0, 0.1D, 0.9D, 0.2D));
        assertEquals(Face.S, PortalConstruction.derivePortalNormal(0, 0, 0, 0.0D, 0.0D, 1.0D));
        assertEquals(Face.W, PortalConstruction.derivePortalNormal(0, 0, 0, -0.8D, 0.1D, 0.2D));
    }
}
