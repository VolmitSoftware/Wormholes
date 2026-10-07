package art.arcane.wormholes.modded.clientview;

import org.junit.Test;

import java.util.UUID;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SeamlessArmTimingTest {
    private static final UUID FLOOR = new UUID(0, 1);
    private static final UUID CEILING = new UUID(0, 2);

    @Test
    public void aServerDetectedCrossingWaitsAFewTicksForTheClientClaim() {
        MinecraftSeamlessTravel.ClaimGrace grace = new MinecraftSeamlessTravel.ClaimGrace();
        assertTrue(grace.waiting(FLOOR, 10L));
        assertTrue(grace.waiting(FLOOR, 12L));
        assertFalse(grace.waiting(FLOOR, 13L));
    }

    @Test
    public void everyCrossingThroughTheSamePortalGetsItsOwnGraceOnceTheLastOneSettled() {
        MinecraftSeamlessTravel.ClaimGrace grace = new MinecraftSeamlessTravel.ClaimGrace();
        assertTrue(grace.waiting(FLOOR, 10L));
        grace.settled();
        assertTrue(grace.waiting(FLOOR, 16L));
        assertTrue(grace.waiting(FLOOR, 18L));
        assertFalse(grace.waiting(FLOOR, 19L));
        assertTrue(grace.waiting(FLOOR, 22L));
    }

    @Test
    public void armsKeepTheirSideWhileAFastTravelerIsStillWithinAFewTicksOfThePlane() {
        assertTrue(MinecraftSeamlessTravel.keepsSide(1.5D, 0.0D));
        assertFalse(MinecraftSeamlessTravel.keepsSide(2.5D, 0.0D));
        assertTrue(MinecraftSeamlessTravel.keepsSide(-9.0D, 3.9D));
        assertFalse(MinecraftSeamlessTravel.keepsSide(-10.5D, 3.9D));
    }

    @Test
    public void aDifferentPortalStartsAFreshGrace() {
        MinecraftSeamlessTravel.ClaimGrace grace = new MinecraftSeamlessTravel.ClaimGrace();
        assertTrue(grace.waiting(FLOOR, 10L));
        assertTrue(grace.waiting(CEILING, 12L));
        assertTrue(grace.waiting(CEILING, 14L));
        assertFalse(grace.waiting(CEILING, 15L));
    }
}
