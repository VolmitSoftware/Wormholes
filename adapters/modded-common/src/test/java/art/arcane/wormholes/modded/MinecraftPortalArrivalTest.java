package art.arcane.wormholes.modded;

import org.junit.Test;

import java.util.UUID;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MinecraftPortalArrivalTest {
    private static final UUID EXIT = new UUID(0, 1);
    private static final UUID OTHER = new UUID(0, 2);

    @Test
    public void exitDuringCooldownRemainsRecordedWhenPlayerReturnsAfterExpiry() {
        MinecraftPortalRegistry.Arrival arrival = new MinecraftPortalRegistry.Arrival(EXIT, 1_000L, 60_000L);
        assertFalse(arrival.release(true, 100L));
        assertFalse(arrival.release(false, 500L));
        assertTrue(arrival.blocks(EXIT, true, 999L));
        assertTrue(arrival.release(true, 1_000L));
        assertFalse(arrival.blocks(EXIT, true, 1_000L));
    }

    @Test
    public void remainingInArrivalApertureStillBlocksImmediateBounce() {
        MinecraftPortalRegistry.Arrival arrival = new MinecraftPortalRegistry.Arrival(EXIT, 1_000L, 60_000L);
        assertFalse(arrival.release(true, 999L));
        assertFalse(arrival.release(true, 1_000L));
        assertTrue(arrival.blocks(EXIT, true, 1_000L));
        assertFalse(arrival.blocks(OTHER, true, 1_000L));
        assertTrue(arrival.release(false, 1_001L));
    }

    @Test
    public void leavingArrivalDoesNotBypassConfiguredCooldownOnOtherRoutes() {
        MinecraftPortalRegistry.Arrival arrival = new MinecraftPortalRegistry.Arrival(EXIT, 1_000L, 60_000L);
        assertFalse(arrival.release(false, 100L));
        assertTrue(arrival.blocks(OTHER, false, 999L));
        assertFalse(arrival.blocks(OTHER, false, 1_000L));
    }

    @Test
    public void abandonedArrivalStillExpiresWhilePlayerRemainsInside() {
        MinecraftPortalRegistry.Arrival arrival = new MinecraftPortalRegistry.Arrival(EXIT, 1_000L, 60_000L);
        assertFalse(arrival.release(true, 59_999L));
        assertTrue(arrival.release(true, 60_000L));
    }
}
