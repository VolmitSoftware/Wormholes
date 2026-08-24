package art.arcane.wormholes.network.replication.capture;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class CaptureDrainBudget {
    private static final Comparator<CaptureRegionScheduler.DrainKey> KEY_ORDER = Comparator
        .comparing(CaptureRegionScheduler.DrainKey::worldId)
        .thenComparingLong(CaptureRegionScheduler.DrainKey::chunkKey);

    private int cursor;

    synchronized List<CaptureRegionScheduler.DrainKey> select(
        List<CaptureRegionScheduler.DrainKey> candidates,
        Set<CaptureRegionScheduler.DrainKey> unavailable,
        int maxAdmissions
    ) {
        if (candidates.isEmpty() || maxAdmissions <= 0) {
            return List.of();
        }
        Set<CaptureRegionScheduler.DrainKey> excluded = unavailable == null ? Set.of() : unavailable;
        Set<CaptureRegionScheduler.DrainKey> unique = new HashSet<CaptureRegionScheduler.DrainKey>(candidates.size());
        List<CaptureRegionScheduler.DrainKey> eligible = new ArrayList<CaptureRegionScheduler.DrainKey>(candidates.size());
        for (CaptureRegionScheduler.DrainKey candidate : candidates) {
            if (candidate != null && !excluded.contains(candidate) && unique.add(candidate)) {
                eligible.add(candidate);
            }
        }
        if (eligible.isEmpty()) {
            return List.of();
        }
        eligible.sort(KEY_ORDER);
        int selected = Math.min(maxAdmissions, eligible.size());
        int start = Math.floorMod(cursor, eligible.size());
        List<CaptureRegionScheduler.DrainKey> admitted = new ArrayList<CaptureRegionScheduler.DrainKey>(selected);
        for (int offset = 0; offset < selected; offset++) {
            admitted.add(eligible.get((start + offset) % eligible.size()));
        }
        cursor = (start + selected) % eligible.size();
        return admitted;
    }
}
