package art.arcane.optics.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import java.util.Random;

import art.arcane.optics.claim.BlockClaim;
import art.arcane.optics.claim.ClaimSet;
import org.junit.jupiter.api.Test;

final class ClientOverlapResolverTest {
    private static final ClientOverlapResolver EXACT = new ClientOverlapResolver(0.0D);

    @Test
    void nearestPortalWinsASharedCell() {
        ClientOverlapResolver.Contender far = new ClientOverlapResolver.Contender(2, 8.0D, false);
        ClientOverlapResolver.Contender near = new ClientOverlapResolver.Contender(1, -2.0D, false);
        assertTrue(ClientOverlapResolver.isHigherPriority(near, far));
        assertFalse(ClientOverlapResolver.isHigherPriority(far, near));
        assertEquals(1, EXACT.resolve(List.of(far, near), ClientOverlapResolver.NO_PORTAL));
        assertEquals(1, EXACT.resolve(List.of(far, near), 2));
    }

    @Test
    void equalDistanceTieBreaksByTheLowerPortalKey() {
        ClientOverlapResolver.Contender high = new ClientOverlapResolver.Contender(9, 4.0D, false);
        ClientOverlapResolver.Contender low = new ClientOverlapResolver.Contender(1, 4.0D, false);
        assertTrue(ClientOverlapResolver.isHigherPriority(low, high));
        assertEquals(1, EXACT.resolve(List.of(high, low), ClientOverlapResolver.NO_PORTAL));
    }

    @Test
    void aRealProjectionBeatsANearerMaskAirCell() {
        ClientOverlapResolver.Contender mask = new ClientOverlapResolver.Contender(1, 1.0D, true);
        ClientOverlapResolver.Contender block = new ClientOverlapResolver.Contender(2, 8.0D, false);
        assertTrue(ClientOverlapResolver.isHigherPriority(block, mask));
        assertEquals(2, new ClientOverlapResolver(4.0D).resolve(List.of(mask, block), 1));
    }

    @Test
    void priorityMatchesProjectionClaimSetForEveryLiveClaimPair() {
        Random random = new Random(0x0FF5E7L);
        for (int sample = 0; sample < 20_000; sample++) {
            ClientOverlapResolver.Contender candidate = randomContender(random);
            ClientOverlapResolver.Contender current = randomContender(random);
            boolean expected = ClaimSet.isHigherPriority(candidate.distance(), tieKey(candidate), claim(candidate),
                current.distance(), tieKey(current), claim(current));
            assertEquals(expected, ClientOverlapResolver.isHigherPriority(candidate, current), candidate + " vs " + current);
            assertEquals(expected && candidate.portalKey() != current.portalKey(), EXACT.displaces(candidate, current),
                candidate + " displacing " + current);
        }
    }

    @Test
    void theIncumbentKeepsTheCellInsideTheMargin() {
        ClientOverlapResolver sticky = new ClientOverlapResolver(0.25D);
        ClientOverlapResolver.Contender incumbent = new ClientOverlapResolver.Contender(5, 3.0D, false);
        ClientOverlapResolver.Contender close = new ClientOverlapResolver.Contender(1, 2.9D, false);
        ClientOverlapResolver.Contender clear = new ClientOverlapResolver.Contender(1, 2.7D, false);
        assertFalse(sticky.displaces(close, incumbent));
        assertTrue(sticky.displaces(clear, incumbent));
        assertEquals(5, sticky.resolve(List.of(close, incumbent), 5));
        assertEquals(1, sticky.resolve(List.of(close, incumbent), ClientOverlapResolver.NO_PORTAL));
        assertEquals(1, sticky.resolve(List.of(clear, incumbent), 5));
        assertEquals(1, sticky.resolve(List.of(close), 5));
        assertTrue(sticky.displaces(close, new ClientOverlapResolver.Contender(5, 1.0D, true)));
        assertEquals(ClientOverlapResolver.NO_PORTAL, sticky.resolve(List.of(), 5));
    }

    @Test
    void aNegativeMarginIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ClientOverlapResolver(-0.1D));
    }

    private static ClientOverlapResolver.Contender randomContender(Random random) {
        double distance = random.nextInt(4) == 0 ? random.nextInt(3) : random.nextDouble() * 6.0D;
        return new ClientOverlapResolver.Contender(random.nextInt(6), random.nextBoolean() ? distance : -distance, random.nextInt(3) == 0);
    }

    private static String tieKey(ClientOverlapResolver.Contender contender) {
        return String.format(Locale.ROOT, "%08d", contender.portalKey());
    }

    private static BlockClaim<String, Object> claim(ClientOverlapResolver.Contender contender) {
        return new BlockClaim<String, Object>("minecraft:stone", null, BlockClaim.NO_REMOTE_KEY, contender.maskAir());
    }
}
