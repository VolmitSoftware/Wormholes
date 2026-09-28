package art.arcane.wormholes.papi;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalProximityIndexTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @Test
    void unknownPlayerHasNoMatch() {
        PortalProximityIndex index = new PortalProximityIndex();

        assertNull(index.match(PLAYER));
        assertNull(index.match(null));
        assertEquals(0, index.size());
    }

    @Test
    void nearestPortalWinsWhenNeitherIsBeingLookedAt() {
        PortalProximityIndex index = new PortalProximityIndex();

        index.offer(PLAYER, 0, 400.0D, 0.0D);
        index.offer(PLAYER, 1, 100.0D, 0.0D);
        index.offer(PLAYER, 2, 900.0D, 0.0D);

        assertEquals(1, index.match(PLAYER).portalIndex());
        assertEquals(100.0D, index.match(PLAYER).distanceSquared());
        assertFalse(index.match(PLAYER).facing());
    }

    @Test
    void thePortalBeingLookedAtBeatsANearerPortalBehindThePlayer() {
        PortalProximityIndex index = new PortalProximityIndex();

        index.offer(PLAYER, 0, 4.0D, -1.0D);
        index.offer(PLAYER, 1, 2500.0D, 1.0D);

        assertEquals(1, index.match(PLAYER).portalIndex());
        assertTrue(index.match(PLAYER).facing());
    }

    @Test
    void amongPortalsBeingLookedAtTheNearestStillWins() {
        PortalProximityIndex index = new PortalProximityIndex();

        index.offer(PLAYER, 0, 2500.0D, 1.0D);
        index.offer(PLAYER, 1, 25.0D, 0.99D);

        assertEquals(1, index.match(PLAYER).portalIndex());
        assertTrue(index.match(PLAYER).facing());
    }

    @Test
    void aPortalJustOutsideTheFacingConeDoesNotCountAsLookedAt() {
        PortalProximityIndex index = new PortalProximityIndex();

        index.offer(PLAYER, 0, 100.0D, PortalProximityIndex.FACING_COSINE - 0.001D);
        index.offer(PLAYER, 1, 400.0D, PortalProximityIndex.FACING_COSINE);

        assertEquals(1, index.match(PLAYER).portalIndex());
        assertTrue(index.match(PLAYER).facing());
    }

    @Test
    void equalCandidatesResolveToTheLowestPortalIndexSoThePassIsDeterministic() {
        PortalProximityIndex first = new PortalProximityIndex();
        first.offer(PLAYER, 7, 64.0D, 0.0D);
        first.offer(PLAYER, 3, 64.0D, 0.0D);

        PortalProximityIndex second = new PortalProximityIndex();
        second.offer(PLAYER, 3, 64.0D, 0.0D);
        second.offer(PLAYER, 7, 64.0D, 0.0D);

        assertEquals(3, first.match(PLAYER).portalIndex());
        assertEquals(3, second.match(PLAYER).portalIndex());
    }

    @Test
    void playersAreTrackedIndependently() {
        PortalProximityIndex index = new PortalProximityIndex();

        index.offer(PLAYER, 0, 100.0D, 0.0D);
        index.offer(OTHER, 1, 900.0D, 0.0D);

        assertEquals(0, index.match(PLAYER).portalIndex());
        assertEquals(1, index.match(OTHER).portalIndex());
        assertEquals(2, index.size());
    }

    @Test
    void nonsenseOffersAreIgnored() {
        PortalProximityIndex index = new PortalProximityIndex();

        index.offer(null, 0, 1.0D, 1.0D);
        index.offer(PLAYER, -1, 1.0D, 1.0D);
        index.offer(PLAYER, 0, -1.0D, 1.0D);

        assertEquals(0, index.size());
    }

    @Test
    void facingCosineIsOneStraightAheadAndMinusOneStraightBehind() {
        assertEquals(1.0D, PortalProximityIndex.facingCosine(0.0F, 0.0F, 0.0D, 0.0D, 10.0D, 100.0D), 1.0E-9D);
        assertEquals(-1.0D, PortalProximityIndex.facingCosine(0.0F, 0.0F, 0.0D, 0.0D, -10.0D, 100.0D), 1.0E-9D);
        assertEquals(0.0D, PortalProximityIndex.facingCosine(0.0F, 0.0F, 10.0D, 0.0D, 0.0D, 100.0D), 1.0E-9D);
    }

    @Test
    void facingCosineFollowsYawAndPitch() {
        assertEquals(1.0D, PortalProximityIndex.facingCosine(-90.0F, 0.0F, 10.0D, 0.0D, 0.0D, 100.0D), 1.0E-9D);
        assertEquals(1.0D, PortalProximityIndex.facingCosine(90.0F, 0.0F, -10.0D, 0.0D, 0.0D, 100.0D), 1.0E-9D);
        assertEquals(1.0D, PortalProximityIndex.facingCosine(0.0F, -90.0F, 0.0D, 10.0D, 0.0D, 100.0D), 1.0E-9D);
        assertEquals(1.0D, PortalProximityIndex.facingCosine(0.0F, 90.0F, 0.0D, -10.0D, 0.0D, 100.0D), 1.0E-9D);
    }

    @Test
    void standingInsideThePortalCountsAsLookingAtIt() {
        assertEquals(1.0D, PortalProximityIndex.facingCosine(37.0F, 12.0F, 0.0D, 0.0D, 0.0D, 0.0D));
    }
}
