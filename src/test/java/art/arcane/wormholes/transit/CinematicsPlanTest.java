package art.arcane.wormholes.transit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import art.arcane.wormholes.util.AxisAlignedBB;

final class CinematicsPlanTest {
    private static final double RANGE = 4.0D;

    @Test
    void approachCuesRampWithProximityAndStopOutsideTheRange() {
        ApproachCue cue = new ApproachCue();
        UUID player = UUID.randomUUID();
        UUID portal = UUID.randomUUID();

        assertNull(cue.plan(player, portal, 4.5D, RANGE, 1_000L), "outside the approach range");
        ApproachCue.Sound far = cue.plan(player, portal, 3.5D, RANGE, 2_000L);
        ApproachCue.Sound near = cue.plan(player, portal, 0.5D, RANGE, 3_000L);
        ApproachCue.Sound touching = cue.plan(player, portal, 0.0D, RANGE, 4_000L);

        assertNotNull(far);
        assertNotNull(near);
        assertTrue(near.pitch() > far.pitch(), "pitch rises as the player closes in");
        assertTrue(near.volume() > far.volume(), "volume rises as the player closes in");
        assertEquals(ApproachCue.MAX_PITCH, touching.pitch(), 1e-6F);
        assertEquals(ApproachCue.MAX_VOLUME, touching.volume(), 1e-6F);
        assertTrue(far.pitch() >= ApproachCue.MIN_PITCH && far.volume() >= ApproachCue.MIN_VOLUME);
    }

    @Test
    void approachCuesAreRateLimitedPerPlayerAndPortal() {
        ApproachCue cue = new ApproachCue();
        UUID player = UUID.randomUUID();
        UUID portal = UUID.randomUUID();
        UUID otherPortal = UUID.randomUUID();

        assertNotNull(cue.plan(player, portal, 2.0D, RANGE, 10_000L));
        assertNull(cue.plan(player, portal, 1.5D, RANGE, 10_000L + ApproachCue.MIN_INTERVAL_MILLIS - 1L), "too soon for the same pair");
        assertNotNull(cue.plan(player, otherPortal, 1.5D, RANGE, 10_000L + 10L), "another portal has its own clock");
        assertNotNull(cue.plan(player, portal, 1.0D, RANGE, 10_000L + ApproachCue.MIN_INTERVAL_MILLIS), "the interval has elapsed");
        assertNotNull(cue.plan(UUID.randomUUID(), portal, 1.0D, RANGE, 10_000L + 20L), "another player has their own clock");
    }

    @Test
    void forgettingAPlayerReleasesTheirClocks() {
        ApproachCue cue = new ApproachCue();
        UUID player = UUID.randomUUID();
        UUID portal = UUID.randomUUID();
        assertNotNull(cue.plan(player, portal, 2.0D, RANGE, 10_000L));
        cue.forget(player);
        assertNotNull(cue.plan(player, portal, 2.0D, RANGE, 10_001L));
        assertEquals(1, cue.tracked());
    }

    @Test
    void distanceToTheApertureBoxIsZeroInsideAndEuclideanOutside() {
        AxisAlignedBB box = new AxisAlignedBB(0.0D, 1.0D, 64.0D, 67.0D, 0.0D, 3.0D);
        assertEquals(0.0D, Cinematics.distanceTo(box, 0.5D, 65.0D, 1.5D), 1e-9D);
        assertEquals(2.0D, Cinematics.distanceTo(box, 3.0D, 65.0D, 1.5D), 1e-9D);
        assertEquals(5.0D, Cinematics.distanceTo(box, 4.0D, 65.0D, 7.0D), 1e-9D, "3 along x and 4 along z");
        assertEquals(1.0D, Cinematics.distanceTo(box, 0.5D, 63.0D, 1.5D), 1e-9D);
    }
}
