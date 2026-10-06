package art.arcane.optics.light;

public final class SkyMath {
    private SkyMath() {
    }

    public static int computeSkyDarken(long dayTime) {
        return computeSkyDarken(dayTime, false, false);
    }

    public static int computeSkyDarken(long dayTime, boolean storm, boolean thunder) {
        double d = (dayTime / 24000.0D) - 0.25D;
        d = d - Math.floor(d);
        double e = 0.5D - Math.cos(d * Math.PI) / 2.0D;
        double celestialAngle = (d * 2.0D + e) / 3.0D;
        double f = 1.0D - (Math.cos(celestialAngle * Math.PI * 2.0D) * 2.0D + 0.5D);
        f = Math.max(0.0D, Math.min(1.0D, f));
        double brightness = 1.0D - f;
        if (storm) {
            brightness *= 1.0D - (5.0D / 16.0D);
        }
        if (thunder) {
            brightness *= 1.0D - (5.0D / 16.0D);
        }
        return (int) ((1.0D - brightness) * 11.0D);
    }

    /** Applies the storm and thunder bands to a clear-sky darken value the same way {@link #computeSkyDarken(long, boolean, boolean)} does. */
    public static int weatherDarken(int clearDarken, boolean storm, boolean thunder) {
        if (!storm && !thunder) {
            return clearDarken;
        }
        double brightness = 1.0D - (Math.max(0, Math.min(11, clearDarken)) / 11.0D);
        if (storm) {
            brightness *= 1.0D - (5.0D / 16.0D);
        }
        if (thunder) {
            brightness *= 1.0D - (5.0D / 16.0D);
        }
        return (int) ((1.0D - brightness) * 11.0D);
    }

}
