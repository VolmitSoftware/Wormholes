package art.arcane.wormholes.atlas;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtlasGuideTest {
    @Test
    void aTargetStraightAheadPointsForwardWhicheverWayTheViewerFaces() {
        assertEquals(0, AtlasGuide.sector(0.0F, 0.0D, 10.0D));
        assertEquals(0, AtlasGuide.sector(90.0F, -10.0D, 0.0D));
        assertEquals(0, AtlasGuide.sector(180.0F, 0.0D, -10.0D));
        assertEquals(0, AtlasGuide.sector(-90.0F, 10.0D, 0.0D));
    }

    @Test
    void theEightSectorsWalkClockwiseFromAheadWhileFacingSouth() {
        assertEquals(0, AtlasGuide.sector(0.0F, 0.0D, 10.0D));
        assertEquals(1, AtlasGuide.sector(0.0F, -10.0D, 10.0D));
        assertEquals(2, AtlasGuide.sector(0.0F, -10.0D, 0.0D));
        assertEquals(3, AtlasGuide.sector(0.0F, -10.0D, -10.0D));
        assertEquals(4, AtlasGuide.sector(0.0F, 0.0D, -10.0D));
        assertEquals(5, AtlasGuide.sector(0.0F, 10.0D, -10.0D));
        assertEquals(6, AtlasGuide.sector(0.0F, 10.0D, 0.0D));
        assertEquals(7, AtlasGuide.sector(0.0F, 10.0D, 10.0D));
    }

    @Test
    void yawWrapsPastThreeHundredAndSixtyDegreesWithoutChangingTheSector() {
        assertEquals(2, AtlasGuide.sector(720.0F, -10.0D, 0.0D));
        assertEquals(6, AtlasGuide.sector(-720.0F, 10.0D, 0.0D));
    }

    @Test
    void everySectorHasItsOwnPlainTextArrow() {
        assertEquals("^", AtlasGuide.arrow(0));
        assertEquals("^>", AtlasGuide.arrow(1));
        assertEquals(">", AtlasGuide.arrow(2));
        assertEquals("v>", AtlasGuide.arrow(3));
        assertEquals("v", AtlasGuide.arrow(4));
        assertEquals("<v", AtlasGuide.arrow(5));
        assertEquals("<", AtlasGuide.arrow(6));
        assertEquals("<^", AtlasGuide.arrow(7));
    }

    @Test
    void theBearingReadsAsAnArrowFollowedByWholeBlocks() {
        assertEquals("^ 10m", AtlasGuide.bearing(0.0F, 0.0D, 10.0D));
        assertEquals("> 20m", AtlasGuide.bearing(0.0F, -20.0D, 0.0D));
        assertEquals("<^ 14m", AtlasGuide.bearing(0.0F, 10.0D, 10.0D));
    }

    @Test
    void aTargetUnderfootStillReadsAsAheadRatherThanFailing() {
        String bearing = AtlasGuide.bearing(37.0F, 0.0D, 0.0D);

        assertTrue(bearing.endsWith("0m"), bearing);
        assertEquals("^ 0m", bearing);
    }
}
