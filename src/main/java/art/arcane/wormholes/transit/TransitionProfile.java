package art.arcane.wormholes.transit;

/**
 * Per-portal cue overrides. Empty sound or effect keys fall back to the built-in cues; a negative
 * {@code maskOverrideTicks} leaves the arrival mask to the adaptive sizing.
 */
public record TransitionProfile(String thresholdEffect, String arrivalSound, int maskOverrideTicks) {
    public static final TransitionProfile NONE = new TransitionProfile("", "", -1);
    private static final String SEPARATOR = "|";
    private static final int FIELDS = 3;
    private static final int LEGACY_FIELDS_WITH_APPROACH_SOUND = 4;

    public TransitionProfile {
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

    public TransitionProfile withThresholdEffect(String effect) {
        return new TransitionProfile(effect, arrivalSound, maskOverrideTicks);
    }

    public TransitionProfile withArrivalSound(String sound) {
        return new TransitionProfile(thresholdEffect, sound, maskOverrideTicks);
    }

    public TransitionProfile withMaskOverrideTicks(int ticks) {
        return new TransitionProfile(thresholdEffect, arrivalSound, ticks);
    }

    public String encode() {
        return thresholdEffect + SEPARATOR + arrivalSound + SEPARATOR + maskOverrideTicks;
    }

    /** Portals saved before the approach cue was removed carry a leading approach-sound field, which is dropped. */
    public static TransitionProfile decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return NONE;
        }
        String[] parts = encoded.split("\\|", -1);
        int offset;
        if (parts.length == FIELDS) {
            offset = 0;
        } else if (parts.length == LEGACY_FIELDS_WITH_APPROACH_SOUND) {
            offset = 1;
        } else {
            return NONE;
        }
        int ticks;
        try {
            ticks = Integer.parseInt(parts[offset + 2].trim());
        } catch (NumberFormatException malformed) {
            ticks = -1;
        }
        return new TransitionProfile(parts[offset], parts[offset + 1], ticks);
    }
}
