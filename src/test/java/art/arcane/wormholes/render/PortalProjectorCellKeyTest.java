package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;
import art.arcane.optics.claim.BlockClaim;
import art.arcane.optics.math.CellKeys;

public final class PortalProjectorCellKeyTest {
    @Test
    public void everyCellCoordinateRoundTripsThroughTheSingleKeyLayout() {
        int[][] coordinates = new int[][] {
            {0, 0, 0},
            {1, 64, 2},
            {-1, -64, -2},
            {30_000_000, 319, -30_000_000},
            {-30_000_000, -2032, 30_000_000},
            {15, 2031, -15}
        };
        for (int[] coordinate : coordinates) {
            long key = CellKeys.pack(coordinate[0], coordinate[1], coordinate[2]);
            assertEquals(coordinate[0], CellKeys.unpackX(key), "x round trip");
            assertEquals(coordinate[1], CellKeys.unpackY(key), "y round trip");
            assertEquals(coordinate[2], CellKeys.unpackZ(key), "z round trip");
        }
    }

    @Test
    public void distinctCellsNeverCollideInsideTheSupportedRange() {
        assertNotEquals(CellKeys.pack(1, 64, 2), CellKeys.pack(2, 64, 1));
        assertNotEquals(CellKeys.pack(-1, 64, 2), CellKeys.pack(1, 64, 2));
        assertNotEquals(CellKeys.pack(1, -64, 2), CellKeys.pack(1, 64, 2));
    }

    @Test
    public void theClaimArbiterSectionDecoderUsesTheSameLayoutAsTheCellKey() {
        int[][] sections = new int[][] {
            {0, 4, 0},
            {-1, -2, -2},
            {1_875_000, 19, -1_875_000}
        };
        for (int[] section : sections) {
            long sectionKey = CellKeys.pack(section[0], section[1], section[2]);
            assertEquals(CellKeys.unpackX(sectionKey), ProjectionClaimArbiter.unpackSectionX(sectionKey),
                "the section decoder must not drift from the cell key layout");
            assertEquals(CellKeys.unpackY(sectionKey), ProjectionClaimArbiter.unpackSectionY(sectionKey),
                "the section decoder must not drift from the cell key layout");
            assertEquals(CellKeys.unpackZ(sectionKey), ProjectionClaimArbiter.unpackSectionZ(sectionKey),
                "the section decoder must not drift from the cell key layout");
            assertEquals(section[0], ProjectionClaimArbiter.unpackSectionX(sectionKey));
            assertEquals(section[1], ProjectionClaimArbiter.unpackSectionY(sectionKey));
            assertEquals(section[2], ProjectionClaimArbiter.unpackSectionZ(sectionKey));
        }
    }

    @Test
    public void theSentinelRemoteKeyIsTheOneDefinedByTheClaimThatStoresIt() {
        assertEquals(Long.MIN_VALUE, BlockClaim.NO_REMOTE_KEY,
            "the light sentinel must stay outside the packed cell key range");
        assertNotEquals(BlockClaim.NO_REMOTE_KEY, CellKeys.pack(-30_000_000, -2032, -30_000_000),
            "no reachable cell may collide with the no-remote-light sentinel");
        assertNotEquals(BlockClaim.NO_REMOTE_KEY, CellKeys.pack(0, 0, 0));
    }
}
