package art.arcane.wormholes.transit;

import java.util.Objects;

import art.arcane.optics.crossing.ScaleRule;

public record ScaleRuleChange(Status status, ScaleRule rule, String reason) {
    public ScaleRuleChange {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(rule, "rule");
        reason = reason == null ? "" : reason;
    }

    public static ScaleRuleChange request(String mode, String min, String max, ScaleRule current) {
        String modeText = trimmed(mode);
        String minText = trimmed(min);
        String maxText = trimmed(max);
        if (modeText.isEmpty() && minText.isEmpty() && maxText.isEmpty()) {
            return new ScaleRuleChange(Status.SHOWN, current, "");
        }
        ScaleRule.Mode parsedMode = modeText.isEmpty() ? current.mode() : ScaleRuleSettings.mode(modeText);
        if (parsedMode == null) {
            return invalid(current, "mode " + modeText);
        }
        Double parsedMin = minText.isEmpty() ? Double.valueOf(current.min()) : ScaleRuleSettings.bound(minText);
        if (parsedMin == null) {
            return invalid(current, "min " + minText);
        }
        Double parsedMax = maxText.isEmpty() ? Double.valueOf(current.max()) : ScaleRuleSettings.bound(maxText);
        if (parsedMax == null) {
            return invalid(current, "max " + maxText);
        }
        if (parsedMin > parsedMax) {
            return invalid(current, "min " + ScaleRuleSettings.format(parsedMin) + " > max " + ScaleRuleSettings.format(parsedMax));
        }
        return new ScaleRuleChange(Status.SET, ScaleRuleSettings.of(parsedMode, parsedMin, parsedMax), "");
    }

    public static String mode(ScaleRule rule) {
        return ScaleRuleSettings.format(rule.mode());
    }

    public static String range(ScaleRule rule) {
        return ScaleRuleSettings.format(rule.min()) + "-" + ScaleRuleSettings.format(rule.max());
    }

    private static ScaleRuleChange invalid(ScaleRule current, String reason) {
        return new ScaleRuleChange(Status.INVALID, current, reason);
    }

    private static String trimmed(String text) {
        return text == null ? "" : text.trim();
    }

    public enum Status {
        SHOWN, SET, INVALID
    }
}
