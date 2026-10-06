package art.arcane.optics.client;

import art.arcane.optics.aperture.ApertureDescriptor;

public final class ClientCellRules {
    public static final int KEEP_REAL = -1;
    public static final int AIR = 0;
    public static final int OCCLUDED = 1;
    public static final int BACKING = 2;

    private ClientCellRules() {
    }

    public static int evaluate(boolean shell, int paletteId, boolean contentOccluding, boolean shadowAir, Policy policy) {
        boolean standIn = paletteId == OCCLUDED || paletteId == BACKING;
        if (shell && policy.blackoutPolicy() != ApertureDescriptor.BLACKOUT_OFF && !standIn && !contentOccluding) {
            return policy.blackoutState();
        }
        if (paletteId == OCCLUDED) {
            return policy.blackoutPolicy() == ApertureDescriptor.BLACKOUT_SHELL_AND_BURIED
                ? policy.blackoutState()
                : policy.backingState();
        }
        if (paletteId == BACKING) {
            return policy.backingState();
        }
        if (paletteId == AIR) {
            return shadowAir || policy.maskAirPolicy() == ApertureDescriptor.MASK_AIR_KEEP_REAL ? KEEP_REAL : AIR;
        }
        return paletteId;
    }

    public record Policy(int blackoutPolicy, int blackoutState, int backingState, int maskAirPolicy) {
        public static Policy of(ApertureDescriptor geometry, int backingState) {
            return new Policy(geometry.blackoutPolicy(), geometry.blackoutState(), backingState, geometry.maskAirPolicy());
        }
    }
}
