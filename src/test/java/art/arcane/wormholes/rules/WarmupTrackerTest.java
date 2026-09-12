package art.arcane.wormholes.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class WarmupTrackerTest {
    private static final UUID PLAYER = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID PORTAL = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID OTHER_PORTAL = UUID.fromString("55555555-5555-4555-8555-555555555555");

    private RecordingPinner pinner;
    private WarmupTracker tracker;
    private World world;

    @BeforeEach
    void freshTracker() {
        pinner = new RecordingPinner();
        tracker = new WarmupTracker(pinner);
        world = RulesTestSupport.world("warmup");
    }

    @Test
    void aZeroWarmupNeverDefers() {
        assertEquals(WarmupTracker.Decision.ALLOW, tracker.begin(PLAYER, PORTAL, 0L, at(0.0D), 1000L));
        assertTrue(pinner.scheduled.isEmpty());
    }

    @Test
    void theFirstCrossingDefersAndTheWarmupAllowsOnceItElapses() {
        assertEquals(WarmupTracker.Decision.DEFER, tracker.begin(PLAYER, PORTAL, 3000L, at(0.0D), 1000L));
        assertEquals(List.of(PLAYER), pinner.scheduled);
        assertEquals(WarmupTracker.Decision.DEFER, tracker.begin(PLAYER, PORTAL, 3000L, at(0.0D), 2000L));
        assertEquals(1, pinner.scheduled.size());

        assertEquals(WarmupTracker.Decision.ALLOW, tracker.begin(PLAYER, PORTAL, 3000L, at(0.0D), 4000L));
        assertEquals(WarmupTracker.Decision.ALLOW, tracker.begin(PLAYER, PORTAL, 3000L, at(0.0D), 6000L));
    }

    @Test
    void theGraceExpiresAndTheNextCrossingWarmsUpAgain() {
        tracker.begin(PLAYER, PORTAL, 3000L, at(0.0D), 1000L);
        assertEquals(WarmupTracker.Decision.ALLOW, tracker.begin(PLAYER, PORTAL, 3000L, at(0.0D), 4000L));

        assertEquals(WarmupTracker.Decision.DEFER, tracker.begin(PLAYER, PORTAL, 3000L, at(0.0D), 7001L));
    }

    @Test
    void driftingBeyondTheAllowanceCancelsAndRefusesTheNextCrossing() {
        tracker.begin(PLAYER, PORTAL, 3000L, at(0.0D), 1000L);

        assertFalse(tracker.cancelOnMove(PLAYER, at(0.4D), 0.5D, 1500L));
        assertTrue(tracker.cancelOnMove(PLAYER, at(0.9D), 0.5D, 1600L));
        assertEquals(List.of(PLAYER), pinner.cancelled);

        assertEquals(WarmupTracker.Decision.CANCELLED, tracker.begin(PLAYER, PORTAL, 3000L, at(0.9D), 1700L));
        assertEquals(WarmupTracker.Decision.DEFER, tracker.begin(PLAYER, PORTAL, 3000L, at(0.9D), 4601L));
    }

    @Test
    void takingDamageCancelsTheWarmup() {
        tracker.begin(PLAYER, PORTAL, 3000L, at(0.0D), 1000L);

        assertTrue(tracker.cancelOnDamage(PLAYER, 1200L));
        assertFalse(tracker.cancelOnDamage(PLAYER, 1300L));
        assertEquals(WarmupTracker.Decision.CANCELLED, tracker.begin(PLAYER, PORTAL, 3000L, at(0.0D), 1400L));
    }

    @Test
    void steppingIntoAnotherPortalRestartsTheWarmup() {
        tracker.begin(PLAYER, PORTAL, 3000L, at(0.0D), 1000L);

        assertEquals(WarmupTracker.Decision.DEFER, tracker.begin(PLAYER, OTHER_PORTAL, 3000L, at(0.0D), 2000L));
        assertEquals(WarmupTracker.Decision.DEFER, tracker.begin(PLAYER, OTHER_PORTAL, 3000L, at(0.0D), 4000L));
        assertEquals(WarmupTracker.Decision.ALLOW, tracker.begin(PLAYER, OTHER_PORTAL, 3000L, at(0.0D), 5000L));
    }

    @Test
    void tickingPinsTheTravelerAndCountsDownOncePerSecondUntilTheWarmupEnds() {
        tracker.begin(PLAYER, PORTAL, 3000L, at(0.0D), 1000L);

        assertTrue(tracker.tick(PLAYER, 1000L));
        assertTrue(tracker.tick(PLAYER, 1050L));
        assertTrue(tracker.tick(PLAYER, 2100L));
        assertEquals(List.of(3L, 2L), pinner.countdowns);

        assertFalse(tracker.tick(PLAYER, 4000L));
        assertFalse(tracker.tick(UUID.randomUUID(), 4000L));
    }

    @Test
    void quittingDropsTheWarmup() {
        tracker.begin(PLAYER, PORTAL, 3000L, at(0.0D), 1000L);

        tracker.clear(PLAYER);

        assertFalse(tracker.tick(PLAYER, 1500L));
        assertEquals(WarmupTracker.Decision.DEFER, tracker.begin(PLAYER, PORTAL, 3000L, at(0.0D), 1600L));
    }

    private Location at(double x) {
        return new Location(world, x, 64.0D, 0.0D);
    }

    private static final class RecordingPinner implements WarmupTracker.Pinner {
        private final List<UUID> scheduled = new ArrayList<>();
        private final List<UUID> cancelled = new ArrayList<>();
        private final List<Long> countdowns = new ArrayList<>();

        @Override
        public void schedule(UUID playerId) {
            scheduled.add(playerId);
        }

        @Override
        public void pin(UUID playerId, long secondsLeft) {
            countdowns.add(Long.valueOf(secondsLeft));
        }

        @Override
        public void cancelled(UUID playerId) {
            cancelled.add(playerId);
        }
    }
}
