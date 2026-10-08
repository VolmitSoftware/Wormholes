package art.arcane.wormholes.transit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import art.arcane.optics.crossing.ScaleRule;

final class ScaleRuleChangeTest {
    private static final ScaleRule CURRENT = ScaleRuleSettings.of(ScaleRule.Mode.MOTION, 0.5D, 2.0D);

    @Test
    void noValuesShowTheCurrentRule() {
        ScaleRuleChange change = ScaleRuleChange.request("", "", "", CURRENT);

        assertEquals(ScaleRuleChange.Status.SHOWN, change.status());
        assertEquals(CURRENT, change.rule());
    }

    @Test
    void modeAndBoundsSetTogetherAndKeepUnspecifiedValues() {
        assertEquals(ScaleRuleSettings.of(ScaleRule.Mode.RATIO, 0.5D, 2.0D), ScaleRuleChange.request("ratio", "", "", CURRENT).rule());
        assertEquals(ScaleRuleSettings.of(ScaleRule.Mode.RATIO, 0.25D, 4.0D), ScaleRuleChange.request("RATIO", "0.25", "4", CURRENT).rule());
        assertEquals(ScaleRuleSettings.of(ScaleRule.Mode.MOTION, 0.5D, 8.0D), ScaleRuleChange.request("", "", "8", CURRENT).rule());
        assertEquals(ScaleRuleChange.Status.SET, ScaleRuleChange.request("off", "", "", CURRENT).status());
    }

    @Test
    void unreadableValuesAreRefusedWithTheirReason() {
        ScaleRuleChange mode = ScaleRuleChange.request("grow", "", "", CURRENT);
        ScaleRuleChange min = ScaleRuleChange.request("ratio", "tiny", "", CURRENT);
        ScaleRuleChange inverted = ScaleRuleChange.request("ratio", "4", "2", CURRENT);

        assertEquals(ScaleRuleChange.Status.INVALID, mode.status());
        assertTrue(mode.reason().contains("grow"));
        assertEquals(ScaleRuleChange.Status.INVALID, min.status());
        assertTrue(min.reason().contains("tiny"));
        assertEquals(ScaleRuleChange.Status.INVALID, inverted.status());
        assertEquals(CURRENT, inverted.rule());
    }

    @Test
    void boundsClampIntoTheAttributeRange() {
        ScaleRule rule = ScaleRuleChange.request("ratio", "0.001", "100", CURRENT).rule();

        assertEquals(ScaleRule.ATTRIBUTE_MIN, rule.min(), 0.0D);
        assertEquals(ScaleRule.ATTRIBUTE_MAX, rule.max(), 0.0D);
    }

    @Test
    void describesTheRuleForMessages() {
        assertEquals("motion", ScaleRuleChange.mode(CURRENT));
        assertEquals("0.5-2", ScaleRuleChange.range(CURRENT));
    }
}
