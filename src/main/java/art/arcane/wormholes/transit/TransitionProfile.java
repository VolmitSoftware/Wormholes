package art.arcane.wormholes.transit;

/**
 * Per-portal cue overrides. Empty sound or effect keys fall back to the built-in cues; a negative
 * {@code maskOverrideTicks} leaves the arrival mask to the adaptive sizing.
 */
public record TransitionProfile(String approachSound, String thresholdEffect, String arrivalSound, int maskOverrideTicks) {
    public static final TransitionProfile NONE = new TransitionProfile("", "", "", -1);
    private static final String SEPARATOR = "|";

    public TransitionProfile {
        approachSound = approachSound == null ? "" : approachSound.trim();
        thresholdEffect = thresholdEffect == null ? "" : thresholdEffect.trim();
        arrivalSound = arrivalSound == null ? "" : arrivalSound.trim();
        maskOverrideTicks = Math.max(-1, maskOverrideTicks);
    }

    public boolean isNone() {
        return equals(NONE);
    }

    public boolean overridesMask() {
        return maskOverrideTicks >= 0;
    }

    public TransitionProfile withApproachSound(String sound) {
        return new TransitionProfile(sound, thresholdEffect, arrivalSound, maskOverrideTicks);
    }

    public TransitionProfile withThresholdEffect(String effect) {
        return new TransitionProfile(approachSound, effect, arrivalSound, maskOverrideTicks);
    }

    public TransitionProfile withArrivalSound(String sound) {
        return new TransitionProfile(approachSound, thresholdEffect, sound, maskOverrideTicks);
    }

    public TransitionProfile withMaskOverrideTicks(int ticks) {
        return new TransitionProfile(approachSound, thresholdEffect, arrivalSound, ticks);
    }

    public String encode() {
        return approachSound + SEPARATOR + thresholdEffect + SEPARATOR + arrivalSound + SEPARATOR + maskOverrideTicks;
    }

    public static TransitionProfile decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return NONE;
        }
        String[] parts = encoded.split("\\|", -1);
        if (parts.length != 4) {
            return NONE;
        }
        int ticks;
        try {
            ticks = Integer.parseInt(parts[3].trim());
        } catch (NumberFormatException malformed) {
            ticks = -1;
        }
        return new TransitionProfile(parts[0], parts[1], parts[2], ticks);
    }
}
