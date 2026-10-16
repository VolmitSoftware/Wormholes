package art.arcane.wormholes.modded.client.menu;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class MenuNumbers {
    private static final double CLEAN_SCALE = 1.0E9D;
    private static final int FORMAT_DIGITS = 2;

    private MenuNumbers() {
    }

    public static double fraction(double value, double min, double max) {
        if (max <= min) {
            return 0.0D;
        }
        return Math.max(0.0D, Math.min(1.0D, (value - min) / (max - min)));
    }

    public static double snap(double value, double min, double max, double step) {
        if (value >= max) {
            return max;
        }
        if (value <= min) {
            return min;
        }
        double steps = Math.rint((value - min) / step);
        double candidate = Math.rint((min + steps * step) * CLEAN_SCALE) / CLEAN_SCALE;
        return Math.max(min, Math.min(max, candidate));
    }

    public static double valueAt(double fraction, double min, double max, double step) {
        double clamped = Math.max(0.0D, Math.min(1.0D, fraction));
        return snap(min + clamped * (max - min), min, max, step);
    }

    public static double stepped(double value, int direction, double min, double max, double step) {
        return snap(value + direction * step, min, max, step);
    }

    public static String format(double value) {
        return BigDecimal.valueOf(value).setScale(FORMAT_DIGITS, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
