package art.arcane.wormholes.menu.transit;

import java.util.Locale;

public final class TransitInputs {
    private static final double MIN_FACTOR = 0.0D;
    private static final double MAX_FACTOR = 10.0D;
    private static final int MAX_MASK_OVERRIDE_TICKS = 200;

    private TransitInputs() {
    }

    public static Double parseFactor(String text) {
        if (text == null) {
            return null;
        }
        try {
            double factor = Double.parseDouble(text.trim());
            if (!Double.isFinite(factor) || factor < MIN_FACTOR || factor > MAX_FACTOR) {
                return null;
            }
            return Double.valueOf(factor);
        } catch (NumberFormatException malformed) {
            return null;
        }
    }

    public static Integer parseMaskTicks(String text) {
        String answer = text == null ? "" : text.trim();
        if (answer.isEmpty()) {
            return Integer.valueOf(-1);
        }
        try {
            int ticks = Integer.parseInt(answer);
            if (ticks < -1 || ticks > MAX_MASK_OVERRIDE_TICKS) {
                return null;
            }
            return Integer.valueOf(ticks);
        } catch (NumberFormatException malformed) {
            return null;
        }
    }

    public static String soundOrEmpty(String text) {
        String answer = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        if (answer.isEmpty() || answer.equals("-") || answer.equals("none") || answer.equals("default")) {
            return "";
        }
        return answer;
    }

    public static String formatFactor(double factor) {
        return factor == Math.rint(factor) ? Integer.toString((int) factor) : String.format(Locale.ROOT, "%.2f", Double.valueOf(factor));
    }

    public static String labelOrDefault(String value) {
        return value == null || value.isBlank() ? "default" : value;
    }
}
