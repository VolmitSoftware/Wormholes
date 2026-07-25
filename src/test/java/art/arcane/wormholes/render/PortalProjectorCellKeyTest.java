package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

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
            long key = ProjectionCellKey.pack(coordinate[0], coordinate[1], coordinate[2]);
            assertEquals(coordinate[0], ProjectionCellKey.unpackX(key), "x round trip");
            assertEquals(coordinate[1], ProjectionCellKey.unpackY(key), "y round trip");
            assertEquals(coordinate[2], ProjectionCellKey.unpackZ(key), "z round trip");
        }
    }

    @Test
    public void distinctCellsNeverCollideInsideTheSupportedRange() {
        assertNotEquals(ProjectionCellKey.pack(1, 64, 2), ProjectionCellKey.pack(2, 64, 1));
        assertNotEquals(ProjectionCellKey.pack(-1, 64, 2), ProjectionCellKey.pack(1, 64, 2));
        assertNotEquals(ProjectionCellKey.pack(1, -64, 2), ProjectionCellKey.pack(1, 64, 2));
    }

    @Test
    public void theClaimArbiterSectionDecoderUsesTheSameLayoutAsTheCellKey() {
        int[][] sections = new int[][] {
            {0, 4, 0},
            {-1, -2, -2},
            {1_875_000, 19, -1_875_000}
        };
        for (int[] section : sections) {
            long sectionKey = ProjectionCellKey.pack(section[0], section[1], section[2]);
            assertEquals(ProjectionCellKey.unpackX(sectionKey), ProjectionClaimArbiter.unpackSectionX(sectionKey),
                "the section decoder must not drift from the cell key layout");
            assertEquals(ProjectionCellKey.unpackY(sectionKey), ProjectionClaimArbiter.unpackSectionY(sectionKey),
                "the section decoder must not drift from the cell key layout");
            assertEquals(ProjectionCellKey.unpackZ(sectionKey), ProjectionClaimArbiter.unpackSectionZ(sectionKey),
                "the section decoder must not drift from the cell key layout");
            assertEquals(section[0], ProjectionClaimArbiter.unpackSectionX(sectionKey));
            assertEquals(section[1], ProjectionClaimArbiter.unpackSectionY(sectionKey));
            assertEquals(section[2], ProjectionClaimArbiter.unpackSectionZ(sectionKey));
        }
    }

    @Test
    public void theSentinelRemoteKeyIsTheOneDefinedByTheClaimThatStoresIt() {
        assertEquals(Long.MIN_VALUE, ProjectedBlockClaim.NO_REMOTE_KEY,
            "the light sentinel must stay outside the packed cell key range");
        assertNotEquals(ProjectedBlockClaim.NO_REMOTE_KEY, ProjectionCellKey.pack(-30_000_000, -2032, -30_000_000),
            "no reachable cell may collide with the no-remote-light sentinel");
        assertNotEquals(ProjectedBlockClaim.NO_REMOTE_KEY, ProjectionCellKey.pack(0, 0, 0));
    }
}
