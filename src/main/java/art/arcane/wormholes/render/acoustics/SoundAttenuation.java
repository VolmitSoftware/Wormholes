package art.arcane.wormholes.render.acoustics;

/** Volume and pitch curves for sounds relayed from the destination to the local aperture. */
public final class SoundAttenuation {
    public static final float VOLUME_FLOOR = 0.1F;
    private static final float PITCH_DROP = 0.15F;
    private static final float MIN_PITCH = 0.5F;
    private static final float MAX_PITCH = 2.0F;

    private SoundAttenuation() {
    }

    public static float volume(float base, double distance, double radius) {
        if (radius <= 0.0D) {
            return base;
        }
        double factor = 1.0D - Math.max(0.0D, distance) / radius;
        return (float) (base * Math.max(VOLUME_FLOOR, Math.min(1.0D, factor)));
    }

    public static float pitch(float base, double distance, double radius) {
        double ratio = radius <= 0.0D ? 0.0D : Math.max(0.0D, Math.min(1.0D, distance / radius));
        double pitch = base * (1.0D - PITCH_DROP * ratio);
        return (float) Math.max(MIN_PITCH, Math.min(MAX_PITCH, pitch));
    }
}
