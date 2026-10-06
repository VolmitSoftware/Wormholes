package art.arcane.wormholes.transit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import art.arcane.optics.crossing.MomentumRule;
import art.arcane.optics.crossing.OrientationRule;
import art.arcane.optics.math.Vec3d;

final class TransitPolicyRuleTest {
    @Test
    void everyOrientationPolicyMapsToTheSameNamedRule() {
        assertEquals(OrientationPolicy.values().length, OrientationRule.values().length);
        for (OrientationPolicy policy : OrientationPolicy.values()) {
            assertEquals(policy.name(), policy.rule().name());
        }
    }

    @Test
    void everyMomentumPolicyCarriesItsModeAndValuesIntoTheRule() {
        assertEquals(MomentumPolicy.Mode.values().length, MomentumRule.Mode.values().length);
        for (MomentumPolicy.Mode mode : MomentumPolicy.Mode.values()) {
            MomentumRule rule = new MomentumPolicy(mode, 2.5D, 6.0D, new Vec3d(0.0D, 0.5D, 1.0D)).rule();
            assertEquals(mode.name(), rule.mode().name());
            assertEquals(2.5D, rule.factor(), 0.0D);
            assertEquals(6.0D, rule.maxSpeed(), 0.0D);
            assertEquals(new Vec3d(0.0D, 0.5D, 1.0D), rule.impulse());
        }
        MomentumRule defaults = MomentumPolicy.of(MomentumPolicy.Mode.CLAMP).rule();
        assertEquals(new MomentumRule(MomentumRule.Mode.CLAMP, 1.0D, 0.0D, null), defaults);
    }
}
