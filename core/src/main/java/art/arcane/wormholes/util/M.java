package art.arcane.wormholes.util;

import java.util.Random;

public final class M {
    private static final Random RANDOM = new Random();

    private M() {
    }

    public static boolean r(Double d) {
        if (d == null) {
            return RANDOM.nextDouble() < 0.5;
        }
        return RANDOM.nextDouble() < d;
    }

    public static int rand(int min, int max) {
        if (max <= min) {
            return min;
        }
        return min + RANDOM.nextInt((max - min) + 1);
    }

    public static double rand(double min, double max) {
        if (max <= min) {
            return min;
        }
        return min + RANDOM.nextDouble() * (max - min);
    }
}
