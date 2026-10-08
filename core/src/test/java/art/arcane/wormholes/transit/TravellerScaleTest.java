package art.arcane.wormholes.transit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import art.arcane.optics.aperture.SizeRatio;
import art.arcane.optics.crossing.ScaleRule;
import art.arcane.optics.spi.ScaleAccess;

final class TravellerScaleTest {
    @Test
    void ratioRuleCompoundsTheFactorAndTheInverseCrossingRestoresIt() {
        Factors factors = new Factors();
        TravellerScale<String> scale = new TravellerScale<>(factors);
        ScaleRule rule = ScaleRule.ratio(0.25D, 4.0D);

        assertEquals(3.0D, scale.cross("steve", rule, new SizeRatio(3.0D, true)), 1.0E-12D);
        assertEquals(3.0D, factors.scale("steve"), 1.0E-12D);
        assertEquals(1.0D, scale.cross("steve", rule, new SizeRatio(1.0D / 3.0D, true)), 1.0E-12D);
        assertEquals(1.0D, factors.scale("steve"), 1.0E-12D);
    }

    @Test
    void ratioRuleClampsRunawayLoops() {
        Factors factors = new Factors();
        TravellerScale<String> scale = new TravellerScale<>(factors);
        ScaleRule rule = ScaleRule.ratio(0.25D, 4.0D);
        double factor = 1.0D;
        for (int pass = 0; pass < 5; pass++) {
            factor = scale.cross("pig", rule, new SizeRatio(3.0D, true));
        }

        assertEquals(4.0D, factor, 0.0D);
        assertEquals(4.0D, factors.scale("pig"), 0.0D);
    }

    @Test
    void offAndMotionRulesLeaveTheFactorAlone() {
        Factors factors = new Factors();
        TravellerScale<String> scale = new TravellerScale<>(factors);

        assertEquals(1.0D, scale.cross("steve", ScaleRule.OFF, new SizeRatio(3.0D, true)), 0.0D);
        assertEquals(1.0D, scale.cross("steve", ScaleRule.motion(), new SizeRatio(3.0D, true)), 0.0D);
        assertEquals(0, factors.writes);
    }

    @Test
    void unsupportedEntitiesKeepTravellingAtTheirSize() {
        Factors factors = new Factors();
        factors.unsupported.add("item");
        TravellerScale<String> scale = new TravellerScale<>(factors);

        assertEquals(1.0D, scale.cross("item", ScaleRule.ratio(0.25D, 4.0D), new SizeRatio(3.0D, true)), 0.0D);
    }

    @Test
    void resetRestoresTheDefaultSizeAndCountsOnlyScaledEntities() {
        Factors factors = new Factors();
        TravellerScale<String> scale = new TravellerScale<>(factors);
        scale.cross("steve", ScaleRule.ratio(0.25D, 4.0D), new SizeRatio(3.0D, true));
        scale.cross("pig", ScaleRule.ratio(0.25D, 4.0D), new SizeRatio(0.5D, true));
        List<String> restored = new ArrayList<>();

        assertTrue(scale.reset("steve"));
        assertFalse(scale.reset("steve"));
        assertEquals(1, scale.resetAll(List.of("steve", "pig", "cow"), restored));
        assertEquals(List.of("pig"), restored);
        assertEquals(1.0D, factors.scale("pig"), 0.0D);
    }

    private static final class Factors implements ScaleAccess<String> {
        private final Map<String, Double> factors = new HashMap<>();
        private final List<String> unsupported = new ArrayList<>();
        private int writes;

        @Override
        public double scale(String entity) {
            return factors.getOrDefault(entity, 1.0D);
        }

        @Override
        public double defaultScale(String entity) {
            return 1.0D;
        }

        @Override
        public boolean scale(String entity, double factor) {
            if (unsupported.contains(entity)) {
                return false;
            }
            writes++;
            factors.put(entity, factor);
            return true;
        }

        @Override
        public boolean reset(String entity) {
            return factors.remove(entity) != null;
        }
    }
}
