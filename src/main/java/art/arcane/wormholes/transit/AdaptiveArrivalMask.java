package art.arcane.wormholes.transit;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.chunk.presend.ChunkPreSendOutcome;

/**
 * Sizes the arrival DARKNESS mask by how much of the destination still has to stream: nothing when the
 * pre-send delivered the whole ring, a floor-to-ceiling proportion of the missing chunks after a partial
 * burst, and the fixed mask when no pre-send happened at all.
 */
public final class AdaptiveArrivalMask {
    private AdaptiveArrivalMask() {
    }

    public static int maskTicks(boolean reloadExpected, ChunkPreSendOutcome outcome, int chunksAlreadySent, int chunksNeeded) {
        return maskTicks(reloadExpected, outcome, chunksAlreadySent, chunksNeeded,
            TransitSubsystem.config().arrivalMaskMinTicks, Settings.ARRIVAL_TRANSITION_MASK_TICKS);
    }

    static int maskTicks(boolean reloadExpected, ChunkPreSendOutcome outcome, int chunksAlreadySent, int chunksNeeded,
                         int minTicks, int maxTicks) {
        if (!reloadExpected || maxTicks <= 0) {
            return 0;
        }
        if (outcome == null || !outcome.delivered()) {
            return maxTicks;
        }
        int missing = Math.max(0, chunksNeeded - chunksAlreadySent);
        if (outcome == ChunkPreSendOutcome.PRE_SENT || (chunksNeeded > 0 && missing == 0)) {
            return 0;
        }
        if (chunksNeeded <= 0) {
            return maxTicks;
        }
        int proportional = (int) Math.ceil(maxTicks * (double) missing / chunksNeeded);
        int floor = Math.max(0, Math.min(minTicks, maxTicks));
        return Math.max(1, Math.min(maxTicks, Math.max(floor, proportional)));
    }
}
