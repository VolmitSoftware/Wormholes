package art.arcane.wormholes.nexus;

import art.arcane.volmlib.util.json.JSONObject;
import art.arcane.wormholes.nexus.DestinationEntry.TargetKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DestinationPolicyTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID OTHER_PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

    @Test
    void policyRoundTripsThroughJsonIncludingWindowsWeightsAndLabels() {
        DestinationPolicy original = new DestinationPolicy(DestinationMode.WEIGHTED, List.of(
                new DestinationEntry(TargetKind.ADDRESS, "AB12", 3, 0, 0, "market"),
                new DestinationEntry(TargetKind.REMOTE, "beta/" + PLAYER, 1, 13000, 23000, "night")),
                SelectionRule.RANDOM);

        DestinationPolicy decoded = DestinationPolicy.fromJSON(original.toJSON());

        assertEquals(original, decoded);
        assertEquals(2, decoded.entries().size());
        assertEquals("beta", decoded.entries().get(1).remoteServer());
        assertEquals(PLAYER, decoded.entries().get(1).remotePortalId());
        assertEquals("night", decoded.entries().get(1).label());
    }

    @Test
    void singleModeLeavesTheOrdinaryTunnelAlone() {
        DestinationPolicy policy = new DestinationPolicy(DestinationMode.SINGLE, List.of(
                new DestinationEntry(TargetKind.ADDRESS, "AB12", 1, 0, 0, "")), SelectionRule.ROUND_ROBIN);

        assertFalse(policy.isActive());
        assertNull(policy.choose(0L, PLAYER, true, false, new Random(1L)));
    }

    @Test
    void orderedModeTakesTheFirstEntryWhoseWindowContainsTheWorldTime() {
        DestinationPolicy policy = new DestinationPolicy(DestinationMode.ORDERED, List.of(
                new DestinationEntry(TargetKind.ADDRESS, "DAY", 1, 0, 12000, "day"),
                new DestinationEntry(TargetKind.ADDRESS, "NIGHT", 1, 12000, 24000, "night"),
                new DestinationEntry(TargetKind.ADDRESS, "ALWAYS", 1, 0, 0, "always")),
                SelectionRule.ROUND_ROBIN);

        assertEquals("DAY", policy.choose(6000L, PLAYER, true, false, new Random(1L)).target());
        assertEquals("NIGHT", policy.choose(18000L, PLAYER, true, false, new Random(1L)).target());
        assertEquals("DAY", policy.choose(24000L + 100L, PLAYER, true, false, new Random(1L)).target());
    }

    @Test
    void scheduledModeReturnsNothingOutsideEveryWindow() {
        DestinationPolicy policy = new DestinationPolicy(DestinationMode.SCHEDULED, List.of(
                new DestinationEntry(TargetKind.ADDRESS, "DAWN", 1, 0, 1000, "dawn")), SelectionRule.ROUND_ROBIN);

        assertEquals("DAWN", policy.choose(500L, PLAYER, true, false, new Random(1L)).target());
        assertNull(policy.choose(5000L, PLAYER, true, false, new Random(1L)));
    }

    @Test
    void windowsThatWrapPastMidnightStayOpenAcrossTheBoundary() {
        DestinationEntry night = new DestinationEntry(TargetKind.ADDRESS, "NIGHT", 1, 20000, 4000, "night");

        assertTrue(night.windowContains(22000L));
        assertTrue(night.windowContains(1000L));
        assertFalse(night.windowContains(12000L));
    }

    @Test
    void weightedModeHonoursWeightsAndSkipsEntriesOutsideTheirWindow() {
        DestinationPolicy policy = new DestinationPolicy(DestinationMode.WEIGHTED, List.of(
                new DestinationEntry(TargetKind.ADDRESS, "HEAVY", 9, 0, 0, ""),
                new DestinationEntry(TargetKind.ADDRESS, "LIGHT", 1, 0, 0, ""),
                new DestinationEntry(TargetKind.ADDRESS, "CLOSED", 50, 12000, 13000, "")),
                SelectionRule.ROUND_ROBIN);

        int heavy = 0;
        Random random = new Random(42L);
        for (int draw = 0; draw < 1000; draw++) {
            String target = policy.choose(0L, PLAYER, true, false, random).target();
            assertFalse("CLOSED".equals(target), "picked an entry outside its window");
            if ("HEAVY".equals(target)) {
                heavy++;
            }
        }
        assertTrue(heavy > 800 && heavy < 980, "weighted draw skewed wrong: " + heavy);
    }

    @Test
    void perPlayerModeIsStableForOnePlayerAndSpreadsAcrossPlayers() {
        DestinationPolicy policy = new DestinationPolicy(DestinationMode.PER_PLAYER, List.of(
                new DestinationEntry(TargetKind.ADDRESS, "AAAA", 1, 0, 0, ""),
                new DestinationEntry(TargetKind.ADDRESS, "BBBB", 1, 0, 0, "")),
                SelectionRule.ROUND_ROBIN);

        DestinationEntry first = policy.choose(0L, PLAYER, true, false, new Random(1L));
        DestinationEntry again = policy.choose(9999L, PLAYER, false, true, new Random(2L));
        assertEquals(first, again);

        int distinct = 0;
        for (int index = 0; index < 64; index++) {
            if (!policy.choose(0L, UUID.randomUUID(), true, false, new Random(index)).equals(first)) {
                distinct++;
            }
        }
        assertTrue(distinct > 0, "per-player picks never varied across players");
        assertNotNull(policy.choose(0L, OTHER_PLAYER, true, false, new Random(1L)));
    }

    @Test
    void returnModeDefersToTheResolverInsteadOfPickingAnEntry() {
        DestinationPolicy policy = new DestinationPolicy(DestinationMode.RETURN, List.of(
                new DestinationEntry(TargetKind.ADDRESS, "AAAA", 1, 0, 0, "")), SelectionRule.ROUND_ROBIN);

        assertTrue(policy.isActive());
        assertNull(policy.choose(0L, PLAYER, true, false, new Random(1L)));
    }

    @Test
    void entrySideAndSneakSelectionRulesOverrideTheModeWithTheFirstTwoEntries() {
        List<DestinationEntry> entries = List.of(
                new DestinationEntry(TargetKind.ADDRESS, "FRONT", 1, 0, 0, ""),
                new DestinationEntry(TargetKind.ADDRESS, "BACK", 1, 0, 0, ""));
        DestinationPolicy side = new DestinationPolicy(DestinationMode.ORDERED, entries, SelectionRule.ENTRY_SIDE);
        DestinationPolicy sneak = new DestinationPolicy(DestinationMode.ORDERED, entries, SelectionRule.SNEAK);

        assertEquals("FRONT", side.choose(0L, PLAYER, true, false, new Random(1L)).target());
        assertEquals("BACK", side.choose(0L, PLAYER, false, false, new Random(1L)).target());
        assertEquals("FRONT", sneak.choose(0L, PLAYER, true, false, new Random(1L)).target());
        assertEquals("BACK", sneak.choose(0L, PLAYER, true, true, new Random(1L)).target());
    }

    @Test
    void emptyPolicyChoosesNothingAndDecodesFromAnEmptyDocument() {
        DestinationPolicy empty = DestinationPolicy.fromJSON(new JSONObject());

        assertEquals(DestinationMode.SINGLE, empty.mode());
        assertTrue(empty.entries().isEmpty());
        assertNull(empty.choose(0L, PLAYER, true, false, new Random(1L)));
    }
}
