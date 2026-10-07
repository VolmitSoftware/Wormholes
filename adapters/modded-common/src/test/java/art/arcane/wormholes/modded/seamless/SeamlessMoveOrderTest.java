package art.arcane.wormholes.modded.seamless;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SeamlessMoveOrderTest {
    @Test
    public void crossLevelAcceptsBeforeTheLevelMoveAndKeepsTheDeliveredViewBeforeJoining() {
        Recorder steps = new Recorder(true, true);

        assertTrue(SeamlessMove.crossLevel(steps));

        assertEquals(List.of("moving:true", "allow", "accept", "departLevel", "enterLevel", "handOver", "addToLevel",
            "dimensionTriggers", "levelInfo", "levelChanged", "moving:false"), steps.calls);
    }

    @Test
    public void failedHandOverFallsBackToVanillaTrackingAndStillJoinsTheDestination() {
        Recorder steps = new Recorder(true, true);
        steps.failHandOver = true;

        assertTrue(SeamlessMove.crossLevel(steps));

        assertEquals(List.of("moving:true", "allow", "accept", "departLevel", "enterLevel", "handOver", "abandonHandOver", "addToLevel",
            "dimensionTriggers", "levelInfo", "levelChanged", "moving:false"), steps.calls);
    }

    @Test
    public void failedResidentSameLevelHandOverStillTracksTheMove() {
        Recorder steps = new Recorder(true, true);
        steps.failHandOver = true;

        assertTrue(SeamlessMove.sameLevel(steps, true));

        assertEquals(List.of("accept", "departView", "reposition", "handOver", "abandonHandOver", "track"), steps.calls);
    }

    @Test
    public void cancelledLevelChangeNeverAcceptsOrMoves() {
        Recorder steps = new Recorder(false, true);

        assertFalse(SeamlessMove.crossLevel(steps));

        assertEquals(List.of("moving:true", "allow", "moving:false"), steps.calls);
    }

    @Test
    public void unsentAcceptLeavesThePlayerWhereTheServerHasThem() {
        Recorder cross = new Recorder(true, false);
        Recorder same = new Recorder(true, false);

        assertFalse(SeamlessMove.crossLevel(cross));
        assertFalse(SeamlessMove.sameLevel(same, true));

        assertEquals(List.of("moving:true", "allow", "accept", "moving:false"), cross.calls);
        assertEquals(List.of("accept"), same.calls);
    }

    @Test
    public void residentSameLevelMoveHandsOverBeforeChunkTracking() {
        Recorder steps = new Recorder(true, true);

        assertTrue(SeamlessMove.sameLevel(steps, true));

        assertEquals(List.of("accept", "departView", "reposition", "handOver", "track"), steps.calls);
    }

    @Test
    public void nearSameLevelMoveOnlyRepositionsAndTracks() {
        Recorder steps = new Recorder(true, true);

        assertTrue(SeamlessMove.sameLevel(steps, false));

        assertEquals(List.of("accept", "reposition", "track"), steps.calls);
    }

    private static final class Recorder implements SeamlessMove.Steps {
        private final List<String> calls = new ArrayList<>();
        private final boolean allow;
        private final boolean sent;
        private boolean failHandOver;

        private Recorder(boolean allow, boolean sent) {
            this.allow = allow;
            this.sent = sent;
        }

        @Override
        public void moving(boolean moving) {
            calls.add("moving:" + moving);
        }

        @Override
        public boolean allowLevelChange() {
            calls.add("allow");
            return allow;
        }

        @Override
        public boolean accept() {
            calls.add("accept");
            return sent;
        }

        @Override
        public void departLevel() {
            calls.add("departLevel");
        }

        @Override
        public void departView() {
            calls.add("departView");
        }

        @Override
        public void enterLevel() {
            calls.add("enterLevel");
        }

        @Override
        public void handOver() {
            calls.add("handOver");
            if (failHandOver) {
                throw new IllegalStateException("handover bookkeeping failed");
            }
        }

        @Override
        public void abandonHandOver(RuntimeException failure) {
            calls.add("abandonHandOver");
        }

        @Override
        public void addToLevel() {
            calls.add("addToLevel");
        }

        @Override
        public void dimensionTriggers() {
            calls.add("dimensionTriggers");
        }

        @Override
        public void levelInfo() {
            calls.add("levelInfo");
        }

        @Override
        public void levelChanged() {
            calls.add("levelChanged");
        }

        @Override
        public void reposition() {
            calls.add("reposition");
        }

        @Override
        public void track() {
            calls.add("track");
        }
    }
}
