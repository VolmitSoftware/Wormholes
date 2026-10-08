package art.arcane.wormholes.transit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import art.arcane.optics.crossing.ScaleRule;

final class ScaleRuleSettingsTest {
    @Test
    void absentSettingsDefaultToOffWithQuarterToFourBounds() {
        ScaleRule rule = ScaleRuleSettings.parse(null, null, null);

        assertEquals(ScaleRule.Mode.OFF, rule.mode());
        assertEquals(0.25D, rule.min(), 0.0D);
        assertEquals(4.0D, rule.max(), 0.0D);
        assertEquals(ScaleRuleSettings.DEFAULT, rule);
    }

    @Test
    void parsesModesCaseInsensitivelyAndKeepsBounds() {
        assertEquals(ScaleRule.ratio(0.5D, 3.0D), ScaleRuleSettings.parse("RATIO", "0.5", "3"));
        assertEquals(new ScaleRule(ScaleRule.Mode.MOTION, 0.25D, 4.0D), ScaleRuleSettings.parse(" motion ", "", ""));
        assertEquals(ScaleRuleSettings.DEFAULT, ScaleRuleSettings.parse("sideways", "x", "-"));
    }

    @Test
    void boundsClampIntoTheAttributeRangeAndReorder() {
        ScaleRule clamped = ScaleRuleSettings.parse("ratio", "0.001", "99");
        assertEquals(ScaleRule.ATTRIBUTE_MIN, clamped.min(), 0.0D);
        assertEquals(ScaleRule.ATTRIBUTE_MAX, clamped.max(), 0.0D);

        ScaleRule reordered = ScaleRuleSettings.of(ScaleRule.Mode.RATIO, 6.0D, 2.0D);
        assertEquals(2.0D, reordered.min(), 0.0D);
        assertEquals(6.0D, reordered.max(), 0.0D);

        ScaleRule nonFinite = ScaleRuleSettings.parse("ratio", "NaN", "Infinity");
        assertEquals(0.25D, nonFinite.min(), 0.0D);
        assertEquals(4.0D, nonFinite.max(), 0.0D);
    }

    @Test
    void formatsCanonicalTextThatParsesBack() {
        ScaleRule rule = ScaleRuleSettings.of(ScaleRule.Mode.RATIO, 0.5D, 3.25D);

        assertEquals("ratio", ScaleRuleSettings.format(rule.mode()));
        assertEquals("0.5", ScaleRuleSettings.format(rule.min()));
        assertEquals("3.25", ScaleRuleSettings.format(rule.max()));
        assertEquals("4", ScaleRuleSettings.format(4.0D));
        assertEquals(rule, ScaleRuleSettings.parse(ScaleRuleSettings.format(rule.mode()), ScaleRuleSettings.format(rule.min()),
            ScaleRuleSettings.format(rule.max())));
    }

    @Test
    void modeAndBoundTextRejectGarbage() {
        assertEquals(ScaleRule.Mode.RATIO, ScaleRuleSettings.mode("Ratio"));
        assertNull(ScaleRuleSettings.mode("grow"));
        assertNull(ScaleRuleSettings.mode(null));
        assertEquals(2.5D, ScaleRuleSettings.bound("2.5"), 0.0D);
        assertNull(ScaleRuleSettings.bound("NaN"));
        assertNull(ScaleRuleSettings.bound("0"));
        assertNull(ScaleRuleSettings.bound("-1"));
        assertNull(ScaleRuleSettings.bound("big"));
    }

    @Test
    void modesCycleOffMotionRatio() {
        assertEquals(ScaleRule.Mode.MOTION, ScaleRuleSettings.next(ScaleRule.Mode.OFF));
        assertEquals(ScaleRule.Mode.RATIO, ScaleRuleSettings.next(ScaleRule.Mode.MOTION));
        assertEquals(ScaleRule.Mode.OFF, ScaleRuleSettings.next(ScaleRule.Mode.RATIO));
    }

    @Test
    void withModeAndBoundsKeepTheOtherFields() {
        ScaleRule rule = ScaleRuleSettings.of(ScaleRule.Mode.MOTION, 0.5D, 2.0D);

        assertEquals(ScaleRuleSettings.of(ScaleRule.Mode.RATIO, 0.5D, 2.0D), ScaleRuleSettings.withMode(rule, ScaleRule.Mode.RATIO));
        assertEquals(ScaleRuleSettings.of(ScaleRule.Mode.MOTION, 1.5D, 2.0D), ScaleRuleSettings.withMin(rule, 1.5D));
        assertEquals(ScaleRuleSettings.of(ScaleRule.Mode.MOTION, 0.5D, 8.0D), ScaleRuleSettings.withMax(rule, 8.0D));
        assertEquals(ScaleRuleSettings.of(ScaleRule.Mode.MOTION, 3.0D, 3.0D), ScaleRuleSettings.withMin(rule, 3.0D));
        assertEquals(ScaleRuleSettings.of(ScaleRule.Mode.MOTION, 0.25D, 0.25D), ScaleRuleSettings.withMax(rule, 0.25D));
    }

    @Test
    void defaultsAreTheOnlyValuesLeftOutOfPersistence() {
        assertEquals(true, ScaleRuleSettings.isDefault(ScaleRuleSettings.DEFAULT));
        assertEquals(false, ScaleRuleSettings.isDefault(ScaleRuleSettings.of(ScaleRule.Mode.OFF, 0.5D, 4.0D)));
        assertEquals(false, ScaleRuleSettings.isDefault(ScaleRuleSettings.of(ScaleRule.Mode.RATIO, 0.25D, 4.0D)));
    }
}
