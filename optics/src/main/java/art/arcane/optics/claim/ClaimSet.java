package art.arcane.optics.claim;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

public final class ClaimSet<C extends Claim<C>> {
    private static final double PRIORITY_EPSILON = 1.0E-7D;

    public record ClaimDelta<C>(Long2ObjectMap<C> previousClaims,
                      Long2ObjectMap<C> claims,
                      LongSet changedKeys,
                      LongSet removedKeys) {
    }

    private final Map<UUID, PortalClaims<C>> portals;
    private final Long2ObjectOpenHashMap<WinningClaim<C>> winners;
    private final Long2ObjectOpenHashMap<C> winningClaims;
    private final Long2IntOpenHashMap claimCounts;
    private final Long2ObjectOpenHashMap<PortalClaims<C>> singleOwners;
    private final LongOpenHashSet contestedKeys;
    private final LongArrayList affectedScratch;
    private final LongArrayList staleScratch;
    private final WinnerChoice<C> winnerScratch;
    private int fullBrightWinnerCount;
    private long generation;

    public ClaimSet() {
        this.portals = new HashMap<UUID, PortalClaims<C>>();
        this.winners = new Long2ObjectOpenHashMap<WinningClaim<C>>(256);
        this.winningClaims = new Long2ObjectOpenHashMap<C>(256);
        this.claimCounts = new Long2IntOpenHashMap(256);
        this.singleOwners = new Long2ObjectOpenHashMap<PortalClaims<C>>(256);
        this.contestedKeys = new LongOpenHashSet(16);
        this.affectedScratch = new LongArrayList(256);
        this.staleScratch = new LongArrayList(64);
        this.winnerScratch = new WinnerChoice<C>();
        this.fullBrightWinnerCount = 0;
        this.generation = 0L;
    }

    public Result replacePortalClaims(UUID portalId,
                                                 String tieKey,
                                                 double priorityDistance,
                                                 Long2ObjectMap<C> claims) {
        LongArrayList affected = affectedScratch;
        affected.clear();
        replacePortalClaimState(portalId, tieKey, priorityDistance, claims, affected);
        return recomputeAffected(affected);
    }

    public void stagePortalClaims(UUID portalId,
                           String tieKey,
                           double priorityDistance,
                           Long2ObjectMap<C> claims,
                           LongOpenHashSet stagedKeys) {
        LongArrayList affected = affectedScratch;
        affected.clear();
        replacePortalClaimState(portalId, tieKey, priorityDistance, claims, affected);
        appendAffectedKeys(affected, stagedKeys);
    }

    public Result replacePortalDelta(UUID portalId, String tieKey,
                                                double priorityDistance, ClaimDelta<C> delta) {
        affectedScratch.clear();
        replacePortalDeltaState(portalId, tieKey, priorityDistance, delta, affectedScratch);
        return recomputeAffected(affectedScratch);
    }

    public void stagePortalDelta(UUID portalId, String tieKey, double priorityDistance,
                          ClaimDelta<C> delta, LongOpenHashSet stagedKeys) {
        affectedScratch.clear();
        replacePortalDeltaState(portalId, tieKey, priorityDistance, delta, affectedScratch);
        appendAffectedKeys(affectedScratch, stagedKeys);
    }

    public Result releasePortal(UUID portalId) {
        LongArrayList affected = affectedScratch;
        affected.clear();
        releasePortalState(portalId, affected);
        return recomputeAffected(affected);
    }

    public void stagePortalRelease(UUID portalId, LongOpenHashSet stagedKeys) {
        LongArrayList affected = affectedScratch;
        affected.clear();
        releasePortalState(portalId, affected);
        appendAffectedKeys(affected, stagedKeys);
    }

    public Result resolveStaged(LongOpenHashSet stagedKeys) {
        Result result = new Result(stagedKeys.size());
        LongIterator iterator = stagedKeys.iterator();
        while (iterator.hasNext()) {
            recomputeKey(iterator.nextLong(), result);
        }
        return result;
    }

    private void replacePortalClaimState(UUID portalId,
                                         String tieKey,
                                         double priorityDistance,
                                         Long2ObjectMap<C> claims,
                                         LongArrayList affected) {
        if (claims == null || claims.isEmpty()) {
            releasePortalState(portalId, affected);
            return;
        }

        PortalClaims<C> existing = portals.get(portalId);
        PortalClaims<C> portalClaims;
        boolean priorityChanged;
        if (existing == null) {
            portalClaims = new PortalClaims<C>(portalId);
            portals.put(portalId, portalClaims);
            priorityChanged = false;
        } else {
            portalClaims = existing;
            priorityChanged = hasPriorityChanged(existing, tieKey, priorityDistance);
        }

        long passGeneration = ++generation;
        int previousSize = portalClaims.claims.size();
        int carriedOver = 0;
        ObjectIterator<Long2ObjectMap.Entry<C>> incoming = Long2ObjectMaps.fastIterator(claims);
        while (incoming.hasNext()) {
            Long2ObjectMap.Entry<C> entry = incoming.next();
            long key = entry.getLongKey();
            C nextClaim = entry.getValue();
            ClaimSlot<C> slot = portalClaims.claims.get(key);
            if (slot == null) {
                incrementClaimCount(key, portalClaims);
                portalClaims.claims.put(key, new ClaimSlot<C>(key, nextClaim, passGeneration));
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
                forgetLosingSlot(portalClaims, portalClaims.claims.remove(key));
                decrementClaimCount(key, portalClaims);
                affected.add(key);
            }
        }

        portalClaims.tieKey = tieKey;
        portalClaims.priorityDistance = priorityDistance;
        portalClaims.submittedClaims = claims;

        if (priorityChanged && !contestedKeys.isEmpty()) {
            appendContestedKeys(portalClaims, passGeneration, affected);
        }
    }

    private void replacePortalDeltaState(UUID portalId, String tieKey, double priorityDistance,
                                         ClaimDelta<C> delta, LongArrayList affected) {
        PortalClaims<C> portalClaims = portals.get(portalId);
        if (portalClaims == null || delta.previousClaims() == null
            || portalClaims.submittedClaims != delta.previousClaims()) {
            replacePortalClaimState(portalId, tieKey, priorityDistance, delta.claims(), affected);
            return;
        }
        if (delta.claims().isEmpty()) {
            releasePortalState(portalId, affected);
            return;
        }
        boolean priorityChanged = hasPriorityChanged(portalClaims, tieKey, priorityDistance);
        long passGeneration = ++generation;
        LongIterator removed = delta.removedKeys().iterator();
        while (removed.hasNext()) {
            long key = removed.nextLong();
            if (delta.claims().containsKey(key)) {
                continue;
            }
            ClaimSlot<C> removedSlot = portalClaims.claims.remove(key);
            if (removedSlot != null) {
                forgetLosingSlot(portalClaims, removedSlot);
                decrementClaimCount(key, portalClaims);
                affected.add(key);
            }
        }
        LongIterator changed = delta.changedKeys().iterator();
        while (changed.hasNext()) {
            long key = changed.nextLong();
            C nextClaim = delta.claims().get(key);
            if (nextClaim == null) {
                continue;
            }
            ClaimSlot<C> slot = portalClaims.claims.get(key);
            if (slot == null) {
                incrementClaimCount(key, portalClaims);
                portalClaims.claims.put(key, new ClaimSlot<C>(key, nextClaim, passGeneration));
                affected.add(key);
            } else if (!sameClaim(slot.claim, nextClaim)) {
                slot.claim = nextClaim;
                slot.affectedGeneration = passGeneration;
                affected.add(key);
            }
        }
        portalClaims.tieKey = tieKey;
        portalClaims.priorityDistance = priorityDistance;
        portalClaims.submittedClaims = delta.claims();
        if (priorityChanged && !contestedKeys.isEmpty()) {
            appendContestedKeys(portalClaims, passGeneration, affected);
        }
    }

    private void releasePortalState(UUID portalId, LongArrayList affected) {
        PortalClaims<C> existing = portals.remove(portalId);
        if (existing == null) {
            return;
        }
        LongIterator existingIterator = existing.claims.keySet().iterator();
        while (existingIterator.hasNext()) {
            long key = existingIterator.nextLong();
            affected.add(key);
            decrementClaimCount(key, existing);
        }
        existing.claims.clear();
    }

    public void clear() {
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

    public boolean isEmpty() {
        return winners.isEmpty();
    }

    public boolean drainLosingTransitions(UUID portalId, LongSet displaced, LongSet restored, boolean resync) {
        PortalClaims<C> portalClaims = portals.get(portalId);
        if (portalClaims == null) {
            return false;
        }
        boolean drained;
        if (resync) {
            drained = collectLosingKeys(portalClaims, displaced);
        } else {
            drained = !portalClaims.displacedKeys.isEmpty() || !portalClaims.restoredKeys.isEmpty();
            displaced.addAll(portalClaims.displacedKeys);
            restored.addAll(portalClaims.restoredKeys);
        }
        portalClaims.displacedKeys.clear();
        portalClaims.restoredKeys.clear();
        return drained;
    }

    public boolean hasFullBrightClaims() {
        return fullBrightWinnerCount > 0;
    }

    public Long2ObjectOpenHashMap<C> getWinningClaims() {
        return winningClaims;
    }

    public C getWinningClaim(long key) {
        return winningClaims.get(key);
    }

    public static <C extends Claim<C>> boolean isHigherPriority(double candidateDistance, String candidateTieKey, double currentDistance, String currentTieKey) {
        return isHigherPriority(candidateDistance, candidateTieKey, null, currentDistance, currentTieKey, null);
    }

    public static <C extends Claim<C>> boolean isHigherPriority(double candidateDistance,
                                    String candidateTieKey,
                                    C candidateClaim,
                                    double currentDistance,
                                    String currentTieKey,
                                    C currentClaim) {
        int maskTier = compareMaskTier(candidateClaim, currentClaim);
        if (maskTier != 0) {
            return maskTier > 0;
        }
        int heldTier = compareHeldTier(candidateClaim, currentClaim);
        if (heldTier != 0) {
            return heldTier > 0;
        }
        return isNearer(candidateDistance, candidateTieKey, currentDistance, currentTieKey);
    }

    private static <C extends Claim<C>> int compareMaskTier(C candidateClaim, C currentClaim) {
        if (candidateClaim == null || currentClaim == null) {
            return 0;
        }
        if (candidateClaim.isMaskAir() == currentClaim.isMaskAir()) {
            return 0;
        }
        return candidateClaim.isMaskAir() ? -1 : 1;
    }

    private static <C extends Claim<C>> int compareHeldTier(C candidateClaim, C currentClaim) {
        if (candidateClaim == null || currentClaim == null) {
            return 0;
        }
        if (candidateClaim.isHeld() == currentClaim.isHeld()) {
            return 0;
        }
        return candidateClaim.isHeld() ? -1 : 1;
    }

    private static boolean isNearer(double candidateDistance, String candidateTieKey, double currentDistance, String currentTieKey) {
        if (candidateDistance < currentDistance - PRIORITY_EPSILON) {
            return true;
        }
        if (candidateDistance > currentDistance + PRIORITY_EPSILON) {
            return false;
        }
        return candidateTieKey.compareTo(currentTieKey) < 0;
    }

    private void collectStaleKeys(PortalClaims<C> portalClaims, long passGeneration) {
        staleScratch.clear();
        ObjectIterator<ClaimSlot<C>> slotIterator = portalClaims.claims.values().iterator();
        while (slotIterator.hasNext()) {
            ClaimSlot<C> slot = slotIterator.next();
            if (slot.generation == passGeneration) {
                continue;
            }
            staleScratch.add(slot.key);
        }
    }

    private void appendContestedKeys(PortalClaims<C> portalClaims, long passGeneration, LongArrayList affected) {
        LongIterator contestedIterator = contestedKeys.iterator();
        while (contestedIterator.hasNext()) {
            long key = contestedIterator.nextLong();
            ClaimSlot<C> slot = portalClaims.claims.get(key);
            if (slot == null || slot.affectedGeneration == passGeneration) {
                continue;
            }
            slot.affectedGeneration = passGeneration;
            affected.add(key);
        }
    }

    private Result recomputeAffected(LongArrayList affected) {
        int affectedCount = affected.size();
        Result result = new Result(affectedCount);
        for (int i = 0; i < affectedCount; i++) {
            recomputeKey(affected.getLong(i), result);
        }
        return result;
    }

    private void recomputeKey(long key, Result result) {
        WinningClaim<C> previous = winners.get(key);
        WinnerChoice<C> choice = chooseWinner(key);
        if (choice.claimCount > 1) {
            result.conflicts++;
            markLosingClaimants(key, choice.owner, choice.claim);
        } else if (choice.owner != null) {
            updateLosing(choice.owner, choice.owner.claims.get(key), false);
        }
        PortalClaims<C> nextOwner = choice.owner;
        if (nextOwner == null) {
            if (previous != null) {
                if (previous.claim.isFullBright()) {
                    fullBrightWinnerCount--;
                    result.immediateLightingUpdate = true;
                }
                winners.remove(key);
                winningClaims.remove(key);
                result.addPacketChange(key);
                result.addDirtyLighting(key);
                result.reverts++;
            }
            return;
        }

        C nextClaim = choice.claim;
        boolean ownerChanged = previous == null || isDifferentOwner(previous.owner, nextOwner);
        if (previous != null && previous.claim == nextClaim) {
            previous.owner = nextOwner;
            if (ownerChanged) {
                result.winnerChanges++;
            }
            return;
        }
        boolean dataChanged = previous == null || !previous.claim.sameBlock(nextClaim);
        boolean lightChanged = previous == null || !previous.claim.sameLightSource(nextClaim);
        if (previous != null && previous.claim.isFullBright() && !nextClaim.isFullBright()) {
            result.immediateLightingUpdate = true;
        }
        if (previous == null) {
            winners.put(key, new WinningClaim<C>(nextOwner, nextClaim));
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
            result.addPacketChange(key);
        }
        if (ownerChanged) {
            result.winnerChanges++;
        }
        if (lightChanged) {
            result.addDirtyLighting(key);
        }
    }

    private void markLosingClaimants(long key, PortalClaims<C> winner, C winningClaim) {
        boolean liveWinner = !winningClaim.isHeld();
        Iterator<PortalClaims<C>> iterator = portals.values().iterator();
        while (iterator.hasNext()) {
            PortalClaims<C> portalClaims = iterator.next();
            ClaimSlot<C> slot = portalClaims.claims.get(key);
            if (slot != null && slot.claim != null) {
                updateLosing(portalClaims, slot, liveWinner && portalClaims != winner);
            }
        }
    }

    private static <C> void updateLosing(PortalClaims<C> portalClaims, ClaimSlot<C> slot, boolean losing) {
        if (slot == null || slot.losing == losing) {
            return;
        }
        slot.losing = losing;
        if (losing) {
            portalClaims.displacedKeys.add(slot.key);
            portalClaims.restoredKeys.remove(slot.key);
        } else {
            portalClaims.restoredKeys.add(slot.key);
            portalClaims.displacedKeys.remove(slot.key);
        }
    }

    private static <C> void forgetLosingSlot(PortalClaims<C> portalClaims, ClaimSlot<C> slot) {
        if (slot != null && slot.losing) {
            updateLosing(portalClaims, slot, false);
        }
    }

    private static <C> boolean collectLosingKeys(PortalClaims<C> portalClaims, LongSet out) {
        boolean any = false;
        ObjectIterator<ClaimSlot<C>> slots = portalClaims.claims.values().iterator();
        while (slots.hasNext()) {
            ClaimSlot<C> slot = slots.next();
            if (slot.losing) {
                out.add(slot.key);
                any = true;
            }
        }
        return any;
    }

    private static void appendAffectedKeys(LongArrayList affected, LongOpenHashSet stagedKeys) {
        int affectedCount = affected.size();
        for (int i = 0; i < affectedCount; i++) {
            stagedKeys.add(affected.getLong(i));
        }
    }

    private WinnerChoice<C> chooseWinner(long key) {
        WinnerChoice<C> choice = winnerScratch;
        choice.owner = null;
        choice.claim = null;
        choice.claimCount = 0;
        int storedClaimCount = claimCounts.get(key);
        if (storedClaimCount <= 0) {
            return choice;
        }
        if (storedClaimCount == 1) {
            PortalClaims<C> singleOwner = singleOwners.get(key);
            if (singleOwner != null) {
                ClaimSlot<C> slot = singleOwner.claims.get(key);
                if (slot != null && slot.claim != null) {
                    choice.owner = singleOwner;
                    choice.claim = slot.claim;
                    choice.claimCount = 1;
                    return choice;
                }
            }
        }

        Iterator<PortalClaims<C>> iterator = portals.values().iterator();
        while (iterator.hasNext()) {
            PortalClaims<C> portalClaims = iterator.next();
            ClaimSlot<C> slot = portalClaims.claims.get(key);
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

    private static <C extends Claim<C>> boolean isDifferentOwner(PortalClaims<C> previousOwner, PortalClaims<C> nextOwner) {
        return previousOwner != nextOwner && !previousOwner.portalId.equals(nextOwner.portalId);
    }

    private static <C extends Claim<C>> boolean hasPriorityChanged(PortalClaims<C> existing, String tieKey, double priorityDistance) {
        if (!existing.tieKey.equals(tieKey)) {
            return true;
        }
        return Math.abs(existing.priorityDistance - priorityDistance) > PRIORITY_EPSILON;
    }

    private static <C extends Claim<C>> boolean sameClaim(C previous, C next) {
        if (previous == next) {
            return true;
        }
        if (previous == null || next == null) {
            return false;
        }
        return previous.isMaskAir() == next.isMaskAir()
            && previous.isHeld() == next.isHeld()
            && previous.sameBlock(next)
            && previous.sameLightSource(next);
    }

    private void incrementClaimCount(long key, PortalClaims<C> owner) {
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

    private void decrementClaimCount(long key, PortalClaims<C> removedOwner) {
        int count = claimCounts.get(key);
        if (count <= 1) {
            claimCounts.remove(key);
            singleOwners.remove(key);
            return;
        }
        if (count == 2) {
            claimCounts.put(key, 1);
            contestedKeys.remove(key);
            PortalClaims<C> remainingOwner = findRemainingOwner(key, removedOwner);
            if (remainingOwner == null) {
                singleOwners.remove(key);
            } else {
                singleOwners.put(key, remainingOwner);
            }
            return;
        }
        claimCounts.put(key, count - 1);
    }

    private PortalClaims<C> findRemainingOwner(long key, PortalClaims<C> removedOwner) {
        Iterator<PortalClaims<C>> iterator = portals.values().iterator();
        while (iterator.hasNext()) {
            PortalClaims<C> portalClaims = iterator.next();
            if (portalClaims == removedOwner) {
                continue;
            }
            if (portalClaims.claims.containsKey(key)) {
                return portalClaims;
            }
        }
        return null;
    }

    public static final class Result {
        private final int expectedKeys;
        private LongOpenHashSet packetChangeKeys;
        private LongOpenHashSet dirtyLightingKeys;
        private int conflicts;
        private int winnerChanges;
        private int reverts;
        private boolean immediateLightingUpdate;

        public Result() {
            this(0);
        }

        Result(int expectedKeys) {
            this.expectedKeys = expectedKeys;
            this.conflicts = 0;
            this.winnerChanges = 0;
            this.reverts = 0;
            this.immediateLightingUpdate = false;
        }

        public LongOpenHashSet getPacketChangeKeys() {
            if (packetChangeKeys == null) {
                packetChangeKeys = new LongOpenHashSet(0);
            }
            return packetChangeKeys;
        }

        public LongOpenHashSet getDirtyLightingKeys() {
            if (dirtyLightingKeys == null) {
                dirtyLightingKeys = new LongOpenHashSet(0);
            }
            return dirtyLightingKeys;
        }

        private void addPacketChange(long key) {
            if (packetChangeKeys == null) {
                packetChangeKeys = new LongOpenHashSet(expectedKeys);
            }
            packetChangeKeys.add(key);
        }

        private void addDirtyLighting(long key) {
            if (dirtyLightingKeys == null) {
                dirtyLightingKeys = new LongOpenHashSet(expectedKeys);
            }
            dirtyLightingKeys.add(key);
        }

        public int getConflicts() {
            return conflicts;
        }

        public int getWinnerChanges() {
            return winnerChanges;
        }

        public int getReverts() {
            return reverts;
        }

        public boolean requiresImmediateLightingUpdate() {
            return immediateLightingUpdate;
        }
    }

    private static final class PortalClaims<C> {
        private final UUID portalId;
        private final Long2ObjectOpenHashMap<ClaimSlot<C>> claims;
        private Long2ObjectMap<C> submittedClaims;
        private String tieKey;
        private double priorityDistance;
        private final LongOpenHashSet displacedKeys;
        private final LongOpenHashSet restoredKeys;

        private PortalClaims(UUID portalId) {
            this.portalId = portalId;
            this.claims = new Long2ObjectOpenHashMap<ClaimSlot<C>>(256);
            this.tieKey = portalId.toString();
            this.priorityDistance = 0.0D;
            this.displacedKeys = new LongOpenHashSet(8);
            this.restoredKeys = new LongOpenHashSet(8);
        }
    }

    private static final class ClaimSlot<C> {
        private final long key;
        private C claim;
        private long generation;
        private long affectedGeneration;
        private boolean losing;

        private ClaimSlot(long key, C claim, long generation) {
            this.key = key;
            this.claim = claim;
            this.generation = generation;
            this.affectedGeneration = generation;
        }
    }

    private static final class WinnerChoice<C> {
        private PortalClaims<C> owner;
        private C claim;
        private int claimCount;

        private WinnerChoice() {
            this.owner = null;
            this.claim = null;
            this.claimCount = 0;
        }
    }

    private static final class WinningClaim<C> {
        private PortalClaims<C> owner;
        private C claim;

        private WinningClaim(PortalClaims<C> owner, C claim) {
            this.owner = owner;
            this.claim = claim;
        }
    }
}
