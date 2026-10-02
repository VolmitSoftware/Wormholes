package art.arcane.wormholes.portal.vanilla;

import org.junit.jupiter.api.Test;

import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NetherSiteSearchTest {
    @Test
    void highSourcePortalUsesActualGroundWithFullFrameHeadroom() {
        OptionalInt found = NetherSiteSearch.findBaseY(options(110, 3),
            (x, y, z) -> y <= 42 ? NetherSiteSearch.Cell.FLOOR : NetherSiteSearch.Cell.CLEAR);
        assertEquals(43, found.orElseThrow());
    }

    @Test
    void hazardousShelfAndObstructedHeadroomLoseToClearGround() {
        OptionalInt found = NetherSiteSearch.findBaseY(options(70, 3), (x, y, z) -> {
            if (y == 69 || y == 52) {
                return NetherSiteSearch.Cell.BLOCKED;
            }
            return y <= 40 || y == 50 ? NetherSiteSearch.Cell.FLOOR : NetherSiteSearch.Cell.CLEAR;
        });
        assertEquals(41, found.orElseThrow());
    }

    @Test
    void fullLandingFootprintWinsOverCloserSingleColumnSupport() {
        OptionalInt found = NetherSiteSearch.findBaseY(options(80, 3), (x, y, z) ->
            y <= 40 || x == 0 && z == 0 && y == 79 ? NetherSiteSearch.Cell.FLOOR : NetherSiteSearch.Cell.CLEAR);
        assertEquals(41, found.orElseThrow());
    }

    @Test
    void narrowNaturalGroundStillAllowsTheExistingSafetyPlatform() {
        OptionalInt found = NetherSiteSearch.findBaseY(options(80, 3), (x, y, z) ->
            x == 0 && z == 0 && y == 55 ? NetherSiteSearch.Cell.FLOOR : NetherSiteSearch.Cell.CLEAR);
        assertEquals(56, found.orElseThrow());
    }

    @Test
    void lavaOrUnloadedTerrainDoesNotMasqueradeAsGround() {
        assertTrue(NetherSiteSearch.findBaseY(options(80, 3),
            (x, y, z) -> y <= 32 ? NetherSiteSearch.Cell.BLOCKED : NetherSiteSearch.Cell.CLEAR).isEmpty());
        assertTrue(NetherSiteSearch.findBaseY(options(80, 3),
            (x, y, z) -> NetherSiteSearch.Cell.BLOCKED).isEmpty());
    }

    @Test
    void tallNetherFrameAndFallbackStayBelowTheBedrockRoof() {
        assertEquals(122, NetherSiteSearch.maximumBaseY(256, 3, true));
        int maximum = NetherSiteSearch.maximumBaseY(256, 21, true);
        assertEquals(104, maximum);
        NetherSiteSearch.Options search = new NetherSiteSearch.Options(0, 0, true, 2, 21, 191, 5, maximum);
        OptionalInt found = NetherSiteSearch.findBaseY(search,
            (x, y, z) -> y == 110 ? NetherSiteSearch.Cell.FLOOR : NetherSiteSearch.Cell.CLEAR);
        assertTrue(found.isEmpty());
        int fallback = Math.clamp(search.preferredBaseY(), search.minimumBaseY(), search.maximumBaseY());
        assertTrue(fallback + search.height() <= 125);
        assertEquals(296, NetherSiteSearch.maximumBaseY(320, 21, false));
    }

    @Test
    void tallFrameCannotUseAPlayersOnlyHeadroomGap() {
        assertTrue(NetherSiteSearch.findBaseY(options(41, 21), (x, y, z) -> {
            if (y == 40) {
                return NetherSiteSearch.Cell.FLOOR;
            }
            return y >= 45 ? NetherSiteSearch.Cell.BLOCKED : NetherSiteSearch.Cell.CLEAR;
        }).isEmpty());
    }

    @Test
    void searchReadsOnlyBoundedHeightAndExistingMutationFootprint() {
        AtomicInteger reads = new AtomicInteger();
        NetherSiteSearch.Options search = new NetherSiteSearch.Options(-16, 15, false, 8, 3, 80, 5, 120);
        OptionalInt found = NetherSiteSearch.findBaseY(search, (x, y, z) -> {
            reads.incrementAndGet();
            assertTrue(x >= -18 && x <= -14);
            assertTrue(z >= 8 && z <= 21);
            assertTrue(y >= 4 && y <= 123);
            return y <= 40 ? NetherSiteSearch.Cell.FLOOR : NetherSiteSearch.Cell.CLEAR;
        });
        assertEquals(41, found.orElseThrow());
        assertTrue(reads.get() < 2_000);
    }
    private static NetherSiteSearch.Options options(int preferred, int height) {
        return new NetherSiteSearch.Options(0, 0, true, 2, height, preferred, 5, 120);
    }
}
