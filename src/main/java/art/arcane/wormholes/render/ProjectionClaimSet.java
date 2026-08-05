package art.arcane.wormholes.render;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

final class ProjectionClaimSet {
    private static final double PRIORITY_EPSILON = 1.0E-7D;

    private final Map<UUID, PortalClaims> portals;
    private final Long2ObjectOpenHashMap<WinningClaim> winners;
    private final Long2ObjectOpenHashMap<ProjectedBlockClaim> winningClaims;
    private final Long2IntOpenHashMap claimCounts;
    private final Long2ObjectOpenHashMap<PortalClaims> singleOwners;
    private final LongOpenHashSet contestedKeys;
    private final LongArrayList affectedScratch;
    private final LongArrayList staleScratch;
    private final WinnerChoice winnerScratch;
    private int fullBrightWinnerCount;
    private long generation;

    ProjectionClaimSet() {
        this.portals = new HashMap<UUID, PortalClaims>();
        this.winners = new Long2ObjectOpenHashMap<WinningClaim>(256);
        this.winningClaims = new Long2ObjectOpenHashMap<ProjectedBlockClaim>(256);
        this.claimCounts = new Long2IntOpenHashMap(256);
        this.singleOwners = new Long2ObjectOpenHashMap<PortalClaims>(256);
        this.contestedKeys = new LongOpenHashSet(16);
        this.affectedScratch = new LongArrayList(256);
        this.staleScratch = new LongArrayList(64);
        this.winnerScratch = new WinnerChoice();
        this.fullBrightWinnerCount = 0;
        this.generation = 0L;
    }

    ProjectionClaimSetResult replacePortalClaims(UUID portalId,
                                                 String tieKey,
                                                 double priorityDistance,
                                                 Long2ObjectMap<ProjectedBlockClaim> claims) {
        if (claims == null || claims.isEmpty()) {
            return releasePortal(portalId);
        }

        PortalClaims existing = portals.get(portalId);
        PortalClaims portalClaims;
        boolean priorityChanged;
        if (existing == null) {
            portalClaims = new PortalClaims(portalId);
            portals.put(portalId, portalClaims);
            priorityChanged = false;
        } else {
            portalClaims = existing;
            priorityChanged = hasPriorityChanged(existing, tieKey, priorityDistance);
        }

        long passGeneration = ++generation;
        int previousSize = portalClaims.claims.size();
        int carriedOver = 0;
        LongArrayList affected = affectedScratch;
        affected.clear();

        ObjectIterator<Long2ObjectMap.Entry<ProjectedBlockClaim>> incoming = Long2ObjectMaps.fastIterator(claims);
        while (incoming.hasNext()) {
            Long2ObjectMap.Entry<ProjectedBlockClaim> entry = incoming.next();
            long key = entry.getLongKey();
            ProjectedBlockClaim nextClaim = entry.getValue();
            ClaimSlot slot = portalClaims.claims.get(key);
            if (slot == null) {
                incrementClaimCount(key, portalClaims);
                portalClaims.claims.put(key, new ClaimSlot(key, nextClaim, passGeneration));
                affected.add(key);
                continue;
            }
            carriedOver++;
            slot.generation = passGeneration;
            if (sameClaim(slot.claim, nextClaim)) {
                continue;
            }
            slot.claim = nextClaim;
            slot.affectedGeneration = passGeneration;
            affected.add(key);
        }

        if (carriedOver != previousSize) {
            collectStaleKeys(portalClaims, passGeneration);
            int staleCount = staleScratch.size();
            for (int i = 0; i < staleCount; i++) {
                long key = staleScratch.getLong(i);
                portalClaims.claims.remove(key);
                decrementClaimCount(key, portalClaims);
                affected.add(key);
            }
        }

        portalClaims.tieKey = tieKey;
        portalClaims.priorityDistance = priorityDistance;

        if (priorityChanged && !contestedKeys.isEmpty()) {
            appendContestedKeys(portalClaims, passGeneration, affected);
        }

        return recomputeAffected(affected);
    }

    ProjectionClaimSetResult releasePortal(UUID portalId) {
        PortalClaims existing = portals.remove(portalId);
        if (existing == null) {
            return new ProjectionClaimSetResult();
        }
        LongArrayList affected = affectedScratch;
        affected.clear();
        LongIterator existingIterator = existing.claims.keySet().iterator();
        while (existingIterator.hasNext()) {
            long key = existingIterator.nextLong();
            affected.add(key);
            decrementClaimCount(key, existing);
        }
        existing.claims.clear();
        return recomputeAffected(affected);
    }

    void clear() {
        portals.clear();
        winners.clear();
        winningClaims.clear();
        claimCounts.clear();
        singleOwners.clear();
        contestedKeys.clear();
        affectedScratch.clear();
        staleScratch.clear();
        fullBrightWinnerCount = 0;
    }

    boolean isEmpty() {
        return winners.isEmpty();
    }

    boolean hasFullBrightClaims() {
        return fullBrightWinnerCount > 0;
    }

    Long2ObjectOpenHashMap<ProjectedBlockClaim> getWinningClaims() {
        return winningClaims;
    }

    ProjectedBlockClaim getWinningClaim(long key) {
        return winningClaims.get(key);
    }

    static boolean isHigherPriority(double candidateDistance, String candidateTieKey, double currentDistance, String currentTieKey) {
        return isHigherPriority(candidateDistance, candidateTieKey, null, currentDistance, currentTieKey, null);
    }

    static boolean isHigherPriority(double candidateDistance,
                                    String candidateTieKey,
                                    ProjectedBlockClaim candidateClaim,
                                    double currentDistance,
                                    String currentTieKey,
                                    ProjectedBlockClaim currentClaim) {
        if (candidateClaim != null && currentClaim != null) {
            if (candidateClaim.isMaskAir() && !currentClaim.isMaskAir()) {
                return false;
            }
            if (!candidateClaim.isMaskAir() && currentClaim.isMaskAir()) {
                return true;
            }
        }
        if (candidateDistance < currentDistance - PRIORITY_EPSILON) {
            return true;
        }
        if (candidateDistance > currentDistance + PRIORITY_EPSILON) {
            return false;
        }
        return candidateTieKey.compareTo(currentTieKey) < 0;
    }

    private void collectStaleKeys(PortalClaims portalClaims, long passGeneration) {
        staleScratch.clear();
        ObjectIterator<ClaimSlot> slotIterator = portalClaims.claims.values().iterator();
        while (slotIterator.hasNext()) {
            ClaimSlot slot = slotIterator.next();
            if (slot.generation == passGeneration) {
                continue;
            }
            staleScratch.add(slot.key);
        }
    }

    private void appendContestedKeys(PortalClaims portalClaims, long passGeneration, LongArrayList affected) {
        LongIterator contestedIterator = contestedKeys.iterator();
        while (contestedIterator.hasNext()) {
            long key = contestedIterator.nextLong();
            ClaimSlot slot = portalClaims.claims.get(key);
            if (slot == null || slot.affectedGeneration == passGeneration) {
                continue;
            }
            slot.affectedGeneration = passGeneration;
            affected.add(key);
        }
    }

    private ProjectionClaimSetResult recomputeAffected(LongArrayList affected) {
        int affectedCount = affected.size();
        ProjectionClaimSetResult result = new ProjectionClaimSetResult(affectedCount);
        for (int i = 0; i < affectedCount; i++) {
            long key = affected.getLong(i);
            WinningClaim previous = winners.get(key);
            WinnerChoice choice = chooseWinner(key);
            if (choice.claimCount > 1) {
                result.conflicts++;
            }
            PortalClaims nextOwner = choice.owner;
            if (nextOwner == null) {
                if (previous != null) {
                    if (previous.claim.isFullBright()) {
                        fullBrightWinnerCount--;
                        result.immediateLightingUpdate = true;
                    }
                    winners.remove(key);
                    winningClaims.remove(key);
                    result.packetChangeKeys.add(key);
                    result.dirtyLightingKeys.add(key);
                    result.reverts++;
                }
                continue;
            }

            ProjectedBlockClaim nextClaim = choice.claim;
            boolean ownerChanged = previous == null || isDifferentOwner(previous.owner, nextOwner);
            boolean dataChanged = previous == null || !previous.claim.getData().equals(nextClaim.getData());
            boolean lightChanged = previous == null || !previous.claim.sameLightSource(nextClaim);
            if (previous != null && previous.claim.isFullBright() && !nextClaim.isFullBright()) {
                result.immediateLightingUpdate = true;
            }
            if (previous == null) {
                winners.put(key, new WinningClaim(nextOwner, nextClaim));
                winningClaims.put(key, nextClaim);
                if (nextClaim.isFullBright()) {
                    fullBrightWinnerCount++;
                }
            } else {
                previous.owner = nextOwner;
                if (previous.claim != nextClaim) {
                    if (previous.claim.isFullBright() != nextClaim.isFullBright()) {
                        fullBrightWinnerCount += nextClaim.isFullBright() ? 1 : -1;
                    }
                    previous.claim = nextClaim;
                    winningClaims.put(key, nextClaim);
                }
            }
            if (dataChanged) {
                result.packetChangeKeys.add(key);
            }
            if (ownerChanged) {
                result.winnerChanges++;
            }
            if (lightChanged) {
                result.dirtyLightingKeys.add(key);
            }
        }
        return result;
    }

    private WinnerChoice chooseWinner(long key) {
        WinnerChoice choice = winnerScratch;
        choice.owner = null;
        choice.claim = null;
        choice.claimCount = 0;
        int storedClaimCount = claimCounts.get(key);
        if (storedClaimCount <= 0) {
            return choice;
        }
        if (storedClaimCount == 1) {
            PortalClaims singleOwner = singleOwners.get(key);
            if (singleOwner != null) {
                ClaimSlot slot = singleOwner.claims.get(key);
                if (slot != null && slot.claim != null) {
                    choice.owner = singleOwner;
                    choice.claim = slot.claim;
                    choice.claimCount = 1;
                    return choice;
                }
            }
        }

        Iterator<PortalClaims> iterator = portals.values().iterator();
        while (iterator.hasNext()) {
            PortalClaims portalClaims = iterator.next();
            ClaimSlot slot = portalClaims.claims.get(key);
            if (slot == null || slot.claim == null) {
                continue;
            }
            choice.claimCount++;
            if (choice.owner == null
                || isHigherPriority(portalClaims.priorityDistance, portalClaims.tieKey, slot.claim,
                    choice.owner.priorityDistance, choice.owner.tieKey, choice.claim)) {
                choice.owner = portalClaims;
                choice.claim = slot.claim;
            }
        }
        return choice;
    }

    private static boolean isDifferentOwner(PortalClaims previousOwner, PortalClaims nextOwner) {
        return previousOwner != nextOwner && !previousOwner.portalId.equals(nextOwner.portalId);
    }

    private static boolean hasPriorityChanged(PortalClaims existing, String tieKey, double priorityDistance) {
        if (!existing.tieKey.equals(tieKey)) {
            return true;
        }
        return Math.abs(existing.priorityDistance - priorityDistance) > PRIORITY_EPSILON;
    }

    private static boolean sameClaim(ProjectedBlockClaim previous, ProjectedBlockClaim next) {
        if (previous == next) {
            return true;
        }
        if (previous == null || next == null) {
            return false;
        }
        return previous.isMaskAir() == next.isMaskAir()
            && previous.getData().equals(next.getData())
            && previous.sameLightSource(next);
    }

    private void incrementClaimCount(long key, PortalClaims owner) {
        int count = claimCounts.get(key);
        if (count == 0) {
            claimCounts.put(key, 1);
            singleOwners.put(key, owner);
            return;
        }
        if (count == 1) {
            singleOwners.remove(key);
        }
        claimCounts.put(key, count + 1);
        contestedKeys.add(key);
    }

    private void decrementClaimCount(long key, PortalClaims removedOwner) {
        int count = claimCounts.get(key);
        if (count <= 1) {
            claimCounts.remove(key);
            singleOwners.remove(key);
            return;
        }
        if (count == 2) {
            claimCounts.put(key, 1);
            contestedKeys.remove(key);
            PortalClaims remainingOwner = findRemainingOwner(key, removedOwner);
            if (remainingOwner == null) {
                singleOwners.remove(key);
            } else {
                singleOwners.put(key, remainingOwner);
            }
            return;
        }
        claimCounts.put(key, count - 1);
    }

    private PortalClaims findRemainingOwner(long key, PortalClaims removedOwner) {
        Iterator<PortalClaims> iterator = portals.values().iterator();
        while (iterator.hasNext()) {
            PortalClaims portalClaims = iterator.next();
            if (portalClaims == removedOwner) {
                continue;
            }
            if (portalClaims.claims.containsKey(key)) {
                return portalClaims;
            }
        }
        return null;
    }

    static final class ProjectionClaimSetResult {
        private final LongOpenHashSet packetChangeKeys;
        private final LongOpenHashSet dirtyLightingKeys;
        private int conflicts;
        private int winnerChanges;
        private int reverts;
        private boolean immediateLightingUpdate;

        ProjectionClaimSetResult() {
            this(0);
        }

        ProjectionClaimSetResult(int expectedKeys) {
            this.packetChangeKeys = new LongOpenHashSet(expectedKeys);
            this.dirtyLightingKeys = new LongOpenHashSet(expectedKeys);
            this.conflicts = 0;
            this.winnerChanges = 0;
            this.reverts = 0;
            this.immediateLightingUpdate = false;
        }

        LongOpenHashSet getPacketChangeKeys() {
            return packetChangeKeys;
        }

        LongOpenHashSet getDirtyLightingKeys() {
            return dirtyLightingKeys;
        }

        int getConflicts() {
            return conflicts;
        }

        int getWinnerChanges() {
            return winnerChanges;
        }

        int getReverts() {
            return reverts;
        }

        boolean requiresImmediateLightingUpdate() {
            return immediateLightingUpdate;
        }

        boolean isEmpty() {
            return packetChangeKeys.isEmpty() && dirtyLightingKeys.isEmpty() && conflicts == 0
                && winnerChanges == 0 && reverts == 0 && !immediateLightingUpdate;
        }

        void merge(ProjectionClaimSetResult other) {
            if (other == null || other.isEmpty()) {
                return;
            }
            packetChangeKeys.addAll(other.packetChangeKeys);
            dirtyLightingKeys.addAll(other.dirtyLightingKeys);
            conflicts += other.conflicts;
            winnerChanges += other.winnerChanges;
            reverts += other.reverts;
            immediateLightingUpdate |= other.immediateLightingUpdate;
        }
    }

    private static final class PortalClaims {
        private final UUID portalId;
        private final Long2ObjectOpenHashMap<ClaimSlot> claims;
        private String tieKey;
        private double priorityDistance;

        private PortalClaims(UUID portalId) {
            this.portalId = portalId;
            this.claims = new Long2ObjectOpenHashMap<ClaimSlot>(256);
            this.tieKey = portalId.toString();
            this.priorityDistance = 0.0D;
        }
    }

    private static final class ClaimSlot {
        private final long key;
        private ProjectedBlockClaim claim;
        private long generation;
        private long affectedGeneration;

        private ClaimSlot(long key, ProjectedBlockClaim claim, long generation) {
            this.key = key;
            this.claim = claim;
            this.generation = generation;
            this.affectedGeneration = generation;
        }
    }

    private static final class WinnerChoice {
        private PortalClaims owner;
        private ProjectedBlockClaim claim;
        private int claimCount;

        private WinnerChoice() {
            this.owner = null;
            this.claim = null;
            this.claimCount = 0;
        }
    }

    private static final class WinningClaim {
        private PortalClaims owner;
        private ProjectedBlockClaim claim;

        private WinningClaim(PortalClaims owner, ProjectedBlockClaim claim) {
            this.owner = owner;
            this.claim = claim;
        }
    }
}
