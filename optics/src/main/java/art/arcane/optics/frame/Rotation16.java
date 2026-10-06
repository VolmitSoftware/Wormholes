package art.arcane.optics.frame;

import art.arcane.optics.math.Face;

public final class Rotation16 {
    private static final int STEPS = 16;

    private Rotation16() {
    }

    public static int rotate(int index, int quarterTurnsClockwise) {
        return Math.floorMod(index + (quarterTurnsClockwise * 4), STEPS);
    }

    /**
     * Mirrors across the vertical plane whose horizontal normal is {@code mirrorNormal}. A plane with a
     * vertical normal is horizontal and leaves every horizontal rotation untouched.
     */
    public static int reflect(int index, Face mirrorNormal) {
        if (mirrorNormal.isVertical()) {
            return Math.floorMod(index, STEPS);
        }
        int fixedAxisIndex = mirrorNormal == Face.N || mirrorNormal == Face.S ? 4 : 0;
        return Math.floorMod((2 * fixedAxisIndex) - index, STEPS);
    }
}
