package art.arcane.wormholes.transit;

import java.math.BigDecimal;
import java.util.Locale;

import art.arcane.optics.crossing.ScaleRule;

public final class ScaleRuleSettings {
    public static final String MODE = "transit.scale.mode";
    public static final String MIN = "transit.scale.min";
    public static final String MAX = "transit.scale.max";
    public static final double DEFAULT_MIN = 0.25D;
    public static final double DEFAULT_MAX = 4.0D;
    public static final ScaleRule DEFAULT = new ScaleRule(ScaleRule.Mode.OFF, DEFAULT_MIN, DEFAULT_MAX);

    private ScaleRuleSettings() {
    }

    public static ScaleRule parse(String mode, String min, String max) {
        ScaleRule.Mode parsedMode = mode(mode);
        Double parsedMin = bound(min);
        Double parsedMax = bound(max);
        return of(parsedMode == null ? ScaleRule.Mode.OFF : parsedMode, parsedMin == null ? DEFAULT_MIN : parsedMin,
            parsedMax == null ? DEFAULT_MAX : parsedMax);
    }

    public static ScaleRule of(ScaleRule.Mode mode, double min, double max) {
        double low = clamp(Math.min(min, max));
        double high = clamp(Math.max(min, max));
        return new ScaleRule(mode, low, high);
    }

    public static ScaleRule.Mode mode(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String name = text.trim();
        for (ScaleRule.Mode mode : ScaleRule.Mode.values()) {
            if (mode.name().equalsIgnoreCase(name)) {
                return mode;
            }
        }
        return null;
    }

    public static Double bound(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            double value = Double.parseDouble(text.trim());
            return Double.isFinite(value) && value > 0.0D ? value : null;
        } catch (NumberFormatException malformed) {
            return null;
        }
    }

    public static ScaleRule.Mode next(ScaleRule.Mode mode) {
        ScaleRule.Mode[] modes = ScaleRule.Mode.values();
        return modes[(mode.ordinal() + 1) % modes.length];
    }

    public static ScaleRule withMode(ScaleRule rule, ScaleRule.Mode mode) {
        return new ScaleRule(mode, rule.min(), rule.max());
    }

    public static ScaleRule withMin(ScaleRule rule, double min) {
        double value = clamp(min);
        return new ScaleRule(rule.mode(), value, Math.max(rule.max(), value));
    }

    public static ScaleRule withMax(ScaleRule rule, double max) {
        double value = clamp(max);
        return new ScaleRule(rule.mode(), Math.min(rule.min(), value), value);
    }

    public static boolean isDefault(ScaleRule rule) {
        return DEFAULT.equals(rule);
    }

    public static String format(ScaleRule.Mode mode) {
        return mode.name().toLowerCase(Locale.ROOT);
    }

    public static String format(double bound) {
        return BigDecimal.valueOf(bound).stripTrailingZeros().toPlainString();
    }

    private static double clamp(double value) {
        if (!Double.isFinite(value)) {
            return DEFAULT_MIN;
        }
        return Math.max(ScaleRule.ATTRIBUTE_MIN, Math.min(ScaleRule.ATTRIBUTE_MAX, value));
    }
}
