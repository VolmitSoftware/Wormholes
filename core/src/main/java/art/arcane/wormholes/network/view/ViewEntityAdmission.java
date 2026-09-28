package art.arcane.wormholes.network.view;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

public final class ViewEntityAdmission<T> {
        private static final Comparator<EntityRank> RANK_ORDER = ViewEntityAdmission::compareRanks;

        private final int limit;
        private final TreeSet<EntityRank> ranks = new TreeSet<>(RANK_ORDER);
        private final Map<UUID, EntityRank> ranksById = new HashMap<>();
        private final Map<UUID, T> valuesById = new HashMap<>();

        public ViewEntityAdmission(int limit) {
            if (limit <= 0) {
                throw new IllegalArgumentException("limit must be positive");
            }
            this.limit = limit;
        }

        public synchronized boolean admit(EntityRank rank, T value) {
            if (ranksById.containsKey(rank.id())) {
                return false;
            }
            if (ranks.size() >= limit) {
                EntityRank worst = ranks.last();
                if (RANK_ORDER.compare(rank, worst) >= 0) {
                    return false;
                }
                ranks.remove(worst);
                ranksById.remove(worst.id());
                valuesById.remove(worst.id());
            }
            ranks.add(rank);
            ranksById.put(rank.id(), rank);
            valuesById.put(rank.id(), value);
            return true;
        }

        public synchronized Set<UUID> admittedIds() {
            return Set.copyOf(ranksById.keySet());
        }

        public synchronized List<T> selectedEntities() {
            return List.copyOf(valuesById.values());
        }
    private static int compareRanks(EntityRank left, EntityRank right) {
        if (left.player() != right.player()) {
            return left.player() ? -1 : 1;
        }
        int distanceOrder = Double.compare(left.distanceSquared(), right.distanceSquared());
        if (distanceOrder != 0) {
            return distanceOrder;
        }
        return left.id().compareTo(right.id());
    }

    public record EntityRank(UUID id, boolean player, double distanceSquared) {
    }
    }

